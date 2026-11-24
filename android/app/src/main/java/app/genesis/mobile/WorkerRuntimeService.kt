package app.genesis.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

class WorkerRuntimeService : Service() {
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val channelId = "genesis_compute_worker"

    override fun onCreate() {
        super.onCreate()
        createChannel()
        getSharedPreferences("genesis-mobile", MODE_PRIVATE).edit().putBoolean("worker_running", true).apply()
        val notification = notification("Connecting to Genesis HQ…")
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(4102, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(4102, notification)
        }
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Genesis:ComputeWorker").apply {
            setReferenceCounted(false)
            acquire(10 * 60 * 60 * 1000L)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running.compareAndSet(false, true)) {
            thread = Thread({ workerLoop() }, "Genesis-Worker").apply { start() }
        }
        return START_STICKY
    }

    private fun workerLoop() {
        val prefs = getSharedPreferences("genesis-mobile", MODE_PRIVATE)
        val base = (prefs.getString("hq_url", "") ?: "").trim().trimEnd('/')
        val workerId = prefs.getString("device_id", null) ?: ""
        val token = SecureTokenStore(this).get()
        if (base.isBlank() || workerId.isBlank() || token.isNullOrBlank()) {
            updateNotification("Not configured — open Genesis and verify pairing")
            stopSelf()
            return
        }
        var registered = false
        var lastTelemetryAt = 0L
        while (running.get()) {
            try {
                if (!registered) {
                    request("$base/api/worker/register", "POST", token,
                        JSONObject().put("workerId", workerId).put("info", JSONObject().put("runtime", "android-foreground-service").put("platform", "Android").put("deviceModel", Build.MODEL).put("androidRelease", Build.VERSION.RELEASE).put("sdkInt", Build.VERSION.SDK_INT)))
                    registered = true
                }
                val now = SystemClock.elapsedRealtime()
                if (now - lastTelemetryAt >= 30_000L) {
                    try {
                        request("$base/api/worker/telemetry", "POST", token, ThermalMonitor.collect(this, workerId))
                        lastTelemetryAt = now
                    } catch (_: Exception) {
                        // Thermal reporting is best-effort and must never block approved compute work.
                    } finally {
                        lastTelemetryAt = now
                    }
                }
                val response = request("$base/api/worker/next?workerId=$workerId", "GET", token)
                val job = response.optJSONObject("job")
                if (job == null) {
                    updateNotification("Connected · waiting for jobs")
                    Thread.sleep(1800)
                    continue
                }
                val id = job.getString("jobId")
                val claimToken = job.optString("claimToken", "")
                val workload = job.getString("workload")
                val maxRuntimeMs = (job.optJSONObject("limits")?.optLong("maxRuntimeMs", 60000L) ?: 60000L).coerceIn(1L, 240000L)
                val deadlineElapsed = SystemClock.elapsedRealtime() + maxRuntimeMs
                val stopRequested = AtomicBoolean(false)
                val executionFinished = AtomicBoolean(false)
                updateNotification("Executing $workload")
                val statusPoller = Thread({
                    while (running.get() && !executionFinished.get() && !stopRequested.get()) {
                        try {
                            val status = request("$base/api/worker/job-status?jobId=$id&claimToken=$claimToken", "GET", token)
                            if (!status.optBoolean("claimValid", false) ||
                                status.optBoolean("cancelRequested", false) ||
                                status.optBoolean("deadlineExceeded", false)) stopRequested.set(true)
                        } catch (_: Exception) {
                            // Temporary network loss does not cancel work; the local deadline still applies.
                        }
                        try { Thread.sleep(1000L) } catch (_: InterruptedException) { break }
                    }
                }, "Genesis-Worker-Cancel-Poll").apply {
                    isDaemon = true
                    start()
                }
                var lastProgressNotification = 0L
                val output = try {
                    NativeWorkloadEngine.execute(
                        workload,
                        job.optJSONObject("payload") ?: JSONObject(),
                        progress = { done, total, detail ->
                            val progressNow = SystemClock.elapsedRealtime()
                            if (done == total || progressNow - lastProgressNotification >= 100L) {
                                lastProgressNotification = progressNow
                                updateNotification("$workload · $detail", done, total)
                            }
                        },
                        shouldStop = {
                            stopRequested.get() || !running.get() || SystemClock.elapsedRealtime() >= deadlineElapsed
                        },
                        appFilesDir = filesDir
                    )
                } catch (e: Exception) {
                    val reason = when {
                        SystemClock.elapsedRealtime() >= deadlineElapsed -> "deadline-exceeded"
                        stopRequested.get() || !running.get() -> "cancelled"
                        else -> (e.message ?: "Workload execution failed").take(400)
                    }
                    JSONObject().put("workerError", reason).put("message", (e.message ?: "").take(400))
                } finally {
                    executionFinished.set(true)
                    statusPoller.interrupt()
                    try { statusPoller.join(250L) } catch (_: InterruptedException) {}
                }
                val result = JSONObject()
                    .put("workerId", workerId)
                    .put("result", JSONObject().put("jobId", id).put("claimToken", claimToken).put("output", output)
                        .put("metadata", JSONObject().put("execution", "android-foreground-service").put("device", Build.MODEL)))
                request("$base/api/worker/result", "POST", token, result)
                updateNotification("Completed $workload · waiting for jobs")
            } catch (e: Exception) {
                registered = false
                updateNotification("HQ connection interrupted · retrying")
                try { Thread.sleep(4000) } catch (_: InterruptedException) { break }
            }
        }
    }

    private fun request(url: String, method: String, token: String, body: JSONObject? = null): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 7000
            connection.readTimeout = 7000
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
            if (code !in 200..299) throw IllegalStateException("HQ returned HTTP $code: $text")
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        } finally { connection.disconnect() }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(channelId, "Genesis compute worker", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun notification(message: String, progress: Int? = null, max: Int? = null): Notification {
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, channelId) else Notification.Builder(this)
        builder.setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Genesis compute worker")
            .setContentText(message)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (progress != null && max != null && max > 0) builder.setProgress(max, progress.coerceIn(0, max), false)
        else builder.setProgress(0, 0, false)
        return builder.build()
    }

    private fun updateNotification(message: String, progress: Int? = null, max: Int? = null) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(4102, notification(message, progress, max))
    }

    override fun onDestroy() {
        running.set(false)
        thread?.interrupt()
        try { wakeLock?.release() } catch (_: Exception) {}
        getSharedPreferences("genesis-mobile", MODE_PRIVATE).edit().putBoolean("worker_running", false).apply()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
