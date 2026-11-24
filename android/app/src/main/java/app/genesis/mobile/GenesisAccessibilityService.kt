package app.genesis.mobile

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.graphics.Path
import android.util.DisplayMetrics
import android.view.WindowManager
import android.os.Handler
import android.os.Looper
import android.accessibilityservice.GestureDescription
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Opt-in observer plus a separate, owner-consented remote gesture adapter.
 * Observation remains package-scoped; remote gestures require an active share
 * with source-owner consent and an authorized viewer session.
 */
class GenesisAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile private var activeService: GenesisAccessibilityService? = null
        fun dispatchRemoteCommand(command: JSONObject, completion: (Boolean, String) -> Unit): Boolean {
            val service = activeService
            if (service == null) {
                completion(false, "Genesis Accessibility is not connected on the source phone.")
                return false
            }
            val posted = Handler(Looper.getMainLooper()).post {
                service.performRemoteCommand(command, completion)
            }
            if (!posted) completion(false, "Android could not schedule the remote input.")
            return posted
        }
    }

    private val io = Executors.newSingleThreadExecutor()
    private var observedPackage: String? = null
    private var lastState: JSONObject? = null
    private var stepIndex = 0
    private var pendingAction = false
    private var lastSession: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        activeService = this
        resetLocalState()
    }

    private fun performRemoteCommand(command: JSONObject, completion: (Boolean, String) -> Unit) {
        when (command.optString("type")) {
            "back" -> {
                val ok = performGlobalAction(GLOBAL_ACTION_BACK)
                completion(ok, if (ok) "Android performed Back." else "Android rejected the Back action.")
            }
            "home" -> {
                val ok = performGlobalAction(GLOBAL_ACTION_HOME)
                completion(ok, if (ok) "Android performed Home." else "Android rejected the Home action.")
            }
            "tap" -> dispatchNormalizedGesture(
                command.optDouble("x", -1.0), command.optDouble("y", -1.0),
                command.optDouble("x", -1.0), command.optDouble("y", -1.0), 80L, completion
            )
            "swipe" -> dispatchNormalizedGesture(
                command.optDouble("x1", -1.0), command.optDouble("y1", -1.0),
                command.optDouble("x2", -1.0), command.optDouble("y2", -1.0),
                command.optLong("durationMs", 300L).coerceIn(50L, 1500L), completion
            )
            else -> completion(false, "Unsupported remote input command.")
        }
    }

    private fun dispatchNormalizedGesture(
        x1: Double, y1: Double, x2: Double, y2: Double, durationMs: Long,
        completion: (Boolean, String) -> Unit
    ) {
        if (!x1.isFinite() || !y1.isFinite() || !x2.isFinite() || !y2.isFinite() ||
            x1 !in 0.0..1.0 || y1 !in 0.0..1.0 || x2 !in 0.0..1.0 || y2 !in 0.0..1.0) {
            completion(false, "Gesture coordinates were outside the permitted screen bounds.")
            return
        }
        try {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
            val width = metrics.widthPixels.coerceAtLeast(1)
            val height = metrics.heightPixels.coerceAtLeast(1)
            val startX = (x1 * (width - 1)).toFloat()
            val startY = (y1 * (height - 1)).toFloat()
            var endX = (x2 * (width - 1)).toFloat()
            var endY = (y2 * (height - 1)).toFloat()
            // Android rejects zero-length gesture paths. A tap is represented by
            // a one-pixel micro-stroke so it remains a tap without a zero-length path.
            if (kotlin.math.abs(endX - startX) + kotlin.math.abs(endY - startY) < 0.5f) {
                if (width > 1) endX = if (startX < width - 1f) startX + 1f else startX - 1f
                else if (height > 1) endY = if (startY < height - 1f) startY + 1f else startY - 1f
            }
            val path = Path().apply {
                moveTo(startX, startY)
                lineTo(endX, endY)
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs)).build()
            val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    completion(true, "Android completed the gesture.")
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    completion(false, "Android cancelled the gesture.")
                }
            }, Handler(mainLooper))
            if (!accepted) completion(false, "Android rejected the gesture before execution.")
        } catch (error: Exception) {
            completion(false, "Could not dispatch gesture: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val sessionId = ObservationSessionState.sessionId ?: return
        val endpoint = ObservationSessionState.endpoint ?: return
        val token = ObservationSessionState.token ?: return
        val targetPackage = ObservationSessionState.packageName ?: return
        val eventPackage = event.packageName?.toString() ?: return
        if (eventPackage != targetPackage) return
        if (event.eventType !in setOf(
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                AccessibilityEvent.TYPE_VIEW_CLICKED,
                AccessibilityEvent.TYPE_VIEW_SCROLLED,
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
            )) return

        if (lastSession != sessionId) {
            resetLocalState()
            val scopedInfo = serviceInfo
            scopedInfo.packageNames = arrayOf(targetPackage)
            serviceInfo = scopedInfo
            lastSession = sessionId
            observedPackage = targetPackage
        }
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != targetPackage) return
        val snapshot = JSONObject()
            .put("packageName", targetPackage)
            .put("window", captureNode(root, 0, intArrayOf(0)))
        if (lastState == null) {
            enqueue(endpoint, token, sessionId, targetPackage, "observation",
                JSONObject().put("stepIndex", stepIndex).put("observation", JSONObject().put("state", snapshot)))
            lastState = snapshot
            return
        }

        val isAction = event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED ||
            event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED ||
            event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
        if (isAction && !pendingAction) {
            val action = describeAction(event)
            enqueue(endpoint, token, sessionId, targetPackage, "action",
                JSONObject().put("stepIndex", stepIndex).put("action", action))
            pendingAction = true
            return
        }

        val isStateUpdate = event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        if (pendingAction && isStateUpdate) {
            enqueue(endpoint, token, sessionId, targetPackage, "outcome",
                JSONObject().put("stepIndex", stepIndex).put("transition",
                    JSONObject().put("nextState", snapshot)))
            stepIndex += 1
            enqueue(endpoint, token, sessionId, targetPackage, "observation",
                JSONObject().put("stepIndex", stepIndex).put("observation", JSONObject().put("state", snapshot)))
            pendingAction = false
            lastState = snapshot
        } else if (!pendingAction && isStateUpdate && lastState?.toString() != snapshot.toString()) {
            // Refresh the current state before the next user action; no transition is invented.
            enqueue(endpoint, token, sessionId, targetPackage, "observation",
                JSONObject().put("stepIndex", stepIndex).put("observation", JSONObject().put("state", snapshot)))
            lastState = snapshot
        }
    }

    private fun describeAction(event: AccessibilityEvent): JSONObject {
        val node = event.source
        val target = JSONObject()
        if (node != null && node.packageName?.toString() == observedPackage) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            target.put("bounds", JSONObject().put("left", bounds.left).put("top", bounds.top)
                .put("right", bounds.right).put("bottom", bounds.bottom))
            target.put("className", node.className?.toString()?.take(120) ?: "")
            target.put("viewId", node.viewIdResourceName?.take(200) ?: "")
            if (!node.isPassword && !node.isEditable) {
                target.put("text", node.text?.toString()?.take(120) ?: "")
                target.put("description", node.contentDescription?.toString()?.take(120) ?: "")
            } else {
                target.put("textRedacted", true)
            }
            target.put("clickable", node.isClickable)
            target.put("scrollable", node.isScrollable)
        }
        val id = when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> "view-clicked"
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> "view-scrolled"
            else -> "view-text-changed"
        }
        return JSONObject().put("id", id).put("target", target)
    }

    private fun captureNode(node: AccessibilityNodeInfo, depth: Int, count: IntArray): JSONObject {
        count[0] += 1
        val result = JSONObject()
            .put("className", node.className?.toString()?.take(120) ?: "")
            .put("viewId", node.viewIdResourceName?.take(200) ?: "")
            .put("clickable", node.isClickable)
            .put("scrollable", node.isScrollable)
            .put("enabled", node.isEnabled)
            .put("selected", node.isSelected)
            .put("childCount", node.childCount)
        if (node.isPassword) {
            result.put("textRedacted", true)
        } else if (node.isEditable) {
            result.put("textRedacted", true)
            result.put("hasText", !node.text.isNullOrEmpty())
        } else {
            result.put("text", node.text?.toString()?.take(256) ?: "")
            result.put("description", node.contentDescription?.toString()?.take(256) ?: "")
        }
        if (depth < 10 && count[0] < 300) {
            val children = JSONArray()
            for (i in 0 until node.childCount) {
                if (count[0] >= 300) break
                val child = node.getChild(i) ?: continue
                children.put(captureNode(child, depth + 1, count))
            }
            result.put("children", children)
        }
        return result
    }

    private fun enqueue(endpoint: String, token: String, sessionId: String, packageName: String,
                        kind: String, data: JSONObject) {
        val body = JSONObject().put("sessionId", sessionId).put("packageName", packageName)
            .put("kind", kind).put("data", data).toString()
        io.execute {
            var connection: HttpURLConnection? = null
            try {
                connection = URL(endpoint + "/api/observation/event").openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("X-Genesis-Observation-Token", token)
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = connection.responseCode
                if (code !in 200..299) {
                    connection.errorStream?.close()
                    if (code == 401 || code == 403 || code == 410) ObservationSessionState.clear()
                } else connection.inputStream?.close()
            } catch (_: Exception) {
                // A transient network error does not crash AccessibilityService.
                // The bounded session expires automatically if delivery cannot resume.
            } finally { connection?.disconnect() }
        }
    }

    private fun resetLocalState() {
        observedPackage = null
        lastState = null
        stepIndex = 0
        pendingAction = false
        lastSession = null
    }

    override fun onInterrupt() { resetLocalState() }

    override fun onDestroy() {
        if (activeService === this) activeService = null
        io.shutdownNow()
        ObservationSessionState.clear()
        super.onDestroy()
    }
}
