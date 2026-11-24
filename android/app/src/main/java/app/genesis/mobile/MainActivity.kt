package app.genesis.mobile

import android.app.Activity
import android.app.AlertDialog
import android.media.projection.MediaProjectionManager
import android.content.Intent
import android.content.ComponentName
import android.content.ServiceConnection
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.Matrix
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.widget.Button
import android.widget.ImageView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.AdapterView
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.Base64
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var endpoint: EditText
    private lateinit var status: TextView
    private lateinit var role: TextView
    private lateinit var hqPanel: LinearLayout
    private lateinit var workerPanel: LinearLayout
    private val hqPages = linkedMapOf<String, LinearLayout>()
    private val workerPages = linkedMapOf<String, LinearLayout>()
    private lateinit var activeHQPage: LinearLayout
    private lateinit var activeWorkerPage: LinearLayout
    private var adminTokenValue = ""
    private lateinit var codeInput: EditText
    private lateinit var codeDisplay: TextView
    private lateinit var requestDisplay: TextView
    private lateinit var requestActions: LinearLayout
    private lateinit var devicesDisplay: TextView
    private lateinit var devicesActions: LinearLayout
    private lateinit var capacityInput: EditText
    private lateinit var capacitySummary: TextView
    private lateinit var guestWorkspaceSummary: TextView
    private lateinit var workspacePathInput: EditText
    private lateinit var workspaceTextInput: EditText
    private lateinit var workspaceWasmInput: EditText
    private lateinit var workspaceWasmPathInput: EditText
    private lateinit var workspaceFilesDisplay: TextView
    private lateinit var isolatedTestSummary: TextView
    private lateinit var systemHealthDisplay: LinearLayout
    private lateinit var thermalDisplay: LinearLayout
    private lateinit var workloadInput: EditText
    private lateinit var payloadInput: EditText
    private lateinit var jobsDisplay: TextView
    private lateinit var observationPackageInput: EditText
    private lateinit var observationSummary: TextView
    private lateinit var revisionRequestInput: EditText

    private data class RemoteViewerUi(
        val isHeadquarters: Boolean,
        val spinner: Spinner,
        val adapter: ArrayAdapter<String>,
        val shares: MutableList<JSONObject>,
        val image: ImageView,
        val summary: TextView,
        var canControl: Boolean = false
    )
    private val remotePollHandler = Handler(Looper.getMainLooper())
    // Keep viewer image traffic off the executor used for input and execution receipts.
    private val remoteFrameExecutor = Executors.newSingleThreadExecutor()
    private val remoteControlExecutor = Executors.newSingleThreadExecutor()
    private val remoteControlPollHandler = Handler(Looper.getMainLooper())
    @Volatile private var activeRemoteSessionId: String? = null
    @Volatile private var activeRemoteGatewayBase: String? = null
    @Volatile private var activeRemoteAuthToken: String? = null
    @Volatile private var remotePollInFlight = false
    private var activeRemoteViewerUi: RemoteViewerUi? = null
    private var activeRemoteFrameSequence = 0L
    private var remotePollRunnable: Runnable? = null

    private val localHQ = "https://127.0.0.1:8789"
    private val screenProjectionRequestCode = 7420
    private var pendingScreenGatewayBase: String? = null
    private var pendingScreenAuthToken: String? = null
    private var pendingScreenAllowControl = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("genesis-mobile", MODE_PRIVATE)
        val savedHq = prefs.getString("hq_url", "").orEmpty()
        if (savedHq.startsWith("http://", ignoreCase = true)) {
            val migrated = secureHqBase(savedHq)
            if (migrated.startsWith("https://", ignoreCase = true)) prefs.edit().putString("hq_url", migrated).apply()
        }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 28, 24, 24) }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundResource(R.drawable.genesis_background)
            addView(root)
        }
        root.addView(TextView(this).apply { text = "GENESIS"; textSize = 30f; typeface = Typeface.DEFAULT_BOLD })
        root.addView(TextView(this).apply { text = "Independent intelligence • distributed compute"; textSize = 14f; alpha = 0.7f })
        role = TextView(this).apply { text = "Detecting device role…"; textSize = 18f; typeface = Typeface.DEFAULT_BOLD; setPadding(0, 18, 0, 8) }
        root.addView(role)
        root.addView(button("Detect Genesis on this device") { detectDeviceRole() })
        status = TextView(this).apply { text = "Checking this device…"; textSize = 14f; setPadding(0, 12, 0, 16) }
        root.addView(status)

        hqPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        root.addView(hqPanel)
        buildTabPages(hqPanel, listOf("Home", "Workers", "Remote Screen", "Workspace", "Learning", "Compute", "Health", "Thermal"), hqPages) { activeHQPage = hqPages[it]!! }
        section(activeHQPage, "HEADQUARTERS · TRUSTED DEVICES")
        activeHQPage.addView(TextView(this).apply {
            text = "The HQ administrator token stays in memory only. Enter it when needed; it is never saved by this app."
            textSize = 13f; alpha = 0.8f
        })
        val adminTokenStatus = TextView(this).apply {
            text = "HQ administrator token is not loaded."
            textSize = 13f; setPadding(0, 8, 0, 8)
        }
        activeHQPage.addView(adminTokenStatus)
        activeHQPage.addView(button("Enter / replace HQ administrator token") {
            showAdminTokenDialog(adminTokenStatus)
        })
        activeHQPage.addView(button("Start Phone A GenB APK signing bridge") { startAndroidBuildSigningBridge() })
        activeHQPage.addView(button("Stop Phone A GenB APK signing bridge") { stopAndroidBuildSigningBridge() })
        activeHQPage.addView(button("Rotate GenB APK signing identity") { confirmAndroidBuildSigningKeyRotation() })
        activeHQPage = hqPages["Health"]!!
        section(activeHQPage, "GENESIS SYSTEM HEALTH · PHONE A AUTHORITY")
        activeHQPage.addView(TextView(this).apply {
            text = "A live, grouped view of Phone A runtime resources and the services that keep Genesis running. Optional services can be idle without indicating a problem."
            textSize = 13f; alpha = 0.8f
        })
        systemHealthDisplay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 10, 0, 12)
            addView(healthMessage("System health has not been loaded."))
        }
        activeHQPage.addView(systemHealthDisplay)
        activeHQPage.addView(button("Refresh Genesis system health") { loadSystemHealth() })
        activeHQPage.addView(button("Start local Android isolated guest bridge") { startAndroidGuestBridge() })
        activeHQPage.addView(button("Stop local Android isolated guest bridge") { stopAndroidGuestBridge() })
        activeHQPage.addView(button("Refresh isolated guest execution status") { loadGuestWorkspaceExecutionStatus() })

        activeHQPage = hqPages["Thermal"]!!
        section(activeHQPage, "THERMAL MONITORING · PHONE A + WORKERS")
        activeHQPage.addView(TextView(this).apply {
            text = "Phone A reads accessible kernel sensor zones and worker reports. Only confirmed measurement types are converted and classified; unknown zones remain raw. Device trip points are shown with their own units and comparison direction."
            textSize = 13f; alpha = 0.8f
        })
        thermalDisplay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 8, 0, 12)
            addView(thermalMessage("Temperature readings have not been collected."))
        }
        activeHQPage.addView(thermalDisplay)
        activeHQPage.addView(button("Refresh Phone A and worker temperatures") { refreshThermalTelemetry() })

        activeHQPage = hqPages["Workspace"]!!
        section(activeHQPage, "PERSISTENT GUEST WORKSPACE · PHONE A AUTHORITY")
        activeHQPage.addView(TextView(this).apply {
            text = "Genesis workspace snapshots are stored under Phone A\u2019s private control data. Phone B is never the persistent owner."
            textSize = 13f; alpha = 0.8f
        })
        guestWorkspaceSummary = TextView(this).apply {
            text = "Guest workspace status has not been loaded."; textSize = 14f; setPadding(0, 8, 0, 8); setTextIsSelectable(true)
        }
        activeHQPage.addView(guestWorkspaceSummary)
        activeHQPage.addView(button("Refresh persistent workspace status") { loadGuestWorkspaceStatus() })
        workspacePathInput = EditText(this).apply {
            hint = "Workspace-relative path, e.g. notes/hello.txt"
            setSingleLine(true)
            setText("notes/hello.txt")
        }
        activeHQPage.addView(workspacePathInput, matchWidth())
        workspaceTextInput = EditText(this).apply {
            hint = "Text file contents"
            setText("Genesis guest workspace")
            minLines = 2
            maxLines = 5
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            gravity = android.view.Gravity.TOP
        }
        activeHQPage.addView(workspaceTextInput, matchWidth())
        activeHQPage.addView(button("Save text file to Phone A workspace") { saveGuestWorkspaceText() })
        activeHQPage.addView(button("Read selected workspace file") { readGuestWorkspaceFile() })
        activeHQPage.addView(button("Delete selected workspace file") { deleteGuestWorkspaceFile() })
        activeHQPage.addView(button("List workspace files") { loadGuestWorkspaceFiles() })
        workspaceWasmPathInput = EditText(this).apply {
            hint = "WASM workspace path"
            setSingleLine(true)
            setText("programs/answer.wasm")
        }
        activeHQPage.addView(workspaceWasmPathInput, matchWidth())
        workspaceWasmInput = EditText(this).apply {
            hint = "Base64-encoded WASM module (max 1 MiB)"
            setText("AGFzbQEAAAABBQFgAAF/AwIBAAcHAQNydW4AAAoGAQQAQSoL")
            minLines = 2
            maxLines = 5
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            gravity = android.view.Gravity.TOP
        }
        activeHQPage.addView(workspaceWasmInput, matchWidth())
        activeHQPage.addView(button("Store WASM module at selected path") { storeGuestWorkspaceWasm() })
        activeHQPage.addView(button("Execute stored WASM export: run") { executeGuestWorkspaceWasm() })
        activeHQPage.addView(button("Check isolated WASM execution status") { loadGuestWorkspaceExecutionStatus() })
        workspaceFilesDisplay = TextView(this).apply {
            text = "Workspace file operations have not been used yet."
            textSize = 13f
            setTextIsSelectable(true)
            setPadding(0, 8, 0, 12)
        }
        activeHQPage.addView(workspaceFilesDisplay)
        activeHQPage.addView(button("Test OS-isolated guest compute") { testIsolatedGuestCompute() })
        activeHQPage.addView(button("Test OS-isolated WASM compute") { testIsolatedGuestWasmCompute() })
        isolatedTestSummary = TextView(this).apply {
            text = prefs.getString("last_isolated_test_result", "No isolated guest test result recorded yet.") ?: "No isolated guest test result recorded yet."
            textSize = 13f
            setPadding(0, 8, 0, 12)
            setTextIsSelectable(true)
        }
        activeHQPage.addView(isolatedTestSummary)
        activeHQPage.addView(button("Load Phone A isolated-test history") { loadIsolatedTestHistory() })
        activeHQPage = hqPages["Workers"]!!
        section(activeHQPage, "WORKER CAPACITY")
        capacitySummary = TextView(this).apply { text = "Worker capacity has not been loaded."; textSize = 14f }
        activeHQPage.addView(capacitySummary)
        capacityInput = EditText(this).apply {
            hint = "Maximum active workers (1–20)"
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        activeHQPage.addView(capacityInput, matchWidth())
        activeHQPage.addView(button("Load worker limit") { loadWorkerCapacity() })
        activeHQPage.addView(button("Save worker limit") { saveWorkerCapacity() })
        codeDisplay = TextView(this).apply { text = "No pairing code generated."; textSize = 16f; typeface = Typeface.DEFAULT_BOLD; setPadding(0, 12, 0, 8) }
        activeHQPage.addView(codeDisplay)
        activeHQPage.addView(button("Generate one-time pairing code") { generateCode() })
        activeHQPage.addView(button("Refresh pairing requests") { refreshRequests() })
        requestDisplay = TextView(this).apply { text = "No requests loaded."; textSize = 14f; setPadding(0, 8, 0, 12) }
        activeHQPage.addView(requestDisplay)
        requestActions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        activeHQPage.addView(requestActions)
        activeHQPage.addView(button("Refresh trusted devices") { refreshDevices() })
        devicesDisplay = TextView(this).apply { text = "Trusted devices have not been loaded."; textSize = 14f; setPadding(0, 8, 0, 8) }
        activeHQPage.addView(devicesDisplay)
        devicesActions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        activeHQPage.addView(devicesActions)

        activeHQPage = hqPages["Learning"]!!
        section(activeHQPage, "LIVE ANDROID OBSERVATION")
        activeHQPage.addView(TextView(this).apply {
            text = "Opt-in, read-only observation of one selected app. During this session Genesis records bounded UI structure and manual taps, scrolls, and UI changes. Password and editable-field text are redacted; no screenshots or background-wide monitoring."
            textSize = 13f; alpha = 0.8f
        })
        observationPackageInput = EditText(this).apply {
            hint = "Target app package, e.g. com.example.game"; setSingleLine(true)
            setText(prefs.getString("observation_package", "") ?: "")
        }
        activeHQPage.addView(observationPackageInput, matchWidth())
        activeHQPage.addView(button("Enable Genesis accessibility observer") { openObservationAccessibilitySettings() })
        activeHQPage.addView(button("Start scoped observation session") { startAndroidObservation() })
        activeHQPage.addView(button("Stop observation session") { stopAndroidObservation() })
        activeHQPage.addView(button("Delete captured observation data") { deleteAndroidObservationSession() })
        activeHQPage.addView(button("Build practice simulator from this session") { buildAndroidPracticeSimulator() })
        revisionRequestInput = EditText(this).apply {
            hint = "Revision JSON: baselineArtifact, trainingSessionIds, counterexampleSessionIds, evaluationSessionIds"
            setText("""{"baselineArtifact":"session-id.json","trainingSessionIds":["training-session-id"],"counterexampleSessionIds":["correction-session-id"],"evaluationSessionIds":["heldout-session-id"]}""")
            minLines = 4
            maxLines = 8
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            gravity = android.view.Gravity.TOP
        }
        activeHQPage.addView(revisionRequestInput, matchWidth())
        activeHQPage.addView(button("Revise simulator from completed observation sessions") { reviseAndroidPracticeSimulator() })
        observationSummary = TextView(this).apply {
            text = "No Android observation session is active."; textSize = 13f; setTextIsSelectable(true); setPadding(0, 8, 0, 12)
        }
        activeHQPage.addView(observationSummary)
        activeHQPage = hqPages["Compute"]!!
        section(activeHQPage, "DISTRIBUTED COMPUTE · SUBMIT JOBS")
        activeHQPage.addView(TextView(this).apply {
            text = "Create a job here on Phone A. Genesis will queue it for an approved worker such as Phone B."
            textSize = 13f; alpha = 0.8f
        })
        workloadInput = EditText(this).apply {
            hint = "Workload ID (for example probability-simulation)"
            setSingleLine(true)
            setText("probability-simulation")
        }
        activeHQPage.addView(workloadInput, matchWidth())
        payloadInput = EditText(this).apply {
            hint = "Job input JSON"
            setText("""{"trials":100000,"probability":0.3,"seed":42}""")
            minLines = 3
            maxLines = 8
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            gravity = android.view.Gravity.TOP
        }
        activeHQPage.addView(payloadInput, matchWidth())
        activeHQPage.addView(button("Queue job for a worker") { queueComputeJob() })
        activeHQPage.addView(button("Refresh workers and jobs") { refreshComputeJobs() })
        jobsDisplay = TextView(this).apply {
            text = "No worker/job data loaded yet."
            textSize = 13f
            setPadding(0, 10, 0, 16)
        }
        activeHQPage.addView(jobsDisplay)
        activeHQPage = hqPages["Remote Screen"]!!
        section(activeHQPage, "REMOTE SCREEN · PHONE A")
        activeHQPage.addView(TextView(this).apply { text = "Screen sharing and remote input are a separate permission-gated service. Starting a share requires Phone A owner approval through Android screen-capture permission. Worker access levels are managed in Workers."; textSize = 14f; setPadding(0, 8, 0, 12) })
        activeHQPage.addView(button("Share Phone A screen") { requestScreenShare() })
        activeHQPage.addView(button("Stop sharing Phone A screen") { stopScreenShare() })
        activeHQPage.addView(button("Enable remote touch control (Accessibility)") { openRemoteControlAccessibilitySettings() })
        buildRemoteViewerControls(activeHQPage, true)
        showTabPage(hqPages, "Home"); activeHQPage = hqPages["Home"]!!

        workerPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        root.addView(workerPanel)
        buildTabPages(workerPanel, listOf("Home", "Compute", "Remote Screen", "Device Sharing", "Settings"), workerPages) { activeWorkerPage = workerPages[it]!! }
        activeWorkerPage.addView(TextView(this).apply { text = "Device role and HQ connection appear above. Use the tabs to manage compute, remote screen access, and device sharing."; textSize = 14f; setPadding(0, 8, 0, 12) })
        activeWorkerPage = workerPages["Settings"]!!
        section(activeWorkerPage, "HEADQUARTERS CONNECTION")
        activeWorkerPage.addView(TextView(this).apply { text = "HQ address (LAN URL or secure remote relay URL)"; textSize = 14f })
        endpoint = EditText(this).apply { hint = "https://192.168.x.x:8789"; setSingleLine(true); setText(prefs.getString("hq_url", "") ?: "") }
        activeWorkerPage.addView(endpoint, matchWidth())
        activeWorkerPage.addView(button("Connect to headquarters") { connectRemote() })
        section(activeWorkerPage, "PAIR THIS DEVICE")
        activeWorkerPage.addView(TextView(this).apply {
            text = "Enter the one-time code from Phone A. The HQ owner must explicitly approve this request."
            textSize = 13f; alpha = 0.8f
        })
        codeInput = EditText(this).apply { hint = "One-time pairing code"; setSingleLine(true) }
        activeWorkerPage.addView(codeInput, matchWidth())
        activeWorkerPage.addView(button("Request worker access") { pairWorker() })
        activeWorkerPage = workerPages["Compute"]!!
        section(activeWorkerPage, "BACKGROUND COMPUTE")
        activeWorkerPage.addView(TextView(this).apply {
            text = "Start the foreground worker to keep processing approved jobs while you use other apps. Android shows an ongoing notification while it runs."
            textSize = 13f; alpha = 0.8f
        })
        activeWorkerPage.addView(button("Start background compute worker") { startWorker() })
        activeWorkerPage.addView(button("Stop background compute worker") { stopWorker() })
        activeWorkerPage.addView(button("Forget stored worker credential") {
            SecureTokenStore(this).clear()
            status.text = "Stored worker credential cleared. Ask the HQ owner to revoke this device too."
            role.text = "WORKER / UNPAIRED DEVICE"
        })
        activeWorkerPage = workerPages["Remote Screen"]!!
        section(activeWorkerPage, "REMOTE SCREEN · PHONE A")
        activeWorkerPage.addView(TextView(this).apply { text = "After Phone A grants Screen Viewer or Screen Controller access, refresh the list to view its live display. Touch, swipe, Back, Home, and stream standby controls require Screen Controller access and this phone's Accessibility approval."; textSize = 14f; setPadding(0, 8, 0, 12) })
        buildRemoteViewerControls(activeWorkerPage, false)
        activeWorkerPage = workerPages["Device Sharing"]!!
        section(activeWorkerPage, "SHARE THIS PHONE WITH HQ")
        activeWorkerPage.addView(TextView(this).apply { text = "To let Phone A view or control this phone, approve Android screen capture here. Remote touch control is a separate choice in the sharing confirmation and also requires Genesis Accessibility to be enabled on this phone."; textSize = 14f; setPadding(0, 8, 0, 12) })
        activeWorkerPage.addView(button("Share this phone's screen with HQ") { requestScreenShare() })
        activeWorkerPage.addView(button("Stop sharing this phone's screen") { stopScreenShare() })
        activeWorkerPage.addView(button("Enable remote touch control (Accessibility)") { openRemoteControlAccessibilitySettings() })
        showTabPage(workerPages, "Home"); activeWorkerPage = workerPages["Home"]!!
        root.addView(TextView(this).apply {
            text = "Device roles depend on local Genesis availability and owner-approved permissions, not phone model. A network connection alone does not grant trust."
            textSize = 12f; alpha = 0.7f; setPadding(0, 22, 0, 0)
        })
        setContentView(scroll)
        detectDeviceRole()
    }

    private fun requestScreenShare() {
        val isHeadquarters = hqPanel.visibility == View.VISIBLE
        val base = if (isHeadquarters) localHQ else
            endpoint.text.toString().trim().trimEnd('/').ifBlank { prefs.getString("hq_url", "") ?: "" }
        val token = if (isHeadquarters) adminTokenValue else SecureTokenStore(this).get().orEmpty()
        if (base.isBlank()) {
            status.text = "Connect this worker to Phone A before sharing its screen."
            return
        }
        if (token.isBlank()) {
            status.text = if (isHeadquarters) "Enter the HQ administrator token on the Home tab first."
                else "Pair this device with Phone A before sharing its screen."
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Share this entire screen?")
            .setMessage("The Android sharing prompt will appear next. Choose view-only or explicitly allow the paired controller to tap, swipe, use Back/Home, and pause/resume transmission. The sharing phone can always end the actual capture session itself.")
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Share view-only") { _, _ -> launchScreenCaptureConsent(base, token, false) }
            .setPositiveButton("Share + allow remote control") { _, _ -> launchScreenCaptureConsent(base, token, true) }
            .show()
    }


    private fun launchScreenCaptureConsent(base: String, token: String, allowControl: Boolean) {
        pendingScreenGatewayBase = base
        pendingScreenAuthToken = token
        pendingScreenAllowControl = allowControl
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(manager.createScreenCaptureIntent(), screenProjectionRequestCode)
    }

    private fun openRemoteControlAccessibilitySettings() {
        AlertDialog.Builder(this)
            .setTitle("Enable Genesis remote touch control?")
            .setMessage("Android Accessibility permission lets Genesis perform taps, swipes, Back, and Home only when the screen owner explicitly enabled remote control for a share. Enable Genesis in Android Accessibility settings. You can disable it again at any time.")
            .setNegativeButton("Not now", null)
            .setPositiveButton("Open settings") { _, _ -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            .show()
    }

    private fun stopScreenShare() {
        try {
            val intent = Intent(this, ScreenCaptureService::class.java).setAction(ScreenCaptureService.ACTION_STOP)
            startService(intent)
            status.text = "Stopping the screen share…"
        } catch (error: Exception) {
            status.text = "Could not stop screen sharing: ${error.message}"
        }
    }

    @Deprecated("Uses the platform activity-result callback for MediaProjection consent")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != screenProjectionRequestCode) return
        val base = pendingScreenGatewayBase
        val token = pendingScreenAuthToken
        val allowControl = pendingScreenAllowControl
        pendingScreenGatewayBase = null
        pendingScreenAuthToken = null
        pendingScreenAllowControl = false
        if (resultCode != RESULT_OK || data == null) {
            status.text = "Android screen-capture permission was not granted. No screen share started."
            return
        }
        if (base.isNullOrBlank() || token.isNullOrBlank()) {
            status.text = "Screen sharing configuration expired. Try again."
            return
        }
        val service = Intent(this, ScreenCaptureService::class.java)
            .putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
            .putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
            .putExtra(ScreenCaptureService.EXTRA_GATEWAY_BASE, base)
            .putExtra(ScreenCaptureService.EXTRA_AUTH_TOKEN, token)
            .putExtra(ScreenCaptureService.EXTRA_ALLOW_CONTROL, allowControl)
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(service) else startService(service)
            status.text = if (allowControl) "Starting the owner-approved screen share with remote control enabled…" else "Starting the owner-approved view-only screen share…"
        } catch (error: Exception) {
            status.text = "Could not start screen sharing: ${error.message}"
        }
    }

    private fun buildRemoteViewerControls(parent: LinearLayout, isHeadquarters: Boolean) {
        section(parent, if (isHeadquarters) "VIEW A WORKER SCREEN" else "VIEW PHONE A SCREEN")
        val options = mutableListOf<JSONObject>()
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, mutableListOf("Refresh available screens"))
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        val spinner = Spinner(this).apply { this.adapter = adapter }
        parent.addView(spinner, matchWidth())
        val summary = TextView(this).apply {
            text = "No remote screen session is open."
            textSize = 13f
            setPadding(0, 8, 0, 8)
        }
        parent.addView(button("Refresh available screens") {
            val ui = if (isHeadquarters) hqRemoteViewer else workerRemoteViewer
            if (ui != null) refreshRemoteShares(ui)
        })
        parent.addView(button("View selected screen") {
            val ui = if (isHeadquarters) hqRemoteViewer else workerRemoteViewer
            if (ui != null) openRemoteShare(ui)
        })
        parent.addView(button("Stop viewing") {
            val ui = if (isHeadquarters) hqRemoteViewer else workerRemoteViewer
            if (ui != null) closeRemoteViewer(ui)
        })
        val image = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(0xff171717.toInt())
        }
        parent.addView(image, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            (resources.displayMetrics.density * 520).toInt()
        ))
        parent.addView(summary, matchWidth())
        val ui = RemoteViewerUi(isHeadquarters, spinner, adapter, options, image, summary)
        parent.addView(button("Resume screen transmission on selected phone") { setRemoteStreaming(ui, true) })
        parent.addView(button("Pause screen transmission on selected phone") { setRemoteStreaming(ui, false) })
        parent.addView(button("Back on selected phone") { sendRemoteControl(ui, JSONObject().put("type", "back")) })
        parent.addView(button("Home on selected phone") { sendRemoteControl(ui, JSONObject().put("type", "home")) })
        installRemoteTouchControls(ui)
        if (isHeadquarters) hqRemoteViewer = ui else workerRemoteViewer = ui
    }

    private var hqRemoteViewer: RemoteViewerUi? = null
    private var workerRemoteViewer: RemoteViewerUi? = null

    private fun remoteCredentials(isHeadquarters: Boolean): Pair<String, String>? {
        val base = if (isHeadquarters) localHQ else
            (prefs.getString("hq_url", "") ?: "").trim().trimEnd('/')
        val token = if (isHeadquarters) {
            adminTokenValue
        } else SecureTokenStore(this).get().orEmpty()
        if (base.isBlank() || token.isBlank()) return null
        return Pair(base, token)
    }

    private fun refreshRemoteShares(ui: RemoteViewerUi) {
        val credentials = remoteCredentials(ui.isHeadquarters)
        if (credentials == null) {
            ui.summary.text = if (ui.isHeadquarters) "Enter the HQ administrator token on Home first."
                else "Connect and pair this worker with Phone A first."
            return
        }
        ui.summary.text = "Refreshing authorized screen shares…"
        executor.execute {
            try {
                val result = api(credentials.first + "/api/remote/shares", token = credentials.second)
                if (result.first !in 200..299) throw IllegalStateException(
                    result.second.optString("error", "HQ returned HTTP ${result.first}")
                )
                val array = result.second.optJSONArray("shares") ?: JSONArray()
                val found = mutableListOf<JSONObject>()
                val labels = mutableListOf<String>()
                for (i in 0 until array.length()) {
                    val share = array.optJSONObject(i) ?: continue
                    val source = share.optString("sourceDeviceId", "Unknown device")
                    if (ui.isHeadquarters && source == "phone-a") continue
                    found.add(share)
                    val label = if (source == "phone-a") "Phone A" else "Worker " + source.take(16)
                    val state = if (!share.optBoolean("streaming", true)) "standby" else if (share.optBoolean("hasFrame")) "live" else "starting"
                    labels.add("$label · $state")
                }
                runOnUiThread {
                    ui.shares.clear()
                    ui.shares.addAll(found)
                    ui.adapter.clear()
                    if (labels.isEmpty()) ui.adapter.add("No authorized screen shares")
                    else ui.adapter.addAll(labels)
                    ui.summary.text = if (labels.isEmpty()) "No screen shares are currently available to this device."
                        else "${labels.size} authorized screen share(s) found."
                }
            } catch (error: Exception) {
                runOnUiThread {
                    ui.summary.text = "Could not load screen shares: ${error.message}"
                }
            }
        }
    }

    private fun openRemoteShare(ui: RemoteViewerUi) {
        val credentials = remoteCredentials(ui.isHeadquarters)
        val shareId = ui.shares.getOrNull(ui.spinner.selectedItemPosition)?.optString("shareId").orEmpty()
        if (credentials == null || shareId.isBlank()) {
            ui.summary.text = "Refresh the list and select an available screen first."
            return
        }
        remotePollHandler.removeCallbacks(remotePollRunnable ?: Runnable {})
        remotePollRunnable = null
        val previousSession = activeRemoteSessionId
        val previousBase = activeRemoteGatewayBase
        val previousToken = activeRemoteAuthToken
        activeRemoteSessionId = null
        activeRemoteViewerUi = null
        executor.execute {
            try {
                if (!previousSession.isNullOrBlank() && !previousBase.isNullOrBlank() && !previousToken.isNullOrBlank()) {
                    try {
                        api(previousBase + "/api/remote/session/close", method = "POST",
                            body = JSONObject().put("sessionId", previousSession), token = previousToken)
                    } catch (_: Exception) {}
                }
                val opened = api(credentials.first + "/api/remote/session/open", method = "POST",
                    body = JSONObject().put("shareId", shareId), token = credentials.second)
                if (opened.first !in 200..299) throw IllegalStateException(
                    opened.second.optString("error", "HQ returned HTTP ${opened.first}")
                )
                val sessionId = opened.second.getString("sessionId")
                runOnUiThread {
                    activeRemoteSessionId = sessionId
                    activeRemoteGatewayBase = credentials.first
                    activeRemoteAuthToken = credentials.second
                    activeRemoteViewerUi = ui
                    ui.canControl = opened.second.optBoolean("canControl", false)
                    activeRemoteFrameSequence = 0L
                    ui.image.setImageDrawable(null)
                    ui.summary.text = if (ui.canControl) "Connected with owner-approved remote control. Tap/swipe the image or use Back/Home and stream controls." else "Connected in view-only mode. The sharing owner has not enabled remote control."
                    scheduleRemotePoll(ui)
                }
            } catch (error: Exception) {
                runOnUiThread { ui.summary.text = "Could not open remote screen: ${error.message}" }
            }
        }
    }

    private fun sendRemoteControl(ui: RemoteViewerUi, command: JSONObject) {
        val sessionId = activeRemoteSessionId
        val base = activeRemoteGatewayBase
        val token = activeRemoteAuthToken
        if (activeRemoteViewerUi !== ui || sessionId.isNullOrBlank() || base.isNullOrBlank() || token.isNullOrBlank()) {
            ui.summary.text = "Open a remote screen session before sending controls."
            return
        }
        if (!ui.canControl) {
            ui.summary.text = "This session is view-only. The source owner must consent to remote control and the viewer must have Screen Controller permission."
            return
        }
        val type = command.optString("type", "input")
        remoteControlExecutor.execute {
            try {
                val result = api(base + "/api/remote/control", method = "POST",
                    body = JSONObject().put("sessionId", sessionId).put("command", command), token = token)
                if (result.first !in 200..299) throw IllegalStateException(result.second.optString("error", "HTTP " + result.first))
                val commandId = result.second.optString("commandId")
                if (commandId.isBlank()) throw IllegalStateException("HQ accepted the command but did not return its tracking ID.")
                runOnUiThread {
                    if (activeRemoteSessionId == sessionId && activeRemoteViewerUi === ui) {
                        ui.summary.text = "Sent $type to the source phone; waiting for Android to confirm execution…"
                        pollRemoteControlResult(ui, sessionId, base, token, commandId, type,
                            android.os.SystemClock.elapsedRealtime())
                    }
                }
            } catch (error: Exception) {
                runOnUiThread { if (activeRemoteSessionId == sessionId) ui.summary.text = "Remote command failed: " + error.message }
            }
        }
    }

    private fun pollRemoteControlResult(
        ui: RemoteViewerUi, sessionId: String, base: String, token: String,
        commandId: String, type: String, startedAt: Long
    ) {
        if (activeRemoteSessionId != sessionId || activeRemoteViewerUi !== ui) return
        remoteControlExecutor.execute {
            try {
                val result = api("$base/api/remote/control/status?sessionId=$sessionId&commandId=$commandId", token = token)
                if (result.first !in 200..299) throw IllegalStateException(
                    result.second.optString("error", "Could not read command execution status (HTTP ${result.first}).")
                )
                val state = result.second.optString("status")
                val message = result.second.optString("message")
                runOnUiThread {
                    if (activeRemoteSessionId != sessionId || activeRemoteViewerUi !== ui) return@runOnUiThread
                    when (state) {
                        "completed" -> ui.summary.text = "Executed $type. $message"
                        "failed" -> ui.summary.text = "Android could not execute $type: $message"
                        "expired" -> ui.summary.text = "Not executed: $message"
                        "queued", "claimed" -> {
                            if (android.os.SystemClock.elapsedRealtime() - startedAt >= 12_000L) {
                                ui.summary.text = if (state == "queued")
                                    "No delivery confirmation: the source phone has not claimed $type."
                                else "The source phone received $type but has not confirmed execution yet."
                            } else {
                                remoteControlPollHandler.postDelayed({
                                    pollRemoteControlResult(ui, sessionId, base, token, commandId, type, startedAt)
                                }, 400L)
                            }
                        }
                        else -> ui.summary.text = "Unknown execution status for $type: $state"
                    }
                }
            } catch (error: Exception) {
                runOnUiThread {
                    if (activeRemoteSessionId == sessionId && activeRemoteViewerUi === ui) {
                        ui.summary.text = "Could not verify $type execution: ${error.message}"
                    }
                }
            }
        }
    }

    private fun setRemoteStreaming(ui: RemoteViewerUi, enabled: Boolean) {
        sendRemoteControl(ui, JSONObject().put("type", if (enabled) "stream-start" else "stream-stop"))
    }

    private fun remoteImagePoint(image: ImageView, x: Float, y: Float): Pair<Float, Float>? {
        val drawable = image.drawable ?: return null
        val inverse = Matrix()
        if (!image.imageMatrix.invert(inverse)) return null
        val point = floatArrayOf(x, y)
        inverse.mapPoints(point)
        val width = drawable.intrinsicWidth.toFloat().coerceAtLeast(1f)
        val height = drawable.intrinsicHeight.toFloat().coerceAtLeast(1f)
        if (point[0] < 0f || point[1] < 0f || point[0] > width || point[1] > height) return null
        return Pair(
            (point[0] / width).coerceIn(0f, 1f),
            (point[1] / height).coerceIn(0f, 1f)
        )
    }

    private fun installRemoteTouchControls(ui: RemoteViewerUi) {
        var down: Pair<Float, Float>? = null
        var downAt = 0L
        ui.image.isClickable = true
        ui.image.setOnTouchListener { _, event ->
            // The viewer page remains scrollable, but a gesture that starts on the
            // remote display belongs to that display and must not scroll its parent.
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    ui.image.parent?.requestDisallowInterceptTouchEvent(true)
                    down = if (ui.canControl && activeRemoteViewerUi === ui)
                        remoteImagePoint(ui.image, event.x, event.y) else null
                    downAt = event.eventTime
                }
                MotionEvent.ACTION_UP -> {
                    val start = down
                    val end = if (ui.canControl && activeRemoteViewerUi === ui)
                        remoteImagePoint(ui.image, event.x, event.y) else null
                    if (start != null && end != null) {
                        val dx = end.first - start.first
                        val dy = end.second - start.second
                        if (kotlin.math.sqrt(dx * dx + dy * dy) > 0.025f) {
                            sendRemoteControl(ui, JSONObject().put("type", "swipe")
                                .put("x1", start.first.toDouble()).put("y1", start.second.toDouble())
                                .put("x2", end.first.toDouble()).put("y2", end.second.toDouble())
                                .put("durationMs", (event.eventTime - downAt).coerceIn(50L, 1500L).toInt()))
                        } else {
                            sendRemoteControl(ui, JSONObject().put("type", "tap")
                                .put("x", end.first.toDouble()).put("y", end.second.toDouble()))
                        }
                    }
                    down = null
                    ui.image.parent?.requestDisallowInterceptTouchEvent(false)
                }
                MotionEvent.ACTION_CANCEL -> {
                    down = null
                    ui.image.parent?.requestDisallowInterceptTouchEvent(false)
                }
            }
            // Consume every touch sequence on the image, including view-only mode
            // and letterboxed margins, so it can never fall through to tab scrolling.
            true
        }
    }

    private fun scheduleRemotePoll(ui: RemoteViewerUi) {
        val sessionId = activeRemoteSessionId ?: return
        val task = Runnable { pollRemoteFrame(ui, sessionId) }
        remotePollRunnable = task
        remotePollHandler.postDelayed(task, 250L)
    }

    private fun pollRemoteFrame(ui: RemoteViewerUi, expectedSessionId: String) {
        if (activeRemoteSessionId != expectedSessionId || activeRemoteViewerUi !== ui) return
        if (remotePollInFlight) return
        val base = activeRemoteGatewayBase ?: return
        val token = activeRemoteAuthToken ?: return
        val cursor = activeRemoteFrameSequence
        remotePollInFlight = true
        remoteFrameExecutor.execute {
            var keepPolling = true
            try {
                val result = api("$base/api/remote/frame?sessionId=$expectedSessionId&afterSequence=$cursor", token = token)
                if (result.first !in 200..299) {
                    keepPolling = false
                    throw IllegalStateException(result.second.optString("error", "Screen session expired"))
                }
                val frame = result.second.optJSONObject("frame")
                    ?: if (result.second.has("frameBase64")) result.second else null
                if (frame == null && !result.second.optBoolean("streaming", true)) {
                    runOnUiThread {
                        if (activeRemoteSessionId == expectedSessionId && activeRemoteViewerUi === ui) {
                            ui.image.setImageDrawable(null)
                            ui.summary.text = "Screen transmission paused. The source phone's approved capture session remains armed."
                        }
                    }
                }
                if (frame != null) {
                    val frameSequence = frame.optLong("sequence", cursor)
                    val encoded = frame.optString("frameBase64")
                    if (frameSequence > cursor && encoded.isNotBlank() && encoded.length <= 1_400_000) {
                        val bytes = Base64.getDecoder().decode(encoded)
                        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        if (bitmap != null) runOnUiThread {
                            if (activeRemoteSessionId == expectedSessionId && activeRemoteViewerUi === ui) {
                                activeRemoteFrameSequence = frameSequence
                                ui.image.setImageBitmap(bitmap)
                                ui.summary.text = "Live screen · frame $frameSequence · ${frame.optInt("width")}×${frame.optInt("height")}"
                            }
                        }
                    }
                }
            } catch (error: Exception) {
                runOnUiThread {
                    if (activeRemoteSessionId == expectedSessionId) {
                        ui.summary.text = if (keepPolling) "Screen stream interrupted; retrying: ${error.message}"
                            else "Screen session ended: ${error.message}. Refresh to reconnect."
                    }
                }
            } finally {
                remotePollInFlight = false
                if (keepPolling && activeRemoteSessionId == expectedSessionId) runOnUiThread { scheduleRemotePoll(ui) }
                else if (!keepPolling && activeRemoteSessionId == expectedSessionId) {
                    activeRemoteSessionId = null
                    activeRemoteViewerUi = null
                    activeRemoteGatewayBase = null
                    activeRemoteAuthToken = null
                }
            }
        }
    }

    private fun closeRemoteViewer(ui: RemoteViewerUi) {
        val sessionId = activeRemoteSessionId
        val base = activeRemoteGatewayBase
        val token = activeRemoteAuthToken
        if (activeRemoteViewerUi !== ui) {
            ui.summary.text = "This panel is not currently viewing a screen."
            return
        }
        remotePollHandler.removeCallbacks(remotePollRunnable ?: Runnable {})
        remotePollRunnable = null
        activeRemoteSessionId = null
        activeRemoteViewerUi = null
        ui.canControl = false
        activeRemoteGatewayBase = null
        activeRemoteAuthToken = null
        activeRemoteFrameSequence = 0L
        ui.image.setImageDrawable(null)
        ui.summary.text = "Remote screen closed."
        if (!sessionId.isNullOrBlank() && !base.isNullOrBlank() && !token.isNullOrBlank()) {
            executor.execute {
                try {
                    api(base + "/api/remote/session/close", method = "POST",
                        body = JSONObject().put("sessionId", sessionId), token = token)
                } catch (_: Exception) {}
            }
        }
    }

    private fun matchWidth() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun showAdminTokenDialog(statusView: TextView) {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density + 0.5f).toInt()
        val tokenInput = EditText(this).apply {
            hint = "HQ administrator token"
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            setTextColor(android.graphics.Color.WHITE)
            setHintTextColor(android.graphics.Color.rgb(166, 180, 196))
            textSize = 16f
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                setColor(android.graphics.Color.rgb(18, 27, 40))
                setStroke(dp(1), android.graphics.Color.rgb(121, 207, 195))
                cornerRadius = dp(8).toFloat()
            }
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("HQ administrator token")
            .setMessage("This token is used in memory only and is not saved by Genesis.")
            .setView(tokenInput)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Use token") { _, _ ->
                adminTokenValue = tokenInput.text.toString().trim()
                tokenInput.text?.clear()
                statusView.text = if (adminTokenValue.isBlank())
                    "HQ administrator token is not loaded."
                else "HQ administrator token is loaded and hidden."
            }
            .create()
        dialog.setOnShowListener {
            dialog.window?.setSoftInputMode(
                android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or
                    android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            )
            tokenInput.requestFocus()
            tokenInput.post {
                val inputMethod = getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                    as android.view.inputmethod.InputMethodManager
                inputMethod.showSoftInput(tokenInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            }
        }
        dialog.show()
    }

    private fun buildTabPages(panel: LinearLayout, labels: List<String>, pages: LinkedHashMap<String, LinearLayout>, onSelect: (String) -> Unit) {
        val scroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        scroll.addView(tabs); panel.addView(scroll, matchWidth())
        val host = FrameLayout(this); panel.addView(host, matchWidth())
        for (label in labels) {
            val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE; setPadding(0, 8, 0, 8) }
            pages[label] = page
            host.addView(page, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            val tab = button(label) { showTabPage(pages, label); onSelect(label) }
            tabs.addView(tab, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = 8 })
        }
        showTabPage(pages, labels.first()); onSelect(labels.first())
    }

    private fun showTabPage(pages: Map<String, LinearLayout>, selected: String) {
        pages.forEach { (name, page) -> page.visibility = if (name == selected) View.VISIBLE else View.GONE }
    }
    private fun section(parent: LinearLayout, title: String) {
        parent.addView(TextView(this).apply { text = title; textSize = 17f; typeface = Typeface.DEFAULT_BOLD; setPadding(0, 18, 0, 8) })
    }
    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; setOnClickListener { action() }
    }
    private fun show(message: String) { runOnUiThread { status.text = message } }

    // Retry only idempotent requests. Pair-request POST must never be replayed.
    private fun apiWithRetry(
        url: String,
        token: String? = null,
        attempts: Int = 8
    ): Pair<Int, JSONObject> {
        var lastError: Exception? = null
        for (attempt in 0 until attempts) {
            try {
                return api(url, token = token)
            } catch (e: Exception) {
                lastError = e
                if (attempt + 1 < attempts) Thread.sleep(1500L * (attempt + 1))
            }
        }
        throw lastError ?: IllegalStateException("HQ connection failed after retries.")
    }

    private fun api(url: String, method: String = "GET", body: JSONObject? = null, token: String? = null, readTimeoutMs: Int = 5000): Pair<Int, JSONObject> {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method; c.connectTimeout = 5000; c.readTimeout = readTimeoutMs
            c.setRequestProperty("Accept", "application/json")
            if (!token.isNullOrBlank()) c.setRequestProperty("Authorization", "Bearer " + token)
            if (body != null) {
                c.setRequestProperty("Content-Type", "application/json")
                c.doOutput = true
                c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val raw = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            return Pair(code, if (raw.isBlank()) JSONObject() else JSONObject(raw))
        } finally { c.disconnect() }
    }

    private fun detectDeviceRole() {
        val savedEndpoint = endpoint.text.toString().trim().trimEnd('/').ifBlank {
            prefs.getString("hq_url", "") ?: ""
        }
        role.text = "CHECKING DEVICE ROLE…"
        executor.execute {
            try {
                val local = api(localHQ + "/api/health")
                if (local.first in 200..299 && local.second.optString("service") == "genesis-control") {
                    runOnUiThread {
                        role.text = "HEADQUARTERS"
                        status.text = "Genesis HQ is running on this device. Owner administration is available here."
                        hqPanel.visibility = View.VISIBLE
                        workerPanel.visibility = View.GONE
                    }
                    return@execute
                }
            } catch (_: Exception) { }
            val workerToken = SecureTokenStore(this).get()
            if (workerToken == null) {
                val reachable = if (savedEndpoint.isNotBlank()) {
                    try {
                        val health = api(savedEndpoint + "/api/health")
                        health.first in 200..299 && health.second.optString("service") == "genesis-control"
                    } catch (_: Exception) { false }
                } else false
                runOnUiThread {
                    role.text = "UNPAIRED DEVICE"
                    status.text = if (reachable)
                        "Headquarters is reachable, but this device has no active worker credential. Request pairing to become a trusted worker."
                    else
                        "No active worker credential is stored. Connect to HQ and request pairing; HQ reachability has not been verified."
                    hqPanel.visibility = View.GONE
                    workerPanel.visibility = View.VISIBLE
                }
                return@execute
            }
            if (savedEndpoint.isBlank()) {
                runOnUiThread {
                    role.text = "WORKER STATUS UNKNOWN"
                    status.text = "A worker credential is stored, but HQ must be reachable to verify whether it is still trusted."
                    hqPanel.visibility = View.GONE
                    workerPanel.visibility = View.VISIBLE
                }
                return@execute
            }
            try {
                val health = api(savedEndpoint + "/api/health")
                if (health.first !in 200..299 || health.second.optString("service") != "genesis-control") error("HQ unavailable")
                val identity = api(savedEndpoint + "/api/worker/me", token = workerToken)
                if (identity.first == 200 && identity.second.optJSONObject("device")?.optString("role") == "worker") {
                    runOnUiThread {
                        role.text = "TRUSTED WORKER"
                        status.text = "Headquarters verified this device's worker credential."
                        hqPanel.visibility = View.GONE
                        workerPanel.visibility = View.VISIBLE
                    }
                } else if (identity.first == 401 || identity.first == 403) {
                    runOnUiThread {
                        role.text = "UNPAIRED DEVICE"
                        status.text = "Headquarters rejected this worker credential. This device is not currently trusted."
                        hqPanel.visibility = View.GONE
                        workerPanel.visibility = View.VISIBLE
                    }
                } else error("Could not verify worker role")
            } catch (_: Exception) {
                runOnUiThread {
                    role.text = "WORKER STATUS UNKNOWN"
                    status.text = "HQ could not be reached to verify this stored credential. Connect to the same network and detect again."
                    hqPanel.visibility = View.GONE
                    workerPanel.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun secureHqBase(value: String): String {
        val base = value.trim().trimEnd('/')
        if (base.startsWith("https://", ignoreCase = true)) return base
        if (!base.startsWith("http://", ignoreCase = true)) return base
        val remainder = base.substringAfter("://")
        val authority = remainder.substringBefore("/")
        if (!authority.endsWith(":8787")) return base
        val host = authority.removeSuffix(":8787")
        val suffix = remainder.removePrefix(authority)
        return "https://$host:8789$suffix"
    }

    private fun connectRemote() {
        val entered = endpoint.text.toString()
        val base = secureHqBase(entered)
        if (base.isBlank()) { status.text = "Enter Phone A's secure HTTPS address first."; return }
        if (!base.startsWith("https://", ignoreCase = true)) {
            status.text = "Cleartext HTTP is disabled. Use Phone A's HTTPS address on port 8789."
            return
        }
        endpoint.setText(base)
        prefs.edit().putString("hq_url", base).apply()
        detectDeviceRole()
    }

    private fun recordIsolatedTestResult(message: String, details: JSONObject? = null) {
        prefs.edit().putString("last_isolated_test_result", message).apply()
        if (::isolatedTestSummary.isInitialized) isolatedTestSummary.text = message
        status.text = message
        val token = adminTokenValue
        if (details != null && token.isNotBlank()) {
            executor.execute {
                try {
                    val body = JSONObject(details.toString()).put("message", message)
                    val result = api(localHQ + "/api/isolated-test/result", method = "POST", body = body, token = token)
                    prefs.edit().putString("last_isolated_test_upload",
                        if (result.first in 200..299) "Result saved to Phone A HQ."
                        else "HQ log upload failed: " + result.second.optString("error", result.first.toString())).apply()
                } catch (e: Exception) {
                    prefs.edit().putString("last_isolated_test_upload",
                        "HQ log upload unavailable: " + (e.message ?: "connection error")).apply()
                }
            }
        }
    }

    private fun testIsolatedGuestCompute() {
        recordIsolatedTestResult("Running OS-isolated guest compute test…")
        val requestId = UUID.randomUUID().toString()
        val program = java.nio.ByteBuffer.allocate(20).order(java.nio.ByteOrder.BIG_ENDIAN)
            .put(1).putLong(20L).put(1).putLong(22L).put(2).put(6).array()
        var bound = false
        lateinit var connection: ServiceConnection
        val reply = Messenger(Handler(Looper.getMainLooper()) { response ->
            val data = response.data
            val responseId = data.getString(IsolatedGuestComputeService.KEY_REQUEST_ID).orEmpty()
            val ok = data.getBoolean(IsolatedGuestComputeService.KEY_OK, false)
            val result = data.getLong(IsolatedGuestComputeService.KEY_RESULT, Long.MIN_VALUE)
            val isolatedUid = data.getInt(IsolatedGuestComputeService.KEY_ISOLATED_UID, -1)
            val ownerUid = data.getInt(IsolatedGuestComputeService.KEY_OWNER_UID, -1)
            val networkPermission = data.getBoolean(IsolatedGuestComputeService.KEY_NETWORK_PERMISSION, true)
            val privateDataAccess = data.getBoolean(IsolatedGuestComputeService.KEY_PRIVATE_DATA_ACCESS, true)
            val valid = responseId == requestId && ok && result == 42L && isolatedUid >= 0 && ownerUid >= 0 && isolatedUid != ownerUid && !networkPermission && !privateDataAccess
            recordIsolatedTestResult(if (valid) {
                "OS-isolated guest test PASSED: 20 + 22 = " + result + "; isolated UID " + isolatedUid + " differs from HQ app UID " + ownerUid + "; network permission absent; app-private data unreadable."
            } else {
                "Isolated guest test FAILED or returned an invalid identity/result. Error: " + (data.getString(IsolatedGuestComputeService.KEY_ERROR) ?: "response validation failed")
            })
            if (bound) { try { unbindService(connection) } catch (_: Exception) {}; bound = false }
            true
        })
        connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                try {
                    val request = Message.obtain(null, IsolatedGuestComputeService.MSG_EXECUTE)
                    request.data = Bundle().apply {
                        putString(IsolatedGuestComputeService.KEY_REQUEST_ID, requestId)
                        putByteArray(IsolatedGuestComputeService.KEY_PROGRAM, program)
                        putInt(IsolatedGuestComputeService.KEY_MAX_STEPS, 100)
                    }
                    request.replyTo = reply
                    Messenger(binder).send(request)
                } catch (e: Exception) {
                    recordIsolatedTestResult("Could not submit isolated guest request: " + e.message)
                    if (bound) { try { unbindService(this) } catch (_: Exception) {}; bound = false }
                }
            }
            override fun onServiceDisconnected(name: ComponentName) {
                recordIsolatedTestResult("Isolated guest process disconnected.")
                if (bound) { try { unbindService(this) } catch (_: Exception) {}; bound = false }
            }
        }
        val intent = Intent(this, IsolatedGuestComputeService::class.java)
        bound = bindService(intent, connection, BIND_AUTO_CREATE)
        if (!bound) recordIsolatedTestResult("Android refused to bind the isolated guest process.")
    }
    private fun testIsolatedGuestWasmCompute() {
        recordIsolatedTestResult("Running OS-isolated WASM test…")
        val requestId = UUID.randomUUID().toString()
        val module = byteArrayOf(
            0,97,115,109,1,0,0,0,
            1,5,1,96,0,1,127,
            3,2,1,0,
            7,7,1,3,114,117,110,0,0,
            10,6,1,4,0,65,42,11
        )
        var bound = false
        lateinit var connection: ServiceConnection
        val reply = Messenger(Handler(Looper.getMainLooper()) { response ->
            val data = response.data
            val responseId = data.getString(IsolatedGuestComputeService.KEY_REQUEST_ID).orEmpty()
            val ok = data.getBoolean(IsolatedGuestComputeService.KEY_OK, false)
            val result = data.getLong(IsolatedGuestComputeService.KEY_RESULT, Long.MIN_VALUE)
            val isolatedUid = data.getInt(IsolatedGuestComputeService.KEY_ISOLATED_UID, -1)
            val ownerUid = data.getInt(IsolatedGuestComputeService.KEY_OWNER_UID, -1)
            val networkPermission = data.getBoolean(IsolatedGuestComputeService.KEY_NETWORK_PERMISSION, true)
            val privateDataAccess = data.getBoolean(IsolatedGuestComputeService.KEY_PRIVATE_DATA_ACCESS, true)
            val valid = responseId == requestId && ok && result == 42L &&
                isolatedUid >= 0 && ownerUid >= 0 && isolatedUid != ownerUid &&
                !networkPermission && !privateDataAccess
            val message = if (valid) {
                "OS-isolated WASM test PASSED: import-free WebAssembly returned " + result +
                    "; isolated UID " + isolatedUid + " differs from HQ app UID " + ownerUid +
                    "; network permission absent; app-private data unreadable."
            } else {
                "Isolated WASM test FAILED. Error: " +
                    (data.getString(IsolatedGuestComputeService.KEY_ERROR) ?: "response validation failed")
            }
            recordIsolatedTestResult(message, JSONObject()
                .put("testType", "android-isolated-wasm")
                .put("requestId", requestId)
                .put("passed", valid)
                .put("result", if (result == Long.MIN_VALUE) JSONObject.NULL else result)
                .put("isolatedUid", isolatedUid)
                .put("ownerUid", ownerUid)
                .put("networkPermission", networkPermission)
                .put("privateDataReadable", privateDataAccess)
                .put("error", data.getString(IsolatedGuestComputeService.KEY_ERROR).orEmpty()))
            if (bound) { try { unbindService(connection) } catch (_: Exception) {}; bound = false }
            true
        })
        connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                try {
                    val request = Message.obtain(null, IsolatedGuestComputeService.MSG_EXECUTE_WASM)
                    request.data = Bundle().apply {
                        putString(IsolatedGuestComputeService.KEY_REQUEST_ID, requestId)
                        putByteArray(IsolatedGuestComputeService.KEY_PROGRAM, module)
                        putString(IsolatedGuestComputeService.KEY_EXPORT_NAME, "run")
                        putLongArray(IsolatedGuestComputeService.KEY_ARGUMENTS, longArrayOf())
                        putInt(IsolatedGuestComputeService.KEY_MAX_STEPS, 1000)
                    }
                    request.replyTo = reply
                    Messenger(binder).send(request)
                } catch (e: Exception) {
                    recordIsolatedTestResult("Could not submit isolated WASM request: " + e.message)
                    if (bound) { try { unbindService(this) } catch (_: Exception) {}; bound = false }
                }
            }
            override fun onServiceDisconnected(name: ComponentName) {
                recordIsolatedTestResult("Isolated WASM guest process disconnected.")
                if (bound) { try { unbindService(this) } catch (_: Exception) {}; bound = false }
            }
        }
        val intent = Intent(this, IsolatedGuestComputeService::class.java)
        bound = bindService(intent, connection, BIND_AUTO_CREATE)
        if (!bound) recordIsolatedTestResult("Android refused to bind the isolated WASM guest process.")
    }

    private fun loadSystemHealth() {
        val token = adminTokenValue
        if (token.isBlank()) {
            systemHealthDisplay.removeAllViews()
            systemHealthDisplay.addView(healthMessage("Enter the HQ administrator token on Home first."))
            return
        }
        systemHealthDisplay.removeAllViews()
        systemHealthDisplay.addView(healthMessage("Refreshing Phone A system health…"))
        executor.execute {
            try {
                val response = api(localHQ + "/api/system/health", token = token)
                if (response.first !in 200..299) error(response.second.optString("error", "System health request failed."))
                runOnUiThread { renderSystemHealth(response.second) }
            } catch (error: Exception) {
                runOnUiThread {
                    systemHealthDisplay.removeAllViews()
                    systemHealthDisplay.addView(healthPanel("SYSTEM HEALTH UNAVAILABLE").apply {
                        addView(healthMessage(error.message ?: "Unknown error"))
                    })
                }
            }
        }
    }

    private fun renderSystemHealth(payload: JSONObject) {
        systemHealthDisplay.removeAllViews()
        val runtime = payload.optJSONObject("runtime") ?: JSONObject()
        val memory = runtime.optJSONObject("processMemoryBytes") ?: JSONObject()
        val systemMemory = runtime.optJSONObject("systemMemoryBytes") ?: JSONObject()
        val services = payload.optJSONArray("services") ?: JSONArray()
        val serviceItems = (0 until services.length()).mapNotNull { services.optJSONObject(it) }
        val running = serviceItems.count { it.optString("state") == "running" }
        val idle = serviceItems.count { it.optString("state") == "idle" }
        val attention = serviceItems.count { it.optString("state") == "unhealthy" || it.optString("state") == "stopped" }

        val overview = healthPanel("PHONE A · RUNTIME")
        overview.addView(healthDetail("Authoritative Genesis device"))
        overview.addView(healthMetricRow(
            "UPTIME" to formatHealthUptime(runtime.optLong("uptimeSeconds")),
            "PROCESS RSS" to formatHealthBytes(memory.optLong("rss"))
        ))
        val heapLimitBytes = runtime.optLong("v8HeapSizeLimitBytes", 0L)
        overview.addView(healthMetricRow(
            "HEAP USED / ALLOCATED" to "${formatHealthBytes(memory.optLong("heapUsed"))} / ${formatHealthBytes(memory.optLong("heapTotal"))}",
            "V8 HEAP LIMIT" to if (heapLimitBytes > 0) formatHealthBytes(heapLimitBytes) else "Unavailable"
        ))
        overview.addView(healthMetricRow(
            "EXTERNAL MEMORY" to formatHealthBytes(memory.optLong("external")),
            "ARRAY BUFFERS" to formatHealthBytes(memory.optLong("arrayBuffers"))
        ))
        overview.addView(healthMetricRow(
            "SYSTEM MEMORY" to "${formatHealthBytes(systemMemory.optLong("free"))} free / ${formatHealthBytes(systemMemory.optLong("total"))}"
        ))
        val load = runtime.optJSONArray("loadAverage")
        val loadText = if (load != null && load.length() >= 3) {
            (0..2).joinToString(" · ") { String.format(java.util.Locale.US, "%.2f", load.optDouble(it)) }
        } else "Unavailable"
        overview.addView(healthDetail("Load average · 1 / 5 / 15 min: $loadText"))
        overview.addView(healthDetail("Updated · ${payload.optString("generatedAt", "Unknown")}"))
        systemHealthDisplay.addView(overview)

        val summary = healthPanel("SERVICE SUMMARY")
        summary.addView(healthMetricRow(
            "RUNNING" to running.toString(),
            "IDLE · OPTIONAL" to idle.toString()
        ))
        summary.addView(healthMetricRow("NEEDS ATTENTION" to attention.toString()))
        summary.addView(healthDetail("Idle guest services are normal when they are not in use."))
        systemHealthDisplay.addView(summary)

        val groups = listOf(
            "CONTROL CENTER" to setOf("control-center-launcher", "control-center-server", "remote-gateway"),
            "DEVELOPMENT BRIDGE" to setOf("dev-bridge-supervisor", "dev-bridge-controller"),
            "GUEST EXECUTION" to setOf("android-guest-bridge")
        )
        for ((title, ids) in groups) {
            val panel = healthPanel(title)
            val items = serviceItems.filter { ids.contains(it.optString("id")) }
            if (items.isEmpty()) panel.addView(healthDetail("No service data reported."))
            for (item in items) panel.addView(healthServiceCard(item))
            systemHealthDisplay.addView(panel)
        }

        val workerService = serviceItems.firstOrNull { it.optString("id") == "worker-connections" }
        val workers = workerService?.optJSONArray("devices") ?: JSONArray()
        val workerPanel = healthPanel("TRUSTED WORKER DEVICES · ${workers.length()}")
        if (workers.length() == 0) {
            workerPanel.addView(healthDetail("No worker devices are currently reporting."))
        } else {
            for (index in 0 until workers.length()) {
                val device = workers.optJSONObject(index) ?: continue
                val deviceCard = healthPanel(device.optString("label", "Worker device"))
                deviceCard.addView(healthStatusBadge(device.optString("telemetry", "unknown")))
                workerPanel.addView(deviceCard)
            }
        }
        systemHealthDisplay.addView(workerPanel)
    }

    private fun healthServiceCard(item: JSONObject): LinearLayout {
        val id = item.optString("id")
        val card = healthPanel(item.optString("label", "Unnamed service"))
        val state = item.optString("state", "unknown")
        card.addView(healthStatusBadge(state))
        val pid = if (!item.isNull("pid") && item.has("pid")) item.optInt("pid").toString() else null
        val parent = if (!item.isNull("parentPid") && item.has("parentPid")) item.optInt("parentPid").toString() else null
        if (pid != null) card.addView(healthDetail("PID $pid" + (parent?.let { " · parent $it" } ?: "")))
        val details = item.optJSONObject("details")
        if (details != null) {
            card.addView(healthDetail("Pending ${details.optInt("pending")} · queued ${details.optInt("queued")} · claimed ${if (details.optBoolean("claimed")) "yes" else "no"}"))
        }
        val command = when (id) {
            "control-center-launcher" -> "npm run control"
            "control-center-server" -> "node src/control/server.js"
            "remote-gateway" -> "Loopback HTTP server in Control Center"
            "dev-bridge-supervisor" -> "node tools/dev-bridge-supervisor.js"
            "dev-bridge-controller" -> "node tools/genesis-dev-controller.js"
            "android-guest-bridge" -> "Local Android isolated guest service"
            else -> ""
        }
        if (command.isNotBlank()) card.addView(healthDetail(command))
        if (id == "android-guest-bridge" && state == "idle") {
            card.addView(healthDetail("Optional service · starts when isolated guest execution is needed."))
        }
        return card
    }

    private fun healthPanel(title: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(12, 11, 12, 11)
        background = GradientDrawable().apply {
            cornerRadius = 14f
            setColor(0xFF1F2D3D.toInt())
        }
        addView(TextView(this@MainActivity).apply {
            text = title
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFFB9D7FF.toInt())
            setPadding(0, 0, 0, 7)
        })
        val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        params.bottomMargin = 10
        layoutParams = params
    }

    private fun healthMetricRow(vararg metrics: Pair<String, String>): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        for ((index, metric) in metrics.withIndex()) {
            val cell = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(9, 8, 9, 8)
                background = GradientDrawable().apply {
                    cornerRadius = 10f
                    setColor(0xFF29394B.toInt())
                }
                addView(TextView(this@MainActivity).apply {
                    text = metric.first
                    textSize = 10f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(0xFF9FB3C9.toInt())
                })
                addView(TextView(this@MainActivity).apply {
                    text = metric.second
                    textSize = 14f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(0xFFF3F6FA.toInt())
                    setPadding(0, 3, 0, 0)
                })
            }
            val params = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            if (index > 0) params.leftMargin = 7
            params.bottomMargin = 7
            addView(cell, params)
        }
    }

    private fun healthStatusBadge(state: String): TextView {
        val normalized = state.lowercase()
        val (label, foreground, backgroundColor) = when (normalized) {
            "running" -> Triple("RUNNING", 0xFF9BE0B0.toInt(), 0xFF214A3B.toInt())
            "reported" -> Triple("REPORTED", 0xFF9BCBFF.toInt(), 0xFF203D59.toInt())
            "idle" -> Triple("IDLE · OPTIONAL", 0xFFC1CBD7.toInt(), 0xFF394452.toInt())
            "unhealthy" -> Triple("UNHEALTHY", 0xFFFFA0A0.toInt(), 0xFF592D35.toInt())
            "stopped" -> Triple("STOPPED", 0xFFFFC783.toInt(), 0xFF59432D.toInt())
            else -> Triple(normalized.uppercase().ifBlank { "UNKNOWN" }, 0xFFC1CBD7.toInt(), 0xFF394452.toInt())
        }
        return TextView(this).apply {
            text = label
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(foreground)
            setPadding(9, 5, 9, 5)
            background = GradientDrawable().apply {
                cornerRadius = 18f
                setColor(backgroundColor)
            }
        }
    }

    private fun healthMessage(message: String) = TextView(this).apply {
        text = message
        textSize = 13f
        alpha = 0.86f
        setPadding(4, 8, 4, 12)
        setTextIsSelectable(true)
    }

    private fun healthDetail(message: String) = TextView(this).apply {
        text = message
        textSize = 12f
        alpha = 0.86f
        setPadding(0, 2, 0, 5)
        setTextIsSelectable(true)
    }

    private fun formatHealthBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024L * 1024L -> String.format(java.util.Locale.US, "%.2f GiB", bytes / (1024.0 * 1024.0 * 1024.0))
        bytes >= 1024L * 1024L -> String.format(java.util.Locale.US, "%.1f MiB", bytes / (1024.0 * 1024.0))
        bytes >= 1024L -> String.format(java.util.Locale.US, "%.1f KiB", bytes / 1024.0)
        else -> "$bytes B"
    }

    private fun formatHealthUptime(seconds: Long): String {
        val days = seconds / 86400
        val hours = (seconds % 86400) / 3600
        val minutes = (seconds % 3600) / 60
        return if (days > 0) "${days}d ${hours}h ${minutes}m" else if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
    }

    private fun refreshThermalTelemetry() {
        thermalDisplay.removeAllViews()
        thermalDisplay.addView(thermalMessage("Collecting accessible sensors from Phone A and retrieving worker reports…"))
        executor.execute {
            try {
                val local = ThermalMonitor.collect(this, "phone-a", "Phone A")
                val token = adminTokenValue
                val remote = if (token.isNotBlank()) {
                    val response = api(localHQ + "/api/thermal/telemetry", token = token)
                    if (response.first !in 200..299) error(response.second.optString("error", "Worker thermal telemetry unavailable."))
                    response.second.optJSONArray("devices") ?: JSONArray()
                } else JSONArray()
                runOnUiThread { renderThermalDevices(local, remote, token.isBlank()) }
            } catch (error: Exception) {
                runOnUiThread {
                    thermalDisplay.removeAllViews()
                    thermalDisplay.addView(thermalMessage("THERMAL TELEMETRY ERROR\n${error.message ?: "Unknown error"}"))
                }
            }
        }
    }

    private fun renderThermalDevices(local: JSONObject, remote: JSONArray, needsToken: Boolean) {
        thermalDisplay.removeAllViews()
        thermalDisplay.addView(thermalMessage("Known temperature zones are shown in °C. Qualcomm battery-voltage and current zones use their own units and threshold directions. Unknown zone types remain raw and unclassified; kernel temperatures may be rounded or quantized by the driver."))
        addThermalDeviceCard(local, isWorker = false)
        for (index in 0 until remote.length()) {
            val device = remote.optJSONObject(index) ?: continue
            addThermalDeviceCard(device, isWorker = true)
        }
        if (needsToken) {
            thermalDisplay.addView(thermalMessage("Enter the HQ administrator token in Home to retrieve Phone B and other worker reports."))
        }
    }

    private fun addThermalDeviceCard(device: JSONObject, isWorker: Boolean) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 14, 16, 14)
            background = GradientDrawable().apply {
                cornerRadius = 18f
                setColor(0xFF1B2636.toInt())
                setStroke(1, 0xFF34445A.toInt())
            }
        }
        val label = device.optString("label", if (isWorker) "Worker" else "Phone A")
        val sensors = device.optJSONArray("sensors") ?: JSONArray()
        val title = TextView(this).apply {
            text = "${if (isWorker) "WORKER" else "PHONE A"} · $label"
            textSize = 17f; typeface = Typeface.DEFAULT_BOLD; setTextColor(0xFFF3F6FA.toInt())
        }
        card.addView(title)
        card.addView(thermalDetail("Model: ${device.optString("model", "Unknown")}"))
        card.addView(thermalDetail("Android thermal status: ${thermalStatusLabel(device.optInt("thermalStatus", -1))}"))
        if (isWorker) {
            val reportStatus = device.optString("status", "unknown")
            val receivedAt = device.optLong("receivedAt", 0L)
            val age = if (receivedAt > 0L) ((System.currentTimeMillis() - receivedAt).coerceAtLeast(0L) / 1000L) else -1L
            card.addView(thermalDetail("Worker report: $reportStatus" + if (age >= 0L) " · ${age}s ago" else " · no timestamp"))
        }
        card.addView(thermalDetail("${sensors.length()} readable sensors · ${device.optInt("unavailableSensors")} unavailable/inaccessible"))
        val groups = linkedMapOf<String, MutableList<JSONObject>>()
        for (i in 0 until sensors.length()) {
            val sensor = sensors.optJSONObject(i) ?: continue
            val name = sensor.optString("name").lowercase()
            val group = when {
                listOf("usb", "charger", "charging", "connector").any { name.contains(it) } -> "USB & charging"
                listOf("battery", "ibat", "batt", "pmic", "vbat").any { name.contains(it) } -> "Battery & power"
                listOf("cpu", "gpu", "soc", "tsens", "cluster", "skin").any { name.contains(it) } -> "Processor & system"
                sensor.optString("type") == "battery" -> "Battery & power"
                sensor.optString("type") == "thermal-zone" -> "System thermal zones"
                else -> "Other sensors"
            }
            groups.getOrPut(group) { mutableListOf() }.add(sensor)
        }
        if (groups.isEmpty()) card.addView(thermalDetail("No readable kernel sensors were reported by this device."))
        for ((groupName, items) in groups) {
            val heading = TextView(this).apply {
                text = "$groupName · ${items.size}"
                textSize = 14f; typeface = Typeface.DEFAULT_BOLD; setTextColor(0xFFB9D7FF.toInt())
                setPadding(0, 12, 0, 6)
            }
            card.addView(heading)
            for (sensor in items) card.addView(thermalSensorCard(sensor))
        }
        val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        params.bottomMargin = 14
        thermalDisplay.addView(card, params)
    }

    private fun thermalSensorCard(sensor: JSONObject): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 10, 12, 10)
            background = GradientDrawable().apply {
                cornerRadius = 12f
                setColor(0xFF263447.toInt())
            }
        }
        val name = sensor.optString("name", "Unnamed sensor")
        val sensorType = sensor.optString("type")
        val rawValue = when {
            sensor.has("rawValue") && !sensor.isNull("rawValue") -> sensor.optDouble("rawValue", Double.NaN)
            sensor.has("rawTemperatureMilliC") && !sensor.isNull("rawTemperatureMilliC") -> sensor.optDouble("rawTemperatureMilliC", Double.NaN)
            sensor.has("rawTemperatureTenthsC") && !sensor.isNull("rawTemperatureTenthsC") -> sensor.optDouble("rawTemperatureTenthsC", Double.NaN)
            else -> Double.NaN
        }
        val declaredKind = sensor.optString("measurementKind")
        val legacyReading = if (declaredKind.isBlank() && rawValue.isFinite()) {
            when (sensorType) {
                "hwmon" -> interpretTemperatureMilliC(rawValue)
                "battery" -> interpretBatteryTemperatureTenthsC(rawValue)
                else -> interpretThermalZoneReading(name, rawValue)
            }
        } else null
        val kind = declaredKind.ifBlank { legacyReading?.measurementKind ?: "unknown" }
        val unit = sensor.optString("unit").ifBlank { legacyReading?.unit ?: "raw" }
        val displayValue = when {
            sensor.has("displayValue") && !sensor.isNull("displayValue") -> sensor.optDouble("displayValue", Double.NaN)
            legacyReading != null -> legacyReading.displayValue ?: Double.NaN
            else -> Double.NaN
        }
        // Reinterpret legacy worker reports from their raw value and sensor identity.
        // Never trust the old temperatureC field when measurement semantics are absent.
        val readingStatus = if (legacyReading != null) legacyReading.readingStatus
            else sensor.optString("readingStatus", "unclassified")
        val value = if (displayValue.isFinite()) {
            formatSensorValue(displayValue, unit)
        } else {
            when {
                readingStatus == "unavailable-sentinel" -> "Unavailable · sensor sentinel"
                kind == "unknown" -> "Unclassified · raw ${rawValue.takeIf { it.isFinite() }?.let { formatSensorValue(it, "raw") } ?: "not readable"}"
                else -> "Unavailable"
            }
        }
        val legacyConcerning = if (kind == "temperature" && !sensor.isNull("concerningC"))
            sensor.optDouble("concerningC", Double.NaN).takeIf { it.isFinite() } else null
        val legacyDangerous = if (kind == "temperature" && !sensor.isNull("dangerousC"))
            sensor.optDouble("dangerousC", Double.NaN).takeIf { it.isFinite() } else null
        val classification = if (legacyReading != null) {
            classifyThermalReading(displayValue.takeIf { it.isFinite() }, readingStatus,
                legacyConcerning, legacyDangerous, legacyReading.thresholdDirection)
        } else sensor.optString("classification", "unknown")
        val statusLabel = when (classification) {
            "dangerous" -> "DANGEROUS"
            "concerning" -> "CONCERNING"
            "normal" -> "NORMAL"
            else -> when {
                kind == "unknown" -> "UNCLASSIFIED · UNIT UNKNOWN"
                readingStatus == "unavailable-sentinel" -> "SENSOR UNAVAILABLE"
                readingStatus == "valid" -> "THRESHOLD UNKNOWN"
                else -> "UNAVAILABLE"
            }
        }
        val statusColor = when (classification) {
            "dangerous" -> 0xFFFF8A8A.toInt()
            "concerning" -> 0xFFFFC46B.toInt()
            "normal" -> 0xFF91D7A5.toInt()
            else -> 0xFFB8C4D3.toInt()
        }
        row.addView(TextView(this).apply {
            text = name; textSize = 14f; typeface = Typeface.DEFAULT_BOLD; setTextColor(0xFFF3F6FA.toInt())
        })
        row.addView(TextView(this).apply {
            text = value; textSize = 20f; typeface = Typeface.DEFAULT_BOLD; setTextColor(0xFFFFFFFF.toInt())
            setPadding(0, 3, 0, 1)
        })
        row.addView(TextView(this).apply {
            text = statusLabel; textSize = 11f; typeface = Typeface.DEFAULT_BOLD; setTextColor(statusColor)
        })

        val thresholds = sensor.optJSONArray("thresholds")
        if (thresholds != null && thresholds.length() > 0) {
            val descriptions = mutableListOf<String>()
            for (index in 0 until thresholds.length()) {
                val point = thresholds.optJSONObject(index) ?: continue
                val label = point.optString("label", "Kernel trip")
                val thresholdValue = point.optDouble("value", Double.NaN)
                val thresholdUnit = point.optString("unit", "raw")
                if (thresholdValue.isFinite()) descriptions += "$label: ${formatSensorValue(thresholdValue, thresholdUnit)}"
            }
            if (descriptions.isNotEmpty()) row.addView(thermalDetail(descriptions.joinToString(" · ")))
        } else if (kind == "temperature") {
            val concerning = if (!sensor.isNull("concerningC")) "${formatCelsius(sensor.optDouble("concerningC"))} °C" else "not exposed"
            val dangerous = if (!sensor.isNull("dangerousC")) "${formatCelsius(sensor.optDouble("dangerousC"))} °C" else "not exposed"
            row.addView(thermalDetail("Concerning trip: $concerning · Critical trip: $dangerous"))
        } else if (kind == "unknown") {
            row.addView(thermalDetail("Unit and trip-point semantics have not been confirmed for this zone."))
        } else {
            row.addView(thermalDetail("No device trip points were exposed for this measurement."))
        }

        if (kind == "unknown" || readingStatus != "valid") {
            val rawText = if (rawValue.isFinite()) formatSensorValue(rawValue, "raw") else "not readable"
            row.addView(thermalDetail("Raw kernel value: $rawText · ${sensor.optString("sourcePath")}"))
        }
        val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        params.bottomMargin = 7
        row.layoutParams = params
        return row
    }

    private fun thermalMessage(message: String) = TextView(this).apply {
        text = message; textSize = 13f; alpha = 0.82f; setPadding(4, 6, 4, 12); setTextIsSelectable(true)
    }

    private fun thermalDetail(message: String) = TextView(this).apply {
        text = message; textSize = 12f; alpha = 0.82f; setPadding(0, 3, 0, 2); setTextIsSelectable(true)
    }

    private fun formatSensorValue(value: Double, unit: String): String = when (unit) {
        "°C" -> "${formatCelsius(value)} °C"
        "V" -> String.format(java.util.Locale.US, "%.3f V", value)
        "mA" -> String.format(java.util.Locale.US, "%.0f mA", value)
        else -> String.format(java.util.Locale.US, "%.3f %s", value, unit)
    }

    private fun formatCelsius(value: Double): String = String.format(java.util.Locale.US, "%.1f", value)

    private fun thermalStatusLabel(value: Int): String = when (value) {
        0 -> "NORMAL"
        1 -> "LIGHT"
        2 -> "MODERATE · CONCERNING"
        3 -> "SEVERE · CONCERNING"
        4 -> "CRITICAL · DANGEROUS"
        5 -> "EMERGENCY · DANGEROUS"
        6 -> "SHUTDOWN · DANGEROUS"
        else -> "UNAVAILABLE"
    }

    private fun startAndroidGuestBridge() {
        val token = adminTokenValue
        if (token.isBlank()) { status.text = "Enter the HQ administrator token first."; return }
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 7302)
            status.text = "Allow notifications, then tap Start local Android isolated guest bridge again."
            return
        }
        val intent = Intent(this, AndroidGuestBridgeService::class.java)
            .putExtra(AndroidGuestBridgeService.EXTRA_ADMIN_TOKEN, token)
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
            status.text = "Phone A's local isolated guest bridge is starting. Keep its Genesis notification visible."
        } catch (e: Exception) {
            status.text = "Could not start the isolated guest bridge: ${e.message}"
        }
    }

    private fun stopAndroidGuestBridge() {
        stopService(Intent(this, AndroidGuestBridgeService::class.java))
        status.text = "Phone A's local isolated guest bridge stopped."
    }

    private fun startAndroidBuildSigningBridge(rotateIdentity: Boolean = false) {
        val token = adminTokenValue
        if (token.isBlank()) { status.text = "Enter the HQ administrator token first."; return }
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 7303)
            status.text = if (rotateIdentity) {
                "Allow notifications, then tap Rotate GenB APK signing identity again."
            } else {
                "Allow notifications, then tap Start Phone A GenB APK signing bridge again."
            }
            return
        }
        val intent = Intent(this, AndroidBuildSigningBridgeService::class.java)
            .putExtra(AndroidBuildSigningBridgeService.EXTRA_ADMIN_TOKEN, token)
            .putExtra(AndroidBuildSigningBridgeService.EXTRA_ROTATE_SIGNING_IDENTITY, rotateIdentity)
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
            status.text = if (rotateIdentity) {
                "Phone A is rotating the GenB signing identity. Uninstall any existing GenB Analytics install before installing an APK signed with the new key."
            } else {
                "Phone A's AndroidKeyStore signing bridge is starting. The private signing key stays on this phone."
            }
        } catch (e: Exception) {
            status.text = "Could not start Phone A's APK signing bridge: ${e.message}"
        }
    }

    private fun confirmAndroidBuildSigningKeyRotation() {
        AlertDialog.Builder(this)
            .setTitle("Rotate GenB APK signing identity?")
            .setMessage(
                "This permanently replaces the Phone A GenB signing key and certificate pin. " +
                    "Android will reject an APK signed by the new key as an update to an app signed by the old key. " +
                    "Uninstall the existing GenB Analytics app before installing the next build; uninstalling may erase its local app data."
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Rotate signing key") { _, _ ->
                startAndroidBuildSigningBridge(rotateIdentity = true)
            }
            .show()
    }

    private fun stopAndroidBuildSigningBridge() {
        stopService(Intent(this, AndroidBuildSigningBridgeService::class.java))
        status.text = "Phone A's GenB APK signing bridge stopped."
    }

    private fun loadIsolatedTestHistory() {
        val token = adminTokenValue
        if (token.isBlank()) { status.text = "Enter the HQ administrator token first."; return }
        executor.execute {
            try {
                val response = api(localHQ + "/api/isolated-test/results", token = token)
                if (response.first !in 200..299) error(response.second.optString("error", "Could not load isolated-test history."))
                val results = response.second.optJSONArray("results") ?: JSONArray()
                val summary = buildString {
                    append("Phone A saved isolated-test history: ").append(results.length())
                    val start = maxOf(0, results.length() - 5)
                    for (index in results.length() - 1 downTo start) {
                        val item = results.optJSONObject(index) ?: continue
                        append("\n\n").append(item.optString("recordedAt"))
                        append("\n").append(if (item.optBoolean("passed")) "PASSED" else "FAILED")
                        append(" · ").append(item.optString("testType"))
                        append("\nResult: ").append(item.opt("result"))
                        append("\n").append(item.optString("message"))
                    }
                }
                runOnUiThread {
                    isolatedTestSummary.text = summary
                    status.text = "Loaded isolated-test evidence from Phone A."
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "Could not load Phone A test history: " + (e.message ?: "unknown error") }
            }
        }
    }

    private fun workspaceRequest(
        route: String,
        method: String = "GET",
        body: JSONObject? = null,
        readTimeoutMs: Int = 5000,
        render: (JSONObject) -> String
    ) {
        val token = adminTokenValue
        if (token.isBlank()) { status.text = "Enter the HQ administrator token first."; return }
        val action = if (route == "/api/workspace/execute") "Executing stored WASM export 'run'" else "Workspace request started"
        workspaceFilesDisplay.text = "$action…\nWaiting for Phone A and the Android isolated guest bridge."
        executor.execute {
            try {
                val result = api(localHQ + route, method = method, body = body, token = token, readTimeoutMs = readTimeoutMs)
                if (result.first !in 200..299) {
                    val detail = result.second.optString("error", "Workspace request failed (HTTP ${result.first}).")
                    runOnUiThread {
                        workspaceFilesDisplay.text = "WORKSPACE REQUEST FAILED\nHTTP ${result.first}\n$detail"
                        status.text = detail
                    }
                    return@execute
                }
                val message = render(result.second)
                runOnUiThread { workspaceFilesDisplay.text = message }
            } catch (e: Exception) {
                val detail = e.message ?: "Workspace request failed."
                runOnUiThread {
                    workspaceFilesDisplay.text = "WORKSPACE REQUEST FAILED\n$detail\n\nUse 'Check isolated WASM execution status' to inspect bridge connectivity and queue state."
                    status.text = detail
                }
            }
        }
    }

    private fun loadGuestWorkspaceFiles() = workspaceRequest("/api/workspace/files") { result ->
        val files = result.optJSONArray("files") ?: org.json.JSONArray()
        "Workspace files (${files.length()}):\n${org.json.JSONObject().put("files", files).toString(2)}"
    }

    private fun readGuestWorkspaceFile() {
        val path = workspacePathInput.text.toString().trim()
        val encoded = java.net.URLEncoder.encode(path, "UTF-8")
        workspaceRequest("/api/workspace/file?path=" + encoded) { result ->
            val file = result.optJSONObject("file") ?: JSONObject()
            "Read ${file.optString("path", path)}:\n${file.optString("content")}"
        }
    }

    private fun saveGuestWorkspaceText() {
        val body = JSONObject().put("path", workspacePathInput.text.toString().trim())
            .put("content", workspaceTextInput.text.toString())
        workspaceRequest("/api/workspace/file", "PUT", body) { result ->
            "Saved text file:\n${result.optJSONObject("file")?.toString(2) ?: result}"
        }
    }

    private fun deleteGuestWorkspaceFile() {
        val body = JSONObject().put("path", workspacePathInput.text.toString().trim())
        workspaceRequest("/api/workspace/file", "DELETE", body) { result ->
            "Delete result:\n${result.optJSONObject("file")?.toString(2) ?: result}"
        }
    }

    private fun storeGuestWorkspaceWasm() {
        val body = JSONObject().put("path", workspaceWasmPathInput.text.toString().trim())
            .put("programBase64", workspaceWasmInput.text.toString().trim())
        workspaceRequest("/api/workspace/wasm", "POST", body) { result ->
            "Stored WASM module:\n${result.optJSONObject("file")?.toString(2) ?: result}"
        }
    }

    private fun executeGuestWorkspaceWasm() {
        val path = workspaceWasmPathInput.text.toString().trim()
        if (path.isBlank()) {
            workspaceFilesDisplay.text = "Execution not started: enter a WASM workspace path."
            return
        }
        val token = adminTokenValue
        if (token.isBlank()) {
            workspaceFilesDisplay.text = "Execution not started: enter the HQ administrator token first."
            return
        }
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 7302)
            workspaceFilesDisplay.text = "Allow Genesis notifications, then tap Execute again. The isolated guest bridge needs its foreground-service notification."
            return
        }
        try {
            val bridgeIntent = Intent(this, AndroidGuestBridgeService::class.java)
                .putExtra(AndroidGuestBridgeService.EXTRA_ADMIN_TOKEN, token)
            if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(bridgeIntent) else startService(bridgeIntent)
            workspaceFilesDisplay.text = "Starting or reusing the local isolated guest bridge…\nExecution will be submitted as soon as the bridge can claim it."
        } catch (error: Exception) {
            workspaceFilesDisplay.text = "Could not start the isolated guest bridge: ${error.message ?: "unknown error"}"
            return
        }
        val body = JSONObject().put("path", path)
            .put("exportName", "run").put("args", org.json.JSONArray())
        workspaceRequest("/api/workspace/execute", "POST", body, readTimeoutMs = 20000) { result ->
            val execution = result.optJSONObject("execution") ?: result
            if (!execution.optBoolean("ok", false)) {
                "WASM EXECUTION FAILED\nPath: $path\nResponse:\n${execution.toString(2)}"
            } else {
                "WASM EXECUTION COMPLETED\nPath: $path\nReturn value: ${execution.opt("result")}\nIsolation attestation:\n" +
                    execution.toString(2)
            }
        }
    }

    private fun loadGuestWorkspaceExecutionStatus() {
        val token = adminTokenValue
        if (token.isBlank()) {
            workspaceFilesDisplay.text = "Diagnostics unavailable: enter the HQ administrator token first."
            return
        }
        workspaceFilesDisplay.text = "Checking Phone A's isolated guest bridge…"
        executor.execute {
            try {
                val response = api(localHQ + "/api/workspace/status", token = token)
                if (response.first !in 200..299) {
                    error(response.second.optString("error", "Bridge diagnostics failed (HTTP ${response.first})."))
                }
                val bridge = response.second.optJSONObject("bridge") ?: JSONObject()
                val workspace = response.second.optJSONObject("workspace") ?: JSONObject()
                val bridgeOnline = bridge.optBoolean("bridgeConnected", false)
                val pending = bridge.optInt("pending", 0)
                val queued = bridge.optInt("queued", 0)
                val claimed = bridge.optBoolean("claimed", false)
                val lastPoll = bridge.optString("lastBridgePollAt", "never")
                val diagnostic = buildString {
                    append("ISOLATED WASM BRIDGE DIAGNOSTICS\n")
                    append("Bridge: ").append(if (bridgeOnline) "ONLINE" else "OFFLINE").append("\n")
                    append("Pending requests: ").append(pending).append("\n")
                    append("Queued requests: ").append(queued).append("\n")
                    append("Request claimed: ").append(claimed).append("\n")
                    append("Last bridge poll: ").append(lastPoll).append("\n")
                    append("Execution mode: ").append(workspace.optString("execution", "unknown")).append("\n")
                    append("\nA successful execution response will include a numeric return value and isolation attestation.")
                }
                runOnUiThread { workspaceFilesDisplay.text = diagnostic }
            } catch (e: Exception) {
                runOnUiThread {
                    workspaceFilesDisplay.text = "BRIDGE DIAGNOSTICS FAILED\n${e.message ?: "Unknown error"}"
                    status.text = e.message ?: "Bridge diagnostics failed."
                }
            }
        }
    }

    private fun loadGuestWorkspaceStatus() {
        val token = adminTokenValue
        if (token.isBlank()) { status.text = "Enter the HQ administrator token first."; return }
        executor.execute {
            try {
                val result = api(localHQ + "/api/status", token = token)
                if (result.first !in 200..299) error(result.second.optString("error", "Could not load HQ status."))
                val guest = result.second.optJSONObject("guestWorkspace")
                    ?: error("This HQ server does not yet report persistent guest workspace status.")
                val workspace = guest.optJSONObject("workspace") ?: JSONObject()
                val disk = guest.optJSONObject("disk") ?: JSONObject()
                val owner = guest.optString("ownerId", "unknown")
                val initialized = guest.optBoolean("initialized", false)
                val files = workspace.optInt("files", 0)
                val used = workspace.optLong("totalBytes", 0)
                val maximum = disk.optLong("maxBytes", 0)
                val generation = disk.optLong("generation", 0)
                val bridge = result.second.optJSONObject("androidGuestBridge") ?: JSONObject()
                val bridgeOnline = bridge.optBoolean("bridgeConnected", false)
                val bridgePending = bridge.optInt("pending", 0)
                val bridgeClaimed = bridge.optBoolean("claimed", false)
                val bridgeLastSeen = bridge.optString("lastBridgePollAt", "never")
                runOnUiThread {
                    guestWorkspaceSummary.text =
                        "Authority: $owner\nInitialized: $initialized\nFiles: $files\nWorkspace bytes: $used\nDisk snapshot: generation $generation\nSnapshot limit: $maximum bytes\n\nAndroid isolated WASM bridge: ${if (bridgeOnline) "ONLINE" else "OFFLINE"}\nPending requests: $bridgePending\nExecution claimed: $bridgeClaimed\nLast bridge poll: $bridgeLastSeen"
                }
            } catch (e: Exception) {
                show(e.message ?: "Could not load persistent workspace status.")
            }
        }
    }

    private fun loadWorkerCapacity() {
        val token = adminTokenValue
        if (token.isBlank()) { status.text = "Enter the HQ administrator token first."; return }
        executor.execute {
            try {
                val result = api(localHQ + "/api/settings/workers", token = token)
                if (result.first !in 200..299) error(result.second.optString("error", "Could not load worker capacity."))
                val max = result.second.optInt("maxWorkers", 1)
                val active = result.second.optInt("activeWorkers", 0)
                val slots = result.second.optInt("availableSlots", 0)
                runOnUiThread {
                    capacityInput.setText(max.toString())
                    capacitySummary.text = "Active trusted workers: $active · Available slots: $slots · Maximum: $max"
                }
            } catch (e: Exception) { show(e.message ?: "Could not load worker capacity.") }
        }
    }

    private fun saveWorkerCapacity() {
        val token = adminTokenValue
        val max = capacityInput.text.toString().toIntOrNull()
        if (token.isBlank()) { status.text = "Enter the HQ administrator token first."; return }
        if (max == null || max !in 1..20) { status.text = "Choose a whole-number worker limit from 1 to 20."; return }
        executor.execute {
            try {
                val result = api(localHQ + "/api/settings/workers", "POST", JSONObject().put("maxWorkers", max), token)
                if (result.first !in 200..299) error(result.second.optString("error", "Could not save worker limit."))
                val active = result.second.optInt("activeWorkers", 0)
                val slots = result.second.optInt("availableSlots", 0)
                runOnUiThread {
                    capacitySummary.text = "Saved. Active trusted workers: $active · Available slots: $slots · Maximum: $max"
                    status.text = "Worker limit saved. Existing trusted workers remain active."
                }
            } catch (e: Exception) { show(e.message ?: "Could not save worker limit.") }
        }
    }

    private fun generateCode() {
        val token = adminTokenValue
        if (token.isBlank()) { status.text = "Enter the HQ administrator token first."; return }
        executor.execute {
            try {
                val result = api(localHQ + "/api/pair/code", "POST", JSONObject(), token)
                if (result.first !in 200..299) error(result.second.optString("error", "Could not generate code."))
                runOnUiThread { codeDisplay.text = "ONE-TIME CODE: " + result.second.optString("code") + "\nExpires in 5 minutes · single use" }
            } catch (e: Exception) { show(e.message ?: "Code generation failed.") }
        }
    }

    private fun refreshRequests() {
        val token = adminTokenValue
        if (token.isBlank()) { status.text = "Enter the HQ administrator token first."; return }
        executor.execute {
            try {
                val result = api(localHQ + "/api/pair/requests", token = token)
                if (result.first !in 200..299) error(result.second.optString("error", "Could not load requests."))
                val requests = result.second.optJSONArray("requests") ?: JSONArray()
                runOnUiThread {
                    requestDisplay.text = if (requests.length() == 0) "No pending pairing requests." else "Pending requests: ${requests.length()}"
                    requestActions.removeAllViews()
                    for (i in 0 until requests.length()) {
                        val item = requests.getJSONObject(i)
                        val id = item.getString("requestId")
                        val label = item.optString("label", "Android device")
                        requestActions.addView(TextView(this).apply {
                            text = "$label\nDevice ID: ${item.optString("deviceId")}"
                            textSize = 13f
                            setPadding(0, 8, 0, 4)
                        })
                        requestActions.addView(button("Approve $label") { decidePairing(id, true) })
                        requestActions.addView(button("Deny $label") { decidePairing(id, false) })
                    }
                }
            } catch (e: Exception) { show(e.message ?: "Could not refresh requests.") }
        }
    }

    private fun decidePairing(id: String, approve: Boolean) {
        val token = adminTokenValue
        executor.execute {
            try {
                val action = if (approve) "approve" else "deny"
                val result = api(localHQ + "/api/pair/" + action, "POST", JSONObject().put("requestId", id), token)
                if (result.first !in 200..299) error(result.second.optString("error", "Pairing action failed."))
                show(if (approve) "Device approved. Phone B will receive its worker credential automatically." else "Pairing request denied.")
                refreshRequests()
            } catch (e: Exception) { show(e.message ?: "Pairing action failed.") }
        }
    }

    private fun refreshDevices() {
        val token = adminTokenValue
        if (token.isBlank()) { status.text = "Enter the HQ administrator token first."; return }
        executor.execute {
            try {
                val result = api(localHQ + "/api/devices", token = token)
                if (result.first !in 200..299) error(result.second.optString("error", "Could not load devices."))
                val devices = result.second.optJSONArray("devices") ?: JSONArray()
                runOnUiThread {
                    devicesDisplay.text = "TRUSTED DEVICES: ${devices.length()}"
                    devicesActions.removeAllViews()
                    for (i in 0 until devices.length()) {
                        val item = devices.getJSONObject(i)
                        devicesDisplay.append("\n" + item.optString("label") + " — " + item.optString("status") + " · " + item.optString("accessLevel", "worker"))
                        if (item.optString("status") != "revoked") {
                            val id = item.getString("deviceId")
                            val levels = arrayOf("worker", "viewer", "controller")
                            val labels = arrayOf("Compute worker", "Screen viewer", "Screen controller")
                            var selectedLevel = item.optString("accessLevel", "worker")
                            val selector = Spinner(this)
                            selector.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
                            selector.setSelection(levels.indexOf(selectedLevel).coerceAtLeast(0))
                            selector.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                                override fun onNothingSelected(parent: AdapterView<*>?) {}
                                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, itemId: Long) {
                                    val nextLevel = levels[position]
                                    if (nextLevel != selectedLevel) { selectedLevel = nextLevel; setWorkerAccessLevel(id, nextLevel) }
                                }
                            }
                            devicesActions.addView(TextView(this).apply { text = item.optString("label") + " permissions"; textSize = 13f; setPadding(0, 8, 0, 0) })
                            devicesActions.addView(selector, matchWidth())
                            devicesActions.addView(button("Revoke " + item.optString("label")) { revoke(id) })
                        }
                    }
                }
            } catch (e: Exception) { show(e.message ?: "Could not refresh devices.") }
        }
    }

    private fun revoke(id: String) {
        val token = adminTokenValue
        executor.execute {
            try {
                val result = api(localHQ + "/api/devices/revoke", "POST", JSONObject().put("deviceId", id), token)
                if (result.first !in 200..299) error(result.second.optString("error", "Revocation failed."))
                show("Device revoked. Its worker credential can no longer authenticate.")
            } catch (e: Exception) { show(e.message ?: "Revocation failed.") }
        }
    }

    private fun setWorkerAccessLevel(id: String, accessLevel: String) {
        val token = adminTokenValue
        if (token.isBlank()) { status.text = "Enter the HQ administrator token first."; return }
        executor.execute {
            try {
                val body = JSONObject().put("deviceId", id).put("accessLevel", accessLevel)
                val result = api(localHQ + "/api/devices/access-level", "POST", body, token)
                if (result.first !in 200..299) error(result.second.optString("error", "Could not change worker permissions."))
                show("Worker access updated to " + result.second.optString("accessLevel", accessLevel) + ".")
                refreshDevices()
            } catch (e: Exception) { show(e.message ?: "Could not change worker permissions.") }
        }
    }

    private fun queueComputeJob() {
        val token = adminTokenValue
        if (token.isBlank()) { status.text = "Enter the HQ administrator token first."; return }
        val workload = workloadInput.text.toString().trim()
        val payload = try { JSONObject(payloadInput.text.toString()) }
        catch (e: Exception) { status.text = "Job input is not valid JSON: ${e.message}"; return }
        if (workload.isBlank()) { status.text = "Enter an approved workload ID."; return }
        executor.execute {
            try {
                val body = JSONObject().put("workload", workload).put("payload", payload)
                val result = api(localHQ + "/api/jobs", "POST", body, token)
                if (result.first !in 200..299) error(result.second.optString("error", "Could not queue job."))
                runOnUiThread {
                    status.text = "Job queued successfully for an available worker."
                    jobsDisplay.text = "NEW JOB\n" + result.second.toString(2)
                }
                refreshComputeJobs()
            } catch (e: Exception) { show(e.message ?: "Could not queue compute job.") }
        }
    }

    private fun refreshComputeJobs() {
        val token = adminTokenValue
        if (token.isBlank()) { status.text = "Enter the HQ administrator token first."; return }
        executor.execute {
            try {
                val result = api(localHQ + "/api/jobs", token = token)
                if (result.first !in 200..299) error(result.second.optString("error", "Could not refresh jobs."))
                val workers = result.second.optJSONArray("workers") ?: JSONArray()
                val jobs = result.second.optJSONArray("jobs") ?: JSONArray()
                val text = StringBuilder()
                text.append("WORKERS ONLINE: ${workers.length()}\n")
                for (i in 0 until workers.length()) {
                    val worker = workers.getJSONObject(i)
                    text.append("• ${worker.optString("workerId")} — ${worker.optString("status")}\n")
                }
                text.append("\nJOBS: ${jobs.length()} · QUEUED: ${result.second.optInt("queued", 0)}\n")
                for (i in 0 until jobs.length()) {
                    val job = jobs.getJSONObject(i)
                    text.append("\n${job.optString("workload")} — ${job.optString("status")}\n")
                    text.append("ID: ${job.optString("jobId")}\n")
                    text.append("ASSIGNED WORKER: ${job.optString("assignedWorkerId", "not assigned")}\n")
                    val resultEnvelope = job.optJSONObject("result")
                    if (resultEnvelope != null) {
                        val metadata = resultEnvelope.optJSONObject("metadata")
                        if (metadata != null) {
                            text.append("EXECUTION: ${metadata.optString("execution", "unknown")}\n")
                            text.append("DEVICE: ${metadata.optString("device", "unknown")}\n")
                        }
                        val output = resultEnvelope.optJSONObject("output")
                        if (output != null) {
                            if (output.has("elapsedMs")) text.append("RUNTIME: ${"%.1f".format(output.optDouble("elapsedMs"))} ms\n")
                            text.append("RESULT: ${output.toString()}\n")
                        } else text.append("RESULT: ${resultEnvelope.toString()}\n")
                    }
                }
                runOnUiThread { jobsDisplay.text = text.toString() }
            } catch (e: Exception) { show(e.message ?: "Could not refresh compute jobs.") }
        }
    }

    private fun pairWorker() {
        val base = secureHqBase(endpoint.text.toString())
        val code = codeInput.text.toString().trim().uppercase()
        if (base.isBlank() || code.isBlank()) { status.text = "Enter the headquarters address and one-time code."; return }
        if (!base.startsWith("https://", ignoreCase = true)) {
            status.text = "Secure pairing requires Phone A's HTTPS address on port 8789."
            return
        }
        endpoint.setText(base)
        prefs.edit().putString("hq_url", base).apply()
        val id = prefs.getString("device_id", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("device_id", it).apply()
        }
        role.text = "PAIRING IN PROGRESS"
        status.text = "Sending pairing request to Phone A…"
        executor.execute {
            try {
                val publicKey = PairingKeyStore().getOrCreatePublicKey(id)
                val body = JSONObject().put("code", code).put("deviceId", id)
                    .put("label", "Genesis Android worker").put("publicKey", publicKey)
                val sent = api(base + "/api/pair/request", "POST", body)
                if (sent.first !in 200..299) {
                    error("Phone A rejected the pairing request (HTTP ${sent.first}): " +
                        sent.second.optString("error", "Unknown pairing error"))
                }
                val requestId = sent.second.getString("requestId")
                show("Pairing request sent. Leave Genesis open while Phone A approves it. Request ID: $requestId")
                val deadline = System.currentTimeMillis() + 5 * 60_000
                while (System.currentTimeMillis() < deadline) {
                    Thread.sleep(2000)
                    val poll = apiWithRetry(base + "/api/pair/result?requestId=" + requestId)
                    if (poll.first == 410) {
                        error("Phone A approved the device, but the one-time credential delivery expired before Phone B received it. Revoke this pending trusted entry on Phone A, then pair again.")
                    }
                    if (poll.first !in 200..299) {
                        error("Could not check approval status (HTTP ${poll.first}): " +
                            poll.second.optString("error", "Check the HQ connection and retry."))
                    }
                    when (poll.second.optString("status")) {
                        "approved" -> {
                            show("Phone A approved this device. Saving the worker credential securely…")
                            val encryptedToken = poll.second.optString("encryptedWorkerToken")
                            if (encryptedToken.isBlank()) error("Phone A approved the request but returned no encrypted worker credential.")
                            val workerToken = PairingKeyStore().decryptWorkerToken(id, encryptedToken)
                            if (workerToken.isBlank()) error("The encrypted worker credential could not be recovered on this device.")
                            SecureTokenStore(this).save(workerToken)
                            show("Credential saved. Verifying it directly with Phone A…")
                            val identity = apiWithRetry(base + "/api/worker/me", token = workerToken)
                            if (identity.first != 200) {
                                error("Credential was saved, but Phone A rejected its verification (HTTP ${identity.first}): " +
                                    identity.second.optString("error", "Unknown authentication error"))
                            }
                            val approvedDevice = identity.second.optJSONObject("device")
                                ?: error("Phone A authenticated the credential but returned no device identity.")
                            if (approvedDevice.optString("deviceId") != id || approvedDevice.optString("role") != "worker") {
                                error("Phone A authenticated a different device identity than this app. Do not start the worker; contact support.")
                            }
                            runOnUiThread {
                                role.text = "TRUSTED WORKER"
                                status.text = "PAIRING COMPLETE: Phone A approved this device, Android Keystore saved the credential, and Phone A independently verified it. You can now start background compute."
                            }
                            return@execute
                        }
                        "denied" -> error("The headquarters owner denied this request.")
                        "pending" -> show("Pairing request received. Waiting for you to approve it on Phone A…")
                    }
                }
                error("Approval timed out after 5 minutes. Check Phone A's pending requests, then generate a fresh code if needed.")
            } catch (e: Exception) {
                runOnUiThread {
                    role.text = "PAIRING NEEDS ATTENTION"
                    status.text = e.message ?: "Pairing failed for an unknown reason."
                }
            }
        }
    }


    private fun observationAccessibilityEnabled(): Boolean {
        val component = android.content.ComponentName(this, GenesisAccessibilityService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return enabled.split(":").any { it.equals(component, ignoreCase = true) }
    }

    private fun openObservationAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        status.text = "Enable Genesis in Accessibility settings, then return here to start a package-scoped session."
    }

    private fun startAndroidObservation() {
        val token = adminTokenValue
        val packageName = observationPackageInput.text.toString().trim()
        if (token.isBlank()) { status.text = "Enter the HQ administrator token first."; return }
        if (!packageName.matches(Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$"))) {
            status.text = "Enter a valid fully qualified Android package name."; return
        }
        if (!observationAccessibilityEnabled()) { openObservationAccessibilitySettings(); return }
        val sessionId = UUID.randomUUID().toString()
        prefs.edit().putString("observation_package", packageName).apply()
        executor.execute {
            try {
                val result = api(localHQ + "/api/observation/session", "POST",
                    JSONObject().put("sessionId", sessionId).put("packageName", packageName), token)
                if (result.first !in 200..299) error(result.second.optString("error", "Could not start observation session."))
                val scopedToken = result.second.optString("observationToken")
                if (scopedToken.isBlank()) error("HQ did not return a scoped observation token.")
                ObservationSessionState.configure(localHQ, sessionId, packageName, scopedToken)
                prefs.edit().putString("observation_session_id", sessionId).apply()
                runOnUiThread {
                    observationSummary.text = "OBSERVING: $packageName\nSession: $sessionId\nInteract with that app manually. Capture expires within one hour."
                    status.text = "Scoped Android observation is active. Only the selected package is captured."
                }
            } catch (e: Exception) { show(e.message ?: "Could not start observation.") }
        }
    }

    private fun stopAndroidObservation() {
        val token = adminTokenValue
        val sessionId = prefs.getString("observation_session_id", null)
        if (sessionId.isNullOrBlank()) {
            ObservationSessionState.clear()
            observationSummary.text = "No saved observation session."
            return
        }
        if (token.isBlank()) { status.text = "Enter the HQ administrator token to close the observation session."; return }
        executor.execute {
            try {
                val result = api(localHQ + "/api/observation/session/stop", "POST", JSONObject().put("sessionId", sessionId), token)
                if (result.first !in 200..299) error(result.second.optString("error", "Could not stop observation session."))
                ObservationSessionState.clear()
                runOnUiThread {
                    observationSummary.text = "Observation stopped. Session: $sessionId"
                    status.text = "Observation stopped. You can now build a practice simulator from the captured session."
                }
            } catch (e: Exception) { show(e.message ?: "Could not stop observation.") }
        }
    }

    private fun deleteAndroidObservationSession() {
        val token = adminTokenValue
        val sessionId = prefs.getString("observation_session_id", null)
        if (token.isBlank()) { status.text = "Enter the HQ administrator token to delete observation data."; return }
        if (sessionId.isNullOrBlank()) { status.text = "There is no saved observation session to delete."; return }
        AlertDialog.Builder(this)
            .setTitle("Delete captured observation data?")
            .setMessage("This permanently removes the selected session journal and any practice simulators derived from it. This cannot be undone.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                executor.execute {
                    try {
                        val result = api(localHQ + "/api/observation/session/delete", "POST", JSONObject().put("sessionId", sessionId), token)
                        if (result.first !in 200..299) error(result.second.optString("error", "Could not delete observation data."))
                        ObservationSessionState.clear()
                        prefs.edit().remove("observation_session_id").apply()
                        runOnUiThread {
                            observationSummary.text = "Captured observation data deleted for session $sessionId."
                            status.text = "The local observation journal and derived simulator artifacts were deleted."
                        }
                    } catch (e: Exception) { show(e.message ?: "Could not delete observation data.") }
                }
            }
            .show()
    }

    private fun buildAndroidPracticeSimulator() {
        val token = adminTokenValue
        val sessionId = prefs.getString("observation_session_id", null)
        if (token.isBlank()) { status.text = "Enter the HQ administrator token first."; return }
        if (sessionId.isNullOrBlank()) { status.text = "There is no observation session to build from."; return }
        executor.execute {
            try {
                val result = api(localHQ + "/api/observation/simulator", "POST", JSONObject().put("sessionId", sessionId), token)
                if (result.first !in 200..299) error(result.second.optString("error", "Simulator construction failed: " + result.second.optString("status")))
                runOnUiThread {
                    observationSummary.text = "PRACTICE SIMULATOR BUILT\nKind: ${result.second.optString("kind")}\nArtifact: ${result.second.optString("artifact")}\nEvidence: ${result.second.optJSONObject("evidence")}"
                    status.text = "Genesis built a candidate practice simulator from the recorded Android session and validated it against held-out observations."
                }
            } catch (e: Exception) { show(e.message ?: "Simulator construction failed.") }
        }
    }
    private fun reviseAndroidPracticeSimulator() {
        val token = adminTokenValue
        if (token.isBlank()) { status.text = "Enter the HQ administrator token first."; return }
        val request = try { JSONObject(revisionRequestInput.text.toString()) }
        catch (e: Exception) { status.text = "Revision request is not valid JSON: ${e.message}"; return }
        executor.execute {
            try {
                val result = api(localHQ + "/api/observation/revise", "POST", request, token)
                if (result.first !in 200..299) error(result.second.optString("error", result.second.optString("reason", "Simulator revision was rejected.")))
                runOnUiThread {
                    observationSummary.text = "REVISED PRACTICE SIMULATOR BUILT\nArtifact: ${result.second.optString("artifact")}\nEvidence: ${result.second.optJSONObject("evidence")}"
                    status.text = "Genesis revised a simulator using recorded counterexamples and an independent completed evaluation session."
                }
            } catch (e: Exception) { show(e.message ?: "Simulator revision failed.") }
        }
    }

    private fun startWorker() {
        val base = endpoint.text.toString().trim().trimEnd('/').ifBlank {
            prefs.getString("hq_url", "") ?: ""
        }
        if (base.isBlank()) { status.text = "Connect to Phone A first."; return }
        if (SecureTokenStore(this).get().isNullOrBlank()) {
            status.text = "This device has no saved worker credential. Pair it with Phone A first."
            return
        }
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 7301)
            status.text = "Allow notifications, then tap Start background compute worker again."
            return
        }
        prefs.edit().putString("hq_url", base).apply()
        val intent = Intent(this, WorkerRuntimeService::class.java)
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
            status.text = "Background worker starting. Keep its Genesis notification visible."
            role.text = "TRUSTED WORKER · STARTING"
        } catch (e: Exception) {
            status.text = "Could not start background worker: ${e.message}"
        }
    }

    private fun stopWorker() {
        stopService(Intent(this, WorkerRuntimeService::class.java))
        prefs.edit().putBoolean("worker_running", false).apply()
        status.text = "Background compute worker stopped."
        role.text = "TRUSTED WORKER"
    }

    override fun onResume() {
        super.onResume()
        if (::prefs.isInitialized && prefs.getBoolean("worker_running", false)) {
            status.text = "Background worker is marked as running. Check the Genesis notification for its current connection state."
        }
    }

    override fun onDestroy() {
        remotePollHandler.removeCallbacks(remotePollRunnable ?: Runnable {})
        remoteControlPollHandler.removeCallbacksAndMessages(null)
        activeRemoteSessionId = null
        activeRemoteViewerUi = null
        executor.shutdownNow()
        super.onDestroy()
    }
}
