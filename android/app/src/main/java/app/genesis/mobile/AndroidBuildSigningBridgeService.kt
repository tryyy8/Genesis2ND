package app.genesis.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONObject
import com.android.apksig.ApkSigner
import com.android.apksig.ApkVerifier
import java.io.File
import java.util.zip.ZipFile
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.Date
import javax.security.auth.x500.X500Principal
import java.util.concurrent.atomic.AtomicBoolean

/** Phone A-only signing bridge. The AndroidKeyStore private key never leaves this device. */
class AndroidBuildSigningBridgeService : Service() {
    private val running = AtomicBoolean(false)
    private val rotateRequested = AtomicBoolean(false)
    private var thread: Thread? = null
    private val channelId = "genesis_android_build_signing"
    private val notificationId = 4104

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(
            channelId, "Genesis APK signing", NotificationManager.IMPORTANCE_LOW
        ))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val token = intent?.getStringExtra(EXTRA_ADMIN_TOKEN)?.trim().orEmpty()
        if (token.isBlank() || token.length > 4096 || token.any { it.isWhitespace() }) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val notification = notification("Connecting to Phone A signing queue…")
        if (Build.VERSION.SDK_INT >= 34) startForeground(
            notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        ) else startForeground(notificationId, notification)
        val rotateIdentity = intent?.getBooleanExtra(EXTRA_ROTATE_SIGNING_IDENTITY, false) == true
        if (running.compareAndSet(false, true)) {
            thread = Thread({ signingLoop(token, rotateIdentity) }, "Genesis-Android-Build-Signer").apply { start() }
        } else if (rotateIdentity) {
            rotateRequested.set(true)
        }
        return START_NOT_STICKY
    }

    private fun signingLoop(token: String, rotateAtStart: Boolean) {
        var rotateOnStart = rotateAtStart
        while (running.get()) {
            try {
                if (rotateOnStart || rotateRequested.compareAndSet(true, false)) {
                    rotateOnStart = false
                    rotateSigningIdentity()
                    updateNotification("GenB signing identity rotated and its new certificate pin was saved")
                }
                val response = request("/api/android-build/sign-next", "GET", token)
                val job = response.optJSONObject("job")
                if (job == null) {
                    updateNotification("Phone A signing bridge ready · waiting for an unsigned APK")
                    Thread.sleep(1000)
                    continue
                }
                val result = try {
                    signJob(job)
                } catch (error: Exception) {
                    JSONObject().put("requestId", job.optString("requestId"))
                        .put("ok", false).put("error", (error.message ?: "APK signing failed").take(512))
                }
                request("/api/android-build/sign-result", "POST", token, result)
                updateNotification(if (result.optBoolean("ok")) "GenB APK signed and independently verified" else "GenB APK signing failed")
            } catch (error: Exception) {
                if (!running.get()) break
                updateNotification("Phone A signing queue unavailable · retrying")
                try { Thread.sleep(1500) } catch (_: InterruptedException) { break }
            }
        }
    }

    private fun signJob(job: JSONObject): JSONObject {
        val requestId = job.optString("requestId")
        require(requestId.matches(Regex("[0-9a-f-]{36}"))) { "Invalid APK signing request ID" }
        require(job.optString("applicationId") == "com.genb.analytics") { "Signing request is not for GenB Analytics" }
        val versionCode = job.optInt("versionCode", -1)
        val versionName = job.optString("versionName")
        require(versionCode in 1..1000000 && versionName.matches(Regex("[A-Za-z0-9][A-Za-z0-9._+-]{0,63}"))) {
            "APK version metadata is invalid"
        }
        require(job.optInt("minSdk", -1) == 26 && job.optInt("targetSdk", -1) in 26..35) {
            "APK SDK metadata is outside the approved profile"
        }
        val encoded = job.optString("apkBase64")
        require(encoded.isNotBlank() && encoded.length <= MAX_UNSIGNED_BASE64) { "Unsigned APK payload exceeds the limit" }
        val unsignedBytes = Base64.getDecoder().decode(encoded)
        require(unsignedBytes.isNotEmpty() && unsignedBytes.size <= MAX_UNSIGNED_BYTES &&
            Base64.getEncoder().encodeToString(unsignedBytes) == encoded) { "Unsigned APK encoding or size is invalid" }
        val inputHash = sha256(unsignedBytes)
        require(inputHash == job.optString("apkSha256")) { "Unsigned APK SHA-256 mismatch" }

        val work = File(cacheDir, "genb-apk-signing").apply {
            deleteRecursively()
            require(mkdirs()) { "Unable to create private signing workspace" }
        }
        try {
            val input = File(work, "unsigned.apk")
            val output = File(work, "signed.apk")
            input.writeBytes(unsignedBytes)
            val before = packageInfo(input)
            require(before.packageName == "com.genb.analytics" && versionOf(before) == versionCode &&
                before.versionName == versionName) {
                "Unsigned APK manifest package/version does not match the requested GenB build"
            }
            ZipFile(input).use { zip ->
                val entry = zip.getEntry("META-INF/genesis-build.json")
                    ?: throw IllegalArgumentException("Unsigned APK is missing its Genesis build attestation")
                val attestation = zip.getInputStream(entry).bufferedReader().use { JSONObject(it.readText()) }
                require(attestation.optString("profileId") == "genb-analytics-java-webview-v1" &&
                    attestation.optString("applicationId") == "com.genb.analytics" &&
                    attestation.optInt("versionCode") == versionCode &&
                    attestation.optString("versionName") == versionName &&
                    attestation.optString("sourceSha256") == job.optString("sourceSha256") &&
                    attestation.optString("androidJarSha256") == job.optString("androidJarSha256") &&
                    attestation.optString("toolchainVersion") == "android-api-35-r02") {
                    "Unsigned APK Genesis build attestation does not match the HQ signing request"
                }
                val dexEntry = zip.getEntry("classes.dex")
                    ?: throw IllegalArgumentException("Unsigned APK is missing classes.dex")
                val dexBytes = zip.getInputStream(dexEntry).use { it.readBytes() }
                require(dexBytes.size in 112..(640 * 1024) &&
                    sha256(dexBytes) == attestation.optString("dexSha256")) {
                    "Unsigned APK DEX digest does not match its Genesis build attestation"
                }
            }

            val (privateKey, certificate) = signingIdentity()
            val signer = ApkSigner.SignerConfig.Builder("Genesis GenB", privateKey, listOf(certificate)).build()
            ApkSigner.Builder(listOf(signer))
                .setInputApk(input)
                .setOutputApk(output)
                .setMinSdkVersion(26)
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(true)
                .setV4SigningEnabled(false)
                .build()
                .sign()

            val verification = ApkVerifier.Builder(output).build().verify()
            require(verification.isVerified) {
                "APK signature verification failed: " + verification.errors.joinToString("; ").take(400)
            }
            val after = packageInfo(output)
            require(after.packageName == "com.genb.analytics" && versionOf(after) == versionCode &&
                after.versionName == versionName) {
                "Signed APK package/version metadata changed during signing"
            }
            val signedBytes = output.readBytes()
            require(signedBytes.size in 128..MAX_SIGNED_BYTES) { "Signed APK exceeds the output limit" }
            val certHash = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
                .joinToString("") { "%02x".format(it) }
            return JSONObject().put("requestId", requestId).put("ok", true)
                .put("packageName", after.packageName).put("versionCode", versionOf(after))
                .put("versionName", versionName).put("certificateSha256", certHash)
                .put("signedApkSha256", sha256(signedBytes))
                .put("signedApkBase64", Base64.getEncoder().encodeToString(signedBytes))
                .put("verified", true)
        } finally {
            work.deleteRecursively()
        }
    }

    private fun packageInfo(apk: File): PackageInfo {
        @Suppress("DEPRECATION")
        return packageManager.getPackageArchiveInfo(apk.absolutePath, 0)
            ?: throw IllegalArgumentException("Android package parser rejected the generated binary manifest")
    }

    private fun versionOf(info: PackageInfo): Int =
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt() else info.versionCode

    private fun signingIdentity(): Pair<PrivateKey, X509Certificate> {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!store.containsAlias(KEY_ALIAS)) {
            val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore")
            val spec = KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setKeySize(2048)
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                .setCertificateSubject(X500Principal("CN=Genesis GenB APK Signing"))
                .setCertificateSerialNumber(BigInteger.ONE)
                .setCertificateNotBefore(Date(0))
                .setCertificateNotAfter(Date(System.currentTimeMillis() + 10L * 365 * 24 * 60 * 60 * 1000))
                .build()
            generator.initialize(spec)
            generator.generateKeyPair()
        }
        val key = store.getKey(KEY_ALIAS, null) as? PrivateKey
            ?: throw IllegalStateException("Phone A GenB signing key is unavailable")
        val certificate = store.getCertificate(KEY_ALIAS) as? X509Certificate
            ?: throw IllegalStateException("Phone A GenB signing certificate is unavailable")
        pinSigningCertificate(certificate)
        return key to certificate
    }

    private fun rotateSigningIdentity() {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (store.containsAlias(KEY_ALIAS)) store.deleteEntry(KEY_ALIAS)
        val preferences = getSharedPreferences(SIGNING_PREFS, MODE_PRIVATE)
        check(preferences.edit().remove(PINNED_CERTIFICATE_SHA256).commit()) {
            "Unable to clear the previous Phone A signing certificate pin"
        }
        signingIdentity()
    }

    /** TOFU pin stored in Phone A app-private preferences, independent of Control Center restarts. */
    private fun pinSigningCertificate(certificate: X509Certificate) {
        val fingerprint = sha256(certificate.encoded)
        val preferences = getSharedPreferences(SIGNING_PREFS, MODE_PRIVATE)
        val pinned = preferences.getString(PINNED_CERTIFICATE_SHA256, null)
        if (pinned == null) {
            check(preferences.edit().putString(PINNED_CERTIFICATE_SHA256, fingerprint).commit()) {
                "Unable to persist the Phone A GenB signing certificate pin"
            }
        } else {
            require(pinned == fingerprint) {
                "Phone A GenB signing certificate changed from its persistent pin; explicit key rotation is required"
            }
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun request(path: String, method: String, token: String, body: JSONObject? = null): JSONObject {
        val connection = URL(LOCAL_HQ + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 3000
            connection.readTimeout = 12000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IllegalStateException("Phone A signing API returned HTTP $code")
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }

    private fun notification(message: String): Notification =
        Notification.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Genesis APK signing")
            .setContentText(message)
            .setOngoing(true).setOnlyAlertOnce(true).build()

    private fun updateNotification(message: String) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(notificationId, notification(message))
    }

    override fun onDestroy() {
        running.set(false)
        thread?.interrupt()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val EXTRA_ADMIN_TOKEN = "genesis_android_build_admin_token"
        const val EXTRA_ROTATE_SIGNING_IDENTITY = "genesis_android_build_rotate_signing_identity"
        private const val KEY_ALIAS = "genesis.genb.apk.signing.v1"
        private const val SIGNING_PREFS = "genesis-android-build-signing"
        private const val PINNED_CERTIFICATE_SHA256 = "genb_signing_certificate_sha256"
        private const val LOCAL_HQ = "https://127.0.0.1:8789"
        private const val MAX_UNSIGNED_BYTES = 700 * 1024
        private const val MAX_UNSIGNED_BASE64 = 960_000
        private const val MAX_SIGNED_BYTES = 900 * 1024
    }
}
