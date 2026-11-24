package app.genesis.mobile

import android.Manifest
import android.app.Service
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.RemoteException
import java.security.MessageDigest
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** OS-isolated, import-free WebAssembly and legacy integer-bytecode executor. */
class IsolatedGuestComputeService : Service() {
    private lateinit var thread: HandlerThread
    private lateinit var messenger: Messenger
    private lateinit var executionExecutor: ExecutorService
    private val cancellationFlags = ConcurrentHashMap<String, AtomicBoolean>()
    private val completedRequestIds = object : LinkedHashMap<String, Unit>(MAX_COMPLETED_REQUEST_IDS + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?): Boolean =
            size > MAX_COMPLETED_REQUEST_IDS
    }

    override fun onCreate() {
        super.onCreate()
        executionExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "Genesis-Isolated-WASM").apply { priority = Thread.NORM_PRIORITY }
        }
        thread = HandlerThread("Genesis-Isolated-Guest").apply { start() }
        messenger = Messenger(Handler(thread.looper) { message ->
            when (message.what) {
                MSG_CANCEL -> handleCancel(message)
                MSG_EXECUTE_WASM -> dispatchWasm(message)
                MSG_TEST_CRASH -> {
                    if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
                        Process.killProcess(Process.myPid())
                    }
                }
                else -> handleLegacyRequest(message)
            }
            true
        })
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    /** Keep the Messenger thread responsive so cancellation can arrive during WASM execution. */
    private fun dispatchWasm(message: Message) {
        val bundle = message.data ?: Bundle()
        val reply = message.replyTo ?: run { GuestWasmModuleTransfer.close(bundle); return }
        val requestId = bundle.getString(KEY_REQUEST_ID).orEmpty()
        if (!requestId.matches(REQUEST_ID_PATTERN)) {
            GuestWasmModuleTransfer.close(bundle)
            sendError(reply, requestId, "Invalid request ID")
            return
        }
        if (cancellationFlags.size >= MAX_IN_FLIGHT_REQUESTS) {
            GuestWasmModuleTransfer.close(bundle)
            sendError(reply, requestId, "An isolated guest execution is already active")
            return
        }
        if (isCompleted(requestId)) {
            GuestWasmModuleTransfer.close(bundle)
            sendError(reply, requestId, "Duplicate or stale request ID")
            return
        }
        val cancellation = AtomicBoolean(false)
        if (cancellationFlags.putIfAbsent(requestId, cancellation) != null) {
            GuestWasmModuleTransfer.close(bundle)
            sendError(reply, requestId, "Duplicate in-flight request ID")
            return
        }

        try {
            executionExecutor.execute {
                try {
                    val program = GuestWasmModuleTransfer.read(bundle)
                    val expectedDigest = bundle.getString(KEY_MODULE_SHA256).orEmpty()
                    require(expectedDigest.matches(Regex("[a-f0-9]{64}"))) { "Invalid WASM module digest" }
                    val actualDigest = sha256Hex(program)
                    require(actualDigest == expectedDigest) { "WASM module digest mismatch" }
                    val exportName = bundle.getString(KEY_EXPORT_NAME) ?: error("Missing WASM export name")
                    val args = bundle.getLongArray(KEY_ARGUMENTS) ?: longArrayOf()
                    val maxSteps = bundle.getInt(KEY_MAX_STEPS, GuestWasmVm.MAX_STEPS)
                    val maxMemoryPages = bundle.getInt(KEY_MAX_MEMORY_PAGES, GuestWasmVm.MAX_MEMORY_PAGES)
                    val timeoutMs = bundle.getLong(KEY_TIMEOUT_MS, GuestWasmVm.MAX_EXECUTION_MS)
                    val value = GuestWasmVm.execute(
                        program, exportName, args, maxSteps, maxMemoryPages, timeoutMs
                    ) { cancellation.get() }
                    sendSuccess(reply, requestId, value, actualDigest)
                } catch (error: Exception) {
                    sendError(reply, requestId, error.message ?: "Guest execution failed")
                } finally {
                    cancellationFlags.remove(requestId, cancellation)
                    rememberCompleted(requestId)
                }
            }
        } catch (error: Exception) {
            cancellationFlags.remove(requestId, cancellation)
            GuestWasmModuleTransfer.close(bundle)
            sendError(reply, requestId, "Guest execution could not be scheduled")
        }
    }

    private fun handleCancel(message: Message) {
        val requestId = message.data?.getString(KEY_REQUEST_ID).orEmpty()
        if (!requestId.matches(REQUEST_ID_PATTERN)) return
        cancellationFlags[requestId]?.set(true)
    }

    private fun handleLegacyRequest(message: Message) {
        val reply = message.replyTo ?: return
        val requestId = message.data?.getString(KEY_REQUEST_ID).orEmpty()
        try {
            require(requestId.matches(REQUEST_ID_PATTERN)) { "Invalid request ID" }
            val bundle = message.data ?: error("Missing request bundle")
            val program = bundle.getByteArray(KEY_PROGRAM) ?: error("Missing bytecode")
            val maxSteps = bundle.getInt(KEY_MAX_STEPS, DEFAULT_MAX_STEPS)
            require(maxSteps in 1..GuestIntegerVm.MAX_STEPS) { "Step limit outside allowed range" }
            val value = GuestIntegerVm.execute(program, maxSteps)
            sendSuccess(reply, requestId, value)
        } catch (error: Exception) {
            sendError(reply, requestId, error.message ?: "Guest execution failed")
        }
    }

    private fun sendSuccess(reply: Messenger, requestId: String, value: Long, moduleSha256: String? = null) {
        val response = responseBundle(requestId).apply {
            putBoolean(KEY_OK, true)
            putLong(KEY_RESULT, value)
            if (moduleSha256 != null) putString(KEY_MODULE_SHA256, moduleSha256)
        }
        sendReply(reply, response)
    }

    private fun sendError(reply: Messenger, requestId: String, error: String) {
        val response = responseBundle(requestId).apply {
            putBoolean(KEY_OK, false)
            putString(KEY_ERROR, error.take(512))
        }
        sendReply(reply, response)
    }

    private fun responseBundle(requestId: String) = Bundle().apply {
        putString(KEY_REQUEST_ID, requestId)
        putInt(KEY_ISOLATED_UID, Process.myUid())
        putInt(KEY_ISOLATED_PID, Process.myPid())
        putInt(KEY_OWNER_UID, applicationInfo.uid)
        putBoolean(KEY_NETWORK_PERMISSION,
            checkSelfPermission(Manifest.permission.INTERNET) == PackageManager.PERMISSION_GRANTED)
        putBoolean(KEY_PRIVATE_DATA_ACCESS, java.io.File(applicationInfo.dataDir).canRead())
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun sendReply(reply: Messenger, response: Bundle) {
        try {
            reply.send(Message.obtain(null, MSG_RESULT).apply { data = response })
        } catch (_: RemoteException) { }
    }

    @Synchronized
    private fun isCompleted(requestId: String): Boolean = completedRequestIds.containsKey(requestId)

    @Synchronized
    private fun rememberCompleted(requestId: String) {
        completedRequestIds[requestId] = Unit
    }

    override fun onDestroy() {
        cancellationFlags.values.forEach { it.set(true) }
        executionExecutor.shutdownNow()
        thread.quitSafely()
        super.onDestroy()
    }

    companion object {
        const val MSG_EXECUTE = 1
        const val MSG_RESULT = 2
        const val MSG_EXECUTE_WASM = 3
        const val MSG_CANCEL = 4
        const val MSG_TEST_CRASH = 5
        const val KEY_EXPORT_NAME = "export_name"
        const val KEY_ARGUMENTS = "arguments"
        const val KEY_REQUEST_ID = "request_id"
        const val KEY_PROGRAM = "program"
        const val KEY_MODULE_SHA256 = "module_sha256"
        const val KEY_MAX_STEPS = "max_steps"
        const val KEY_MAX_MEMORY_PAGES = "max_memory_pages"
        const val KEY_TIMEOUT_MS = "timeout_ms"
        const val KEY_OK = "ok"
        const val KEY_RESULT = "result"
        const val KEY_ERROR = "error"
        const val KEY_ISOLATED_UID = "isolated_uid"
        const val KEY_ISOLATED_PID = "isolated_pid"
        const val KEY_OWNER_UID = "owner_uid"
        const val KEY_NETWORK_PERMISSION = "network_permission"
        const val KEY_PRIVATE_DATA_ACCESS = "private_data_access"
        const val DEFAULT_MAX_STEPS = 1000
        private const val MAX_IN_FLIGHT_REQUESTS = 1
        private const val MAX_COMPLETED_REQUEST_IDS = 256
        private val REQUEST_ID_PATTERN = Regex("[A-Za-z0-9._-]{8,80}")
    }
}
