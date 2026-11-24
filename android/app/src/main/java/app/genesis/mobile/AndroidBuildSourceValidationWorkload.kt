package app.genesis.mobile

import org.json.JSONObject
import java.security.MessageDigest
import java.util.Base64

/**
 * Validates the deliberately narrow GenB Java build input before a compiler is invoked.
 * This stage does not claim to compile or emit an APK.
 */
object AndroidBuildSourceValidationWorkload {
    private const val MAX_FILES = 8
    private const val MAX_TOTAL_BYTES = 40 * 1024
    private const val MAX_FILE_BYTES = 32 * 1024
    private const val PROFILE_ID = "genb-analytics-java-webview-v1"

    private fun fail(message: String): Nothing = throw IllegalArgumentException(message)

    fun execute(payload: JSONObject, shouldStop: () -> Boolean = { false }): JSONObject {
        if (shouldStop()) throw IllegalStateException("WORKER_STOP_REQUESTED")
        val allowed = setOf("profileId", "applicationId", "versionCode", "versionName",
            "minSdk", "targetSdk", "entryPoint", "manifest", "files")
        require(payload.keys().asSequence().all { it in allowed }) {
            "Android build validation payload contains unsupported fields"
        }
        require(payload.optString("profileId") == PROFILE_ID) { "Unsupported Android build profile" }
        val appId = payload.optString("applicationId")
        require(appId == "com.genb.analytics") { "Application ID does not match the approved GenB profile" }
        val versionCode = payload.optInt("versionCode", -1)
        require(versionCode in 1..1000000) { "Invalid Android versionCode" }
        val versionName = payload.optString("versionName")
        require(versionName.matches(Regex("[A-Za-z0-9][A-Za-z0-9._+-]{0,63}"))) { "Invalid Android versionName" }
        val minSdk = payload.optInt("minSdk", -1)
        val targetSdk = payload.optInt("targetSdk", -1)
        require(minSdk == 26 && targetSdk in minSdk..35) { "Android SDK range is outside the approved GenB profile" }
        val entryPoint = payload.optString("entryPoint")
        require(entryPoint == "com.genb.analytics.MainActivity") { "Unexpected Android entry point" }
        val manifestPath = payload.optString("manifest")
        require(manifestPath == "android/app/src/main/AndroidManifest.xml") { "Unexpected Android manifest path" }

        val files = payload.optJSONArray("files") ?: fail("Android build files array is required")
        require(files.length() in 2..MAX_FILES) { "Android build must contain 2 to $MAX_FILES files" }
        val seen = HashSet<String>()
        var totalBytes = 0
        var hasEntry = false
        var hasManifest = false
        val digest = MessageDigest.getInstance("SHA-256")
        val ordered = (0 until files.length()).map { files.getJSONObject(it) }
            .sortedBy { it.optString("path") }

        for (file in ordered) {
            if (shouldStop()) throw IllegalStateException("WORKER_STOP_REQUESTED")
            require(file.keys().asSequence().all { it == "path" || it == "contentBase64" }) {
                "Android source file contains unsupported metadata"
            }
            val path = file.optString("path")
            require(path.startsWith("android/app/src/main/") && !path.startsWith("/") &&
                '\\' !in path && path.split('/').none { it.isBlank() || it == "." || it == ".." } &&
                path.length <= 240) { "Android source path is outside the approved project tree" }
            require(seen.add(path)) { "Duplicate Android source path" }
            val encoded = file.optString("contentBase64")
            require(encoded.isNotBlank() && encoded.length <= ((MAX_FILE_BYTES + 2) / 3) * 4) {
                "Android source file is empty or exceeds its encoded size limit"
            }
            val bytes = try { Base64.getDecoder().decode(encoded) }
                catch (_: IllegalArgumentException) { fail("Android source file has invalid base64") }
            require(bytes.isNotEmpty() && bytes.size <= MAX_FILE_BYTES &&
                Base64.getEncoder().encodeToString(bytes) == encoded) { "Android source file size or base64 encoding is invalid" }
            totalBytes += bytes.size
            require(totalBytes <= MAX_TOTAL_BYTES) { "Android source bundle exceeds 40 KiB" }
            val text = bytes.toString(Charsets.UTF_8)
            require(!text.contains('\u0000')) { "Android source file contains a NUL byte" }
            when {
                path == manifestPath -> {
                    hasManifest = true
                    require(text.contains("<manifest") && text.contains("android.permission.INTERNET") &&
                        text.contains("android:name=\".MainActivity\"")) { "GenB Android manifest failed profile checks" }
                }
                path == "android/app/src/main/java/com/genb/analytics/MainActivity.java" -> {
                    hasEntry = true
                    require(text.contains("package com.genb.analytics;") &&
                        text.contains("class MainActivity") &&
                        text.contains("extends Activity") &&
                        text.contains("WebView")) { "GenB Java entry point failed profile checks" }
                }
                path.endsWith(".kt") -> fail("Kotlin source is not supported by the focused Java build profile")
                path.endsWith(".java") -> require(path.startsWith("android/app/src/main/java/com/genb/analytics/")) {
                    "Java source is outside the approved GenB package"
                }
                else -> fail("Unsupported source or resource type in focused GenB build profile")
            }
            val nameBytes = path.toByteArray(Charsets.UTF_8)
            digest.update(nameBytes.size.toString().toByteArray(Charsets.US_ASCII))
            digest.update(0.toByte())
            digest.update(nameBytes)
            digest.update(bytes)
        }
        require(hasEntry && hasManifest) { "GenB source bundle is missing its entry point or manifest" }
        if (shouldStop()) throw IllegalStateException("WORKER_STOP_REQUESTED")
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        return JSONObject()
            .put("status", "source-validated")
            .put("profileId", PROFILE_ID)
            .put("applicationId", appId)
            .put("versionCode", versionCode)
            .put("versionName", versionName)
            .put("minSdk", minSdk)
            .put("targetSdk", targetSdk)
            .put("sourceFiles", files.length())
            .put("sourceBytes", totalBytes)
            .put("sourceSha256", hash)
            .put("nextStage", "embedded-java-compiler")
            .put("apkProduced", false)
    }
}
