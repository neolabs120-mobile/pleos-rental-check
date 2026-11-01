package kr.taeuk.pleos.rentalcheck

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelProvider
import kr.taeuk.pleos.rentalcheck.ui.RentalCheckScreen
import kr.taeuk.pleos.rentalcheck.ui.AccountConnectionScreen
import kr.taeuk.pleos.rentalcheck.ui.RentalCheckTheme
import org.json.JSONObject

/** Drive the real paired-rental controller and SDKs in isolated preferences, rendering its actual state rather than fixture vehicle/location data. */
class ReturnNavigationFlowRunner : Instrumentation() {
    private lateinit var controller: InspectionController
    private var prefix = "nav_test"

    // Require an explicit diagnostic namespace and consent to the labelled emulator destination; no entry point is present in a Release APK.
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        prefix = arguments?.getString("prefix") ?: prefix
        require(prefix.matches(Regex("nav_[a-z0-9_]{1,40}")) && arguments?.getString("allowTestDestination") == "true")
        start()
    }

    // Coordinate normal pairing, pickup, Navigation and return events while the host supplies only public simulator signals and API owner approval.
    override fun onStart() {
        val result = Bundle(); var code = Activity.RESULT_OK; var activity: MainActivity? = null
        var observer: LifecycleEventObserver? = null
        val isolated = object : ContextWrapper(targetContext) {
            // Keep production stores that ask for applicationContext inside the test namespace.
            override fun getApplicationContext(): Context = this
            // Preserve every existing installed preference file while retaining the real app package's SDK identity and permissions.
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = targetContext.getSharedPreferences(prefix + "_" + name, mode)
        }
        try {
            val configFile = targetContext.filesDir.resolve("$prefix-config.json"); val config = JSONObject(configFile.readText()); check(configFile.delete())
            val screen = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            activity = screen
            val live = mutableStateOf(InspectionUiState())
            runOnMainSync {
                ViewModelProvider(screen)[InspectionViewModel::class.java].stop()
                controller = InspectionController(isolated)
                controller.onChanged = { live.value = it }; controller.start()
                screen.setContent { RentalCheckTheme {
                    if (live.value.pairedBooking == null && !live.value.resultVisible) AccountConnectionScreen(live.value, controller::dispatch)
                    else RentalCheckScreen(live.value, controller::dispatch)
                } }
                observer = LifecycleEventObserver { _, event ->
                    // Follow the genuine Activity foreground lifecycle with the isolated production controller during a real Navigation screen launch.
                    if (event == Lifecycle.Event.ON_STOP) controller.stop()
                    if (event == Lifecycle.Event.ON_START) {
                        ViewModelProvider(screen)[InspectionViewModel::class.java].stop()
                        controller.onChanged = { live.value = it }; controller.start()
                    }
                }.also { screen.lifecycle.addObserver(it) }
            }
            idle(); check(state().record == null && state().emulatorTestAvailable)
            send(InspectionEvent.SetEmulatorTestMode(true))
            send(InspectionEvent.ConfigureServer(config.getString("serverUrl"), config.getString("deviceToken")))
            send(InspectionEvent.CreateAccountQr); snapshot(0)
            val file = targetContext.filesDir.resolve("$prefix-command.json")
            var sequence = 0; val deadline = System.currentTimeMillis() + 12 * 60_000
            while (System.currentTimeMillis() < deadline) {
                if (!file.exists()) { Thread.sleep(100); continue }
                val command = JSONObject(file.readText())
                if (command.getInt("sequence") <= sequence) { Thread.sleep(100); continue }
                sequence = command.getInt("sequence")
                when (command.getString("action")) {
                    "connection" -> send(InspectionEvent.CheckAccountConnection)
                    "continue" -> send(InspectionEvent.ContinueAfterConnection)
                    "pickup" -> send(InspectionEvent.CapturePickup)
                    "directions" -> send(InspectionEvent.RequestReturnDirections(checkNotNull(state().pairedBooking?.returnLocation)))
                    "retry-location" -> send(InspectionEvent.RetryReturnLocation)
                    "open-navigation" -> send(InspectionEvent.OpenReturnNavigation)
                    "begin-return" -> send(InspectionEvent.BeginReturn)
                    "dismiss-seats" -> send(InspectionEvent.DismissReturnSeatNotice)
                    "save-return" -> send(InspectionEvent.CaptureReturn)
                    "refresh" -> send(InspectionEvent.Refresh)
                    "stop" -> runOnMainSync { controller.stop() }
                    "resume" -> runOnMainSync { controller.onChanged = { live.value = it }; controller.start() }
                    "foreground" -> runOnMainSync { screen.startActivity(Intent(screen, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)) }
                    "state" -> Unit
                    "finish" -> { snapshot(sequence); break }
                    else -> error("Unknown navigation test action")
                }
                idle(); snapshot(sequence)
            }
            result.putString("returnNavigationFlow", "Actual production controller, guarded events and real SDK services in private test preferences")
        } catch (error: Throwable) { code = Activity.RESULT_CANCELED; result.putString("returnNavigationFlowError", error.stackTraceToString()) }
        finally { runOnMainSync {
            observer?.let { activity?.lifecycle?.removeObserver(it) }
            if (::controller.isInitialized) controller.dispose()
        } }
        finish(code, result)
    }

    // Read the same main-thread state used by the composables to avoid racing Binder or cloud updates in assertions.
    private fun state(): InspectionUiState { lateinit var value: InspectionUiState; runOnMainSync { value = controller.state }; return value }

    // Wait for inspection/account IO only; the host separately verifies asynchronous location and Navigation outcomes.
    private fun idle() {
        val deadline = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < deadline) {
            val current = state()
            check(current.cloudError == null) { current.cloudError.orEmpty() }
            if (!current.busy && !current.cloudBusy && !current.accountBusy) return
            Thread.sleep(50)
        }
        error("Production operation timed out")
    }

    // Exercise exactly the guarded event handlers behind normal renter controls; no vehicle or location result is injected.
    private fun send(event: InspectionEvent) { idle(); runOnMainSync { controller.dispatch(event) }; idle() }

    // Keep credentials and simulator coordinates in private host evidence, alongside screenshots of genuine SDK-derived state.
    private fun snapshot(sequence: Int) {
        val current = state(); val guide = current.returnGuide
        val json = JSONObject().put("sequence", sequence).put("stage", current.stage.name)
            .put("pairingUrl", current.pairing?.pairingUrl ?: JSONObject.NULL).put("paired", current.pairedBooking != null)
            .put("recordId", current.record?.id ?: JSONObject.NULL).put("recordRevision", current.record?.revision ?: 0)
            .put("syncedRevision", current.record?.syncedRevision ?: 0).put("returned", current.record?.returned != null)
            .put("destinationName", current.pairedBooking?.returnLocation?.name ?: JSONObject.NULL)
            .put("destinationIsTest", current.pairedBooking?.returnLocation?.isTest ?: false)
            .put("locationPermissionRequired", guide.locationPermissionRequired).put("distanceMeters", guide.distanceMeters ?: JSONObject.NULL)
            .put("latitude", guide.fix?.latitude ?: JSONObject.NULL).put("longitude", guide.fix?.longitude ?: JSONObject.NULL)
            .put("locationTimestamp", guide.fix?.timestampMillis ?: JSONObject.NULL).put("locationMessage", guide.locationMessage)
            .put("navigationConnected", guide.navigation.connected).put("navigationBusy", guide.navigation.busy)
            .put("navigationConfirmed", guide.navigation.confirmed).put("navigationMessage", guide.navigation.message)
            .put("errorMessage", current.errorMessage ?: JSONObject.NULL).put("gear", current.current?.find("gear.selection")?.value ?: JSONObject.NULL)
        waitForIdleSync(); Thread.sleep(150)
        val bitmap = checkNotNull(uiAutomation.takeScreenshot())
        targetContext.filesDir.resolve("$prefix-$sequence.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle(); targetContext.filesDir.resolve("$prefix-state.json").writeText(json.toString())
    }
}
