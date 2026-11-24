package app.genesis.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.WindowManager
import android.graphics.PixelFormat
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Owner-started MediaProjection publisher and remote-control command relay.
 *
 * Frames are bounded JPEGs held in memory and sent to the authenticated HQ
 * remote-screen endpoint. Remote input is separately gated by source-owner
 * consent, viewer permissions, and the source device's Accessibility service.
 * Captured frames are never persisted by this service.
 */
class ScreenCaptureService : Service() {
    companion object {
        const val ACTION_STOP = "app.genesis.mobile.action.STOP_SCREEN_SHARE"
        const val EXTRA_RESULT_CODE = "projection_result_code"
        const val EXTRA_RESULT_DATA = "projection_result_data"
        const val EXTRA_GATEWAY_BASE = "gateway_base"
        const val EXTRA_AUTH_TOKEN = "auth_token"
        const val EXTRA_ALLOW_CONTROL = "allow_remote_control"
        private const val CHANNEL_ID = "genesis_screen_share"
        private const val NOTIFICATION_ID = 4203
        private const val MAX_FRAME_BYTES = 1024 * 1024
        private const val MAX_DIMENSION = 960
        // Publish at roughly 3 FPS; the viewer polls independently for the newest available frame.
        private const val FRAME_INTERVAL_MS = 350L
    }

    private data class EncodedFrame(val bytes: ByteArray, val width: Int, val height: Int)

    private val stopping = AtomicBoolean(false)
    private val uploadInFlight = AtomicBoolean(false)
    private val sequence = AtomicLong(0)
    private val lastFrameAt = AtomicLong(0)
    private val uploadExecutor = Executors.newSingleThreadExecutor()
    // Frame traffic must never queue ahead of command execution receipts or share lifecycle requests.
    private val frameUploadExecutor = Executors.newSingleThreadExecutor()
    private val controlExecutor: ScheduledExecutorService = Executors.newScheduledThreadPool(2)
    private val controlPollInFlight = AtomicBoolean(false)
    private val streaming = AtomicBoolean(true)
    @Volatile private var allowControl = false
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var imageThread: HandlerThread? = null
    private var imageHandler: Handler? = null
    @Volatile private var shareId: String? = null
    @Volatile private var shareToken: String? = null
    @Volatile private var gatewayBase = ""
    @Volatile private var authToken = ""
    @Volatile private var shareReady = false

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            stopSharing("Android screen-capture permission was revoked", stopProjection = false)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSharing("Screen sharing stopped by the device owner")
            return START_NOT_STICKY
        }
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        @Suppress("DEPRECATION")
        val resultData = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        gatewayBase = intent?.getStringExtra(EXTRA_GATEWAY_BASE).orEmpty().trimEnd('/')
        authToken = intent?.getStringExtra(EXTRA_AUTH_TOKEN).orEmpty()
        allowControl = intent?.getBooleanExtra(EXTRA_ALLOW_CONTROL, false) ?: false
        if (resultCode == 0 || resultData == null || gatewayBase.isBlank() || authToken.isBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }

        startAsForeground()
        try {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(resultCode, resultData)
                ?: throw IllegalStateException("Android did not create a screen-capture session")
            projection?.registerCallback(projectionCallback, Handler(mainLooper))
            startCapturePipeline()
            uploadExecutor.execute {
                try {
                    val started = requestJson(
                        "/api/remote/share/start",
                        "POST",
                        JSONObject().put("ownerConsent", true).put("allowControl", allowControl)
                            .put("controlConsent", allowControl)
                    )
                    val newShareId = started.getString("shareId")
                    val newShareToken = started.getString("shareToken")
                    shareId = newShareId
                    shareToken = newShareToken
                    if (stopping.get()) {
                        try {
                            requestJson("/api/remote/share/stop", "POST", JSONObject().put("shareId", newShareId), newShareToken)
                        } catch (_: Exception) {}
                    } else {
                        shareReady = true
                        streaming.set(true)
                        startControlLoop()
                    }
                } catch (error: Exception) {
                    stopSharing("Could not start the authenticated screen share: ${error.message}")
                }
            }
        } catch (error: Exception) {
            stopSharing("Could not start screen capture: ${error.message}")
        }
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startCapturePipeline() {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
        val width = metrics.widthPixels.coerceAtLeast(1)
        val height = metrics.heightPixels.coerceAtLeast(1)
        val density = metrics.densityDpi.coerceAtLeast(1)
        imageThread = HandlerThread("Genesis-ScreenCapture").apply { start() }
        imageHandler = Handler(imageThread!!.looper)
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader!!.setOnImageAvailableListener({ reader -> captureLatest(reader) }, imageHandler)
        virtualDisplay = projection!!.createVirtualDisplay(
            "GenesisRemoteScreen", width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, imageHandler
        ) ?: throw IllegalStateException("Android could not create the screen-capture display")
    }

    private fun captureLatest(reader: ImageReader) {
        val image = try { reader.acquireLatestImage() } catch (_: Exception) { null } ?: return
        try {
            if (stopping.get() || !shareReady || !streaming.get() || uploadInFlight.get()) return
            val now = SystemClock.elapsedRealtime()
            val previous = lastFrameAt.get()
            if (now - previous < FRAME_INTERVAL_MS || !lastFrameAt.compareAndSet(previous, now)) return
            val frame = imageToJpeg(image) ?: return
            if (frame.bytes.isEmpty() || frame.bytes.size > MAX_FRAME_BYTES) return
            if (!uploadInFlight.compareAndSet(false, true)) return
            val currentSequence = sequence.incrementAndGet()
            frameUploadExecutor.execute {
                try {
                    val id = shareId ?: return@execute
                    val token = shareToken ?: return@execute
                    val body = JSONObject()
                        .put("shareId", id)
                        .put("sequence", currentSequence)
                        .put("frameBase64", Base64.getEncoder().encodeToString(frame.bytes))
                        .put("width", frame.width)
                        .put("height", frame.height)
                    requestJson("/api/remote/frame", "POST", body, token)
                } catch (_: Exception) {
                    // The next fresh frame retries naturally; stale frames are never queued.
                } finally {
                    uploadInFlight.set(false)
                }
            }
        } finally {
            image.close()
        }
    }

    private fun imageToJpeg(image: Image): EncodedFrame? {
        val plane = image.planes.firstOrNull() ?: return null
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        if (pixelStride != 4 || rowStride < image.width * pixelStride) return null
        val paddedWidth = image.width + (rowStride - image.width * pixelStride) / pixelStride
        val padded = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
        try {
            buffer.rewind()
            padded.copyPixelsFromBuffer(buffer)
            val cropped = if (paddedWidth == image.width) padded else
                Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
            val scale = minOf(1f, MAX_DIMENSION.toFloat() / maxOf(image.width, image.height))
            val outputWidth = (image.width * scale).toInt().coerceAtLeast(1)
            val outputHeight = (image.height * scale).toInt().coerceAtLeast(1)
            val scaled = if (scale < 1f) Bitmap.createScaledBitmap(cropped, outputWidth, outputHeight, true) else cropped
            return try {
                ByteArrayOutputStream().use { output ->
                    scaled.compress(Bitmap.CompressFormat.JPEG, 55, output)
                    EncodedFrame(output.toByteArray(), outputWidth, outputHeight)
                }
            } finally {
                if (scaled !== cropped && scaled !== padded) scaled.recycle()
                if (cropped !== padded) cropped.recycle()
            }
        } finally {
            padded.recycle()
        }
    }

    private fun requestJson(path: String, method: String, body: JSONObject? = null, shareCredential: String? = null): JSONObject {
        val connection = URL(gatewayBase + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 7000
            connection.readTimeout = 10000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Authorization", "Bearer " + authToken)
            if (shareCredential != null) connection.setRequestProperty("X-Genesis-Share-Token", shareCredential)
            if (body != null) {
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val raw = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IllegalStateException("HQ returned HTTP $code: $raw")
            return if (raw.isBlank()) JSONObject() else JSONObject(raw)
        } finally {
            connection.disconnect()
        }
    }

    private fun startControlLoop() {
        controlExecutor.scheduleWithFixedDelay({ if (!stopping.get() && shareReady) pollRemoteControls() }, 0, 500, TimeUnit.MILLISECONDS)
        controlExecutor.scheduleWithFixedDelay({ if (!stopping.get() && shareReady) sendShareHeartbeat() }, 10, 10, TimeUnit.SECONDS)
    }

    private fun pollRemoteControls() {
        if (!controlPollInFlight.compareAndSet(false, true)) return
        try {
            val id = shareId ?: return
            val token = shareToken ?: return
            val response = requestJson("/api/remote/control/next?shareId=$id", "GET", null, token)
            val commands = response.optJSONArray("commands") ?: return
            for (index in 0 until commands.length()) {
                val item = commands.optJSONObject(index) ?: continue
                val commandId = item.optString("commandId")
                if (commandId.isBlank()) continue
                val command = item.optJSONObject("command")
                if (command == null) {
                    reportControlResult(id, token, commandId, false, "The source received an invalid command payload.")
                    continue
                }
                when (command.optString("type")) {
                    "stream-stop" -> {
                        val ok = setStreaming(false)
                        reportControlResult(id, token, commandId, ok,
                            if (ok) "Screen transmission paused; the capture session remains armed."
                            else "The source phone could not pause screen transmission.")
                    }
                    "stream-start" -> {
                        val ok = setStreaming(true)
                        reportControlResult(id, token, commandId, ok,
                            if (ok) "Screen transmission resumed."
                            else "The source phone could not resume screen transmission.")
                    }
                    else -> if (!allowControl) {
                        reportControlResult(id, token, commandId, false,
                            "Remote control was not enabled by the source owner for this share.")
                    } else {
                        GenesisAccessibilityService.dispatchRemoteCommand(command) { success, message ->
                            reportControlResult(id, token, commandId, success, message)
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // The next bounded poll and heartbeat retry after transient network errors.
        } finally { controlPollInFlight.set(false) }
    }

    private fun reportControlResult(
        id: String, token: String, commandId: String, success: Boolean, message: String
    ) {
        try {
            uploadExecutor.execute {
                try {
                    requestJson("/api/remote/control/result", "POST",
                        JSONObject().put("shareId", id).put("commandId", commandId)
                            .put("status", if (success) "completed" else "failed")
                            .put("message", message.take(240)), token)
                    getSharedPreferences("genesis-mobile", MODE_PRIVATE).edit()
                        .putString("last_remote_control_status", message.take(240)).apply()
                } catch (_: Exception) {
                    // The viewer will report a missing execution confirmation if this cannot be delivered.
                }
            }
        } catch (_: Exception) {
            // The service may be shutting down while an Accessibility callback completes.
        }
    }

    private fun sendShareHeartbeat() {
        val id = shareId ?: return
        val token = shareToken ?: return
        try { requestJson("/api/remote/share/heartbeat", "POST", JSONObject().put("shareId", id), token) }
        catch (_: Exception) { /* Server expires abandoned shares if the source cannot reconnect. */ }
    }

    private fun setStreaming(enabled: Boolean): Boolean {
        if (stopping.get()) return false
        if (streaming.get() == enabled) return true
        val id = shareId ?: return false
        val token = shareToken ?: return false
        return try {
            requestJson("/api/remote/share/stream", "POST", JSONObject().put("shareId", id).put("streaming", enabled), token)
            streaming.set(enabled)
            if (enabled) {
                imageReader?.setOnImageAvailableListener({ reader -> captureLatest(reader) }, imageHandler)
                virtualDisplay?.setSurface(imageReader?.surface)
                lastFrameAt.set(0)
            } else {
                virtualDisplay?.setSurface(null)
                imageReader?.setOnImageAvailableListener(null, null)
            }
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
            getSharedPreferences("genesis-mobile", MODE_PRIVATE).edit()
                .putString("last_remote_control_status", if (enabled) "Remote screen transmission resumed" else "Remote screen transmission paused; capture session remains armed")
                .apply()
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun stopSharing(reason: String, stopProjection: Boolean = true) {
        if (!stopping.compareAndSet(false, true)) return
        shareReady = false
        streaming.set(false)
        controlExecutor.shutdownNow()
        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null
        try { imageReader?.setOnImageAvailableListener(null, null); imageReader?.close() } catch (_: Exception) {}
        imageReader = null
        try { imageThread?.quitSafely() } catch (_: Exception) {}
        imageThread = null
        if (stopProjection) {
            try { projection?.stop() } catch (_: Exception) {}
        }
        projection = null
        val id = shareId
        val token = shareToken
        if (id != null && token != null) {
            uploadExecutor.execute {
                try {
                    requestJson("/api/remote/share/stop", "POST", JSONObject().put("shareId", id), token)
                } catch (_: Exception) {
                    // Server-side share TTL expires abandoned shares automatically.
                } finally {
                    finishService(reason)
                }
            }
        } else {
            finishService(reason)
        }
    }

    private fun finishService(reason: String) {
        getSharedPreferences("genesis-mobile", MODE_PRIVATE).edit()
            .putString("last_screen_share_status", reason).apply()
        try { uploadExecutor.shutdown() } catch (_: Exception) {}
        try { frameUploadExecutor.shutdown() } catch (_: Exception) {}
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(
                CHANNEL_ID, "Genesis screen sharing", NotificationManager.IMPORTANCE_LOW
            ))
        }
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, ScreenCaptureService::class.java).setAction(ACTION_STOP)
        val stopPending = PendingIntent.getService(
            this, 4203, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID)
            else Notification.Builder(this)
        return builder.setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Genesis screen sharing")
            .setContentText(if (streaming.get()) "Screen is being shared with an approved Genesis device" else "Sharing is armed; screen transmission is paused")
            .setOngoing(true)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, android.R.drawable.ic_media_pause), "Stop sharing", stopPending).build())
            .build()
    }

    override fun onDestroy() {
        if (!stopping.get()) stopSharing("Screen sharing service closed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
