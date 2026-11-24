package app.genesis.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * User-started loopback bridge between Phone A's Termux control server and the
 * Android OS-isolated guest service. The administrator token is held in memory
 * only and is sent exclusively to 127.0.0.1.
 */
class AndroidGuestBridgeService : Service() {
    private val running = AtomicBoolean(false)
    private var bridgeThread: Thread? = null
    private val channelId = "genesis_isolated_guest_bridge"
    private val notificationId = 4103

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val token = intent?.getStringExtra(EXTRA_ADMIN_TOKEN)?.trim().orEmpty()
        if (token.isBlank() || token.length > 4096 || token.any { it.isWhitespace() }) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val notification = notification("Connecting to Phone A Termux control server…")
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(notificationId, notification)
        }
        if (running.compareAndSet(false, true)) {
            bridgeThread = Thread({ bridgeLoop(token) }, "Genesis-Android-Guest-Bridge").apply { start() }
        }
        return START_NOT_STICKY
    }

    private fun bridgeLoop(token: String) {
        while (running.get()) {
            try {
                val response = request(LOCAL_HQ + "/api/guest/next", "GET", token, maxResponseBytes = MAX_GUEST_JOB_RESPONSE_BYTES)
                val job = response.optJSONObject("job")
                if (job == null) {
                    updateNotification("Connected · waiting for isolated guest tasks")
                    Thread.sleep(500)
                    continue
                }
                val result = try {
                    executeIsolatedWasm(job)
                } catch (error: Exception) {
                    JSONObject().put("requestId", job.optString("requestId"))
                        .put("ok", false)
                        .put("error", (error.message ?: "Isolated guest bridge failed").take(512))
                }
                request(LOCAL_HQ + "/api/guest/result", "POST", token, result)
                updateNotification("Completed isolated guest task · waiting")
            } catch (error: Exception) {
                if (!running.get()) break
                updateNotification("Termux bridge unavailable · retrying")
                try { Thread.sleep(1200) } catch (_: InterruptedException) { break }
            }
        }
    }

    private fun executeIsolatedWasm(job: JSONObject): JSONObject {
        val requestId = job.optString("requestId")
        require(requestId.matches(Regex("[A-Za-z0-9._-]{8,80}"))) { "Invalid guest request ID" }
        val expectedDigest = job.optString("moduleSha256")
        require(expectedDigest.matches(Regex("[a-f0-9]{64}"))) { "Invalid guest module digest" }
        val encoded = job.optString("programBase64")
        require(encoded.isNotBlank() && encoded.length <= MAX_GUEST_MODULE_BASE64_CHARS) { "Invalid guest module payload" }
        val program = Base64.decode(encoded, Base64.DEFAULT)
        require(program.size in 8..MAX_GUEST_MODULE_BYTES) { "Guest module size outside the allowed range" }
        require(Base64.encodeToString(program, Base64.NO_WRAP) == encoded) { "Guest module is not canonical base64" }
        val exportName = job.optString("exportName")
        require(exportName.matches(Regex("[A-Za-z_$][A-Za-z0-9_$]{0,127}"))) { "Invalid guest export name" }
        val argsJson = job.optJSONArray("args") ?: JSONArray()
        require(argsJson.length() <= 64) { "Too many guest arguments" }
        val args = LongArray(argsJson.length()) { index ->
            val number = argsJson.get(index)
            require(number is Number && number.toDouble().isFinite() &&
                number.toDouble() == number.toLong().toDouble() &&
                kotlin.math.abs(number.toDouble()) <= 9007199254740991.0) { "Guest arguments must be safe integers" }
            number.toLong()
        }
        val maxSteps = job.optInt("maxSteps", 100000)
        val maxMemoryPages = job.optInt("maxMemoryPages", 64)
        val timeoutMs = job.optLong("timeoutMs", 5000L)
        require(maxSteps in 1..GuestWasmVm.MAX_STEPS) { "Guest instruction budget outside the allowed range" }
        require(maxMemoryPages in 1..GuestWasmVm.MAX_MEMORY_PAGES) { "Guest memory budget outside the allowed range" }
        require(timeoutMs in 1..GuestWasmVm.MAX_EXECUTION_MS) { "Guest deadline outside the allowed range" }

        val responseData = AtomicReference<Bundle?>(null)
        val responseLatch = CountDownLatch(1)
        val bindLatch = CountDownLatch(1)
        val target = AtomicReference<Messenger?>(null)
        val reply = Messenger(Handler(Looper.getMainLooper()) { message ->
            responseData.set(Bundle(message.data))
            responseLatch.countDown()
            true
        })
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                target.set(Messenger(binder))
                bindLatch.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) {
                bindLatch.countDown()
            }
        }
        val bound = bindService(Intent(this, IsolatedGuestComputeService::class.java), connection, BIND_AUTO_CREATE)
        if (!bound) throw IllegalStateException("Android refused to bind the isolated guest process")
        try {
            if (!bindLatch.await(3000, TimeUnit.MILLISECONDS)) throw IllegalStateException("Isolated guest service did not bind in time")
            val messenger = target.get() ?: throw IllegalStateException("Isolated guest service disconnected before execution")
            val request = Message.obtain(null, IsolatedGuestComputeService.MSG_EXECUTE_WASM)
            request.data = Bundle().apply {
                putString(IsolatedGuestComputeService.KEY_REQUEST_ID, requestId)
                putString(IsolatedGuestComputeService.KEY_MODULE_SHA256, expectedDigest)
                putString(IsolatedGuestComputeService.KEY_EXPORT_NAME, exportName)
                putLongArray(IsolatedGuestComputeService.KEY_ARGUMENTS, args)
                putInt(IsolatedGuestComputeService.KEY_MAX_STEPS, maxSteps)
                putInt(IsolatedGuestComputeService.KEY_MAX_MEMORY_PAGES, maxMemoryPages)
                putLong(IsolatedGuestComputeService.KEY_TIMEOUT_MS, timeoutMs)
            }
            request.replyTo = reply
            val programTransfer = GuestWasmModuleTransfer.attach(this, request.data, program)
            try {
                messenger.send(request)
            } finally {
                programTransfer.close()
            }
            if (!responseLatch.await(timeoutMs + 2500, TimeUnit.MILLISECONDS)) {
                val cancel = Message.obtain(null, IsolatedGuestComputeService.MSG_CANCEL)
                cancel.data = Bundle().apply {
                    putString(IsolatedGuestComputeService.KEY_REQUEST_ID, requestId)
                }
                try { messenger.send(cancel) } catch (_: Exception) { }
                throw IllegalStateException("Isolated guest execution did not return before its deadline")
            }
            val data = responseData.get() ?: throw IllegalStateException("Isolated guest returned no response")
            val ok = data.getBoolean(IsolatedGuestComputeService.KEY_OK, false)
            return JSONObject()
                .put("requestId", data.getString(IsolatedGuestComputeService.KEY_REQUEST_ID).orEmpty())
                .put("ok", ok)
                .put("result", if (ok) data.getLong(IsolatedGuestComputeService.KEY_RESULT) else JSONObject.NULL)
                .put("moduleSha256", if (ok) data.getString(IsolatedGuestComputeService.KEY_MODULE_SHA256) else JSONObject.NULL)
                .put("isolatedUid", data.getInt(IsolatedGuestComputeService.KEY_ISOLATED_UID, -1))
                .put("ownerUid", data.getInt(IsolatedGuestComputeService.KEY_OWNER_UID, -1))
                .put("networkPermission", data.getBoolean(IsolatedGuestComputeService.KEY_NETWORK_PERMISSION, true))
                .put("privateDataAccess", data.getBoolean(IsolatedGuestComputeService.KEY_PRIVATE_DATA_ACCESS, true))
                .put("error", data.getString(IsolatedGuestComputeService.KEY_ERROR).orEmpty())
        } finally {
            try { unbindService(connection) } catch (_: Exception) { }
        }
    }

    private fun request(
        url: String,
        method: String,
        token: String,
        body: JSONObject? = null,
        maxResponseBytes: Int = DEFAULT_MAX_RESPONSE_BYTES
    ): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 3000
            connection.readTimeout = 8000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.use { readBoundedUtf8(it, maxResponseBytes) }.orEmpty()
            if (code !in 200..299) throw IllegalStateException("Phone A control server returned HTTP $code")
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }

    private fun readBoundedUtf8(input: InputStream, maxBytes: Int): String {
        require(maxBytes in 1..(2 * 1024 * 1024)) { "Control response byte limit is outside its safe range" }
        val output = ByteArrayOutputStream(minOf(maxBytes, 8192))
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > maxBytes) {
                throw IllegalStateException("Phone A control response exceeds its configured byte limit")
            }
            output.write(buffer, 0, count)
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(
                channelId, "Genesis isolated guest bridge", NotificationManager.IMPORTANCE_LOW
            ))
        }
    }

    private fun notification(message: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, channelId) else Notification.Builder(this)
        return builder.setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Genesis isolated guest bridge")
            .setContentText(message)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification(message: String) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(notificationId, notification(message))
    }

    override fun onDestroy() {
        running.set(false)
        bridgeThread?.interrupt()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val EXTRA_ADMIN_TOKEN = "genesis_admin_token"
        private const val LOCAL_HQ = "https://127.0.0.1:8789"
        private const val MAX_GUEST_MODULE_BYTES = 1024 * 1024
        private const val MAX_GUEST_MODULE_BASE64_CHARS = 1_398_104
        private const val MAX_GUEST_JOB_RESPONSE_BYTES = 1_500_000
        private const val DEFAULT_MAX_RESPONSE_BYTES = 65_536
    }
}
