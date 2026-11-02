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
import kr.taeuk.pleos.rentalcheck.speech.PleosLuggageRecognition
import kr.taeuk.pleos.rentalcheck.model.StorageSpace
import kr.taeuk.pleos.rentalcheck.data.UsageMemoryCodec
import kotlinx.coroutines.*

/** Drive the real paired-rental controller and SDKs in isolated preferences, rendering its actual state rather than fixture vehicle/location data. */
class VoiceArrivalFlowRunner : Instrumentation() {
    private lateinit var controller: InspectionController
    private var activeScreen: DebugInspectionActivity? = null
    private var prefix = "voice_test"
    private val recognitionScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // Require an explicit diagnostic namespace and consent to the labelled emulator destination; no entry point is present in a Release APK.
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        prefix = arguments?.getString("prefix") ?: prefix
        require(prefix.matches(Regex("voice_[a-z0-9_]{1,40}")) && arguments?.getString("allowTestDestination") == "true")
        start()
    }

    // Coordinate normal pairing, pickup, Navigation and return events while the host supplies only public simulator signals and API owner approval.
    override fun onStart() {
        uiAutomation.serviceInfo = uiAutomation.serviceInfo.apply { flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        val result = Bundle(); var code = Activity.RESULT_OK; var activity: DebugInspectionActivity? = null
        var callbacks: android.app.Application.ActivityLifecycleCallbacks? = null
        val isolated = object : ContextWrapper(targetContext) {
            // Keep production stores that ask for applicationContext inside the test namespace.
            override fun getApplicationContext(): Context = this
            // Preserve every existing installed preference file while retaining the real app package's SDK identity and permissions.
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = targetContext.getSharedPreferences(prefix + "_" + name, mode)
        }
        try {
            val configFile = targetContext.filesDir.resolve("$prefix-config.json"); val config = JSONObject(configFile.readText()); check(configFile.delete())
            val screen = startActivitySync(Intent(targetContext, DebugInspectionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as DebugInspectionActivity
            activity = screen; activeScreen = screen
            val live = mutableStateOf(InspectionUiState())
            runOnMainSync {
                controller = createController(isolated)
                controller.onChanged = { live.value = it }; controller.start()
                screen.setContent { RentalCheckTheme {
                    if (live.value.pairedBooking == null && !live.value.resultVisible) AccountConnectionScreen(live.value, controller::dispatch)
                    else RentalCheckScreen(live.value, controller::dispatch)
                } }
            }
            val application = targetContext.applicationContext as android.app.Application
            callbacks = object : android.app.Application.ActivityLifecycleCallbacks {
                // A Car TaskView may recreate DebugInspectionActivity during navigation initialization; bind the actual resumed instance to the same real controller.
                override fun onActivityResumed(candidate: Activity) {
                    if (candidate !is DebugInspectionActivity) return
                    activeScreen = candidate
                    controller.onChanged = { live.value = it }; controller.start()
                    candidate.setContent { RentalCheckTheme {
                        if (live.value.pairedBooking == null && !live.value.resultVisible) AccountConnectionScreen(live.value, controller::dispatch)
                        else RentalCheckScreen(live.value, controller::dispatch)
                    } }
                }
                // Stop only the active instance so an old Activity's late stop cannot silence a newer resumed screen.
                override fun onActivityStopped(candidate: Activity) { if (candidate === activeScreen) controller.stop() }
                // Creation is observed at resume after the app's own initialization has finished.
                override fun onActivityCreated(candidate: Activity, state: Bundle?) = Unit
                // The production controller begins at resume, after the app's ViewModel has run onStart.
                override fun onActivityStarted(candidate: Activity) = Unit
                // Match the app's normal onStop boundary rather than creating a second pause-only policy.
                override fun onActivityPaused(candidate: Activity) = Unit
                // Persistent rental storage remains owned by the real controller, not Activity state bundles.
                override fun onActivitySaveInstanceState(candidate: Activity, out: Bundle) = Unit
                // Retire the reference only for the destroyed current instance; a replacement may already be active.
                override fun onActivityDestroyed(candidate: Activity) { if (candidate === activeScreen) activeScreen = null }
            }.also { application.registerActivityLifecycleCallbacks(it) }
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
                    "foreground" -> runOnMainSync {
                        val target = activeScreen ?: screen
                        target.startActivity(Intent(target, DebugInspectionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                        target.setContent { RentalCheckTheme {
                            if (live.value.pairedBooking == null && !live.value.resultVisible) AccountConnectionScreen(live.value, controller::dispatch)
                            else RentalCheckScreen(live.value, controller::dispatch)
                        } }
                    }
                    "collect-trunk" -> send(InspectionEvent.CollectLuggage(StorageSpace.TRUNK, true))
                    "charge-directions" -> send(InspectionEvent.RequestChargeDirections(checkNotNull(state().pairedBooking?.returnLocation?.chargingStops?.firstOrNull())))
                    "show-parking" -> tapParking()
                    "close-parking" -> tapScreen(1920f, 965f)

                    "open-memo" -> send(InspectionEvent.OpenVoiceMemo())
                    "listen" -> send(InspectionEvent.StartVoiceRecognition)
                    "edit-memo" -> send(InspectionEvent.EditVoiceMemo(StorageSpace.valueOf(command.getString("space")), command.getString("item")))
                    "save-memo" -> send(InspectionEvent.ConfirmVoiceMemo)
                    "cancel-memo" -> send(InspectionEvent.CloseVoiceMemo)
                    "begin-arrival" -> send(InspectionEvent.BeginArrivalReturn)
                    "dismiss-arrival" -> send(InspectionEvent.DismissArrivalPrompt)
                    "reopen-controller" -> runOnMainSync {
                        controller.dispose(); controller = createController(isolated)
                        controller.onChanged = { live.value = it }; controller.start()
                    }
                    "state" -> Unit
                    "finish" -> { snapshot(sequence); break }
                    else -> error("Unknown navigation test action")
                }
                idle(); snapshot(sequence)
            }
            result.putString("returnNavigationFlow", "Actual production controller, guarded events and real SDK services in private test preferences")
        } catch (error: Throwable) { code = Activity.RESULT_CANCELED; result.putString("returnNavigationFlowError", error.stackTraceToString()) }
        finally { runOnMainSync {
            callbacks?.let { (targetContext.applicationContext as android.app.Application).unregisterActivityLifecycleCallbacks(it) }
            if (::controller.isInitialized) controller.dispose()
            recognitionScope.cancel()
        } }
        finish(code, result)
    }

    // Supply generated Korean PCM through the documented ON_DEVICE audio API; no host microphone or invented recognition result is used.
    private fun createController(context: Context): InspectionController {
        val real = PleosLuggageRecognition(context, recognitionScope) { stt ->
            val pcm = targetContext.filesDir.resolve("$prefix-audio.pcm").readBytes()
            stt.sendAudio(pcm); stt.completeAudioSend()
        }
        val traced = object : kr.taeuk.pleos.rentalcheck.speech.LuggageRecognitionEngine {
            // Retain ordered real service events privately to distinguish final text from a partial hypothesis or capture-end notification.
            override fun start(receive: (kr.taeuk.pleos.rentalcheck.speech.RecognitionEvent) -> Unit) = real.start {
                targetContext.filesDir.resolve("$prefix-recognition.log").appendText(it.toString() + "\n"); receive(it)
            }
            // Delegate cancellation unchanged so event tracing cannot alter the production adapter's session lifetime.
            override fun stop() = real.stop()
        }
        val navigation = kr.taeuk.pleos.rentalcheck.navigation.PleosReturnGuideEngine(context, recognitionScope) { message ->
            targetContext.filesDir.resolve("$prefix-navigation.log").appendText(message + "\n")
        }
        return InspectionController(context, traced, navigation)
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

    // Touch the parking-details control at coordinates reviewed in the actual fixed-size test AVD screenshot; never assign the dialog's remembered state.
    private fun tapParking() {
        waitForIdleSync(); Thread.sleep(150)
        val picture = checkNotNull(uiAutomation.takeScreenshot())
        check(picture.width == 2560 && picture.height >= 1400) { "Unreviewed test display dimensions" }; picture.recycle()
        tapScreen(1325f, 606f)
    }

    // Exercise the actual fixed-display confirmation control without a global Back event that could close the surrounding AAOS task.
    private fun tapScreen(x: Float, y: Float) {
        val now = android.os.SystemClock.uptimeMillis()
        for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
            val event = android.view.MotionEvent.obtain(now, android.os.SystemClock.uptimeMillis(), action, x, y, 0)
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            check(uiAutomation.injectInputEvent(event, true)); event.recycle()
        }
    }

    // Inspect the genuine accessibility text of rendered windows so a background controller or another DebugInspectionActivity cannot pass visual verification.
    private fun screenTexts(): String = buildString {
        // Walk only current Android UI nodes; no inspection state or expected text is injected into the screen evidence.
        fun visit(node: android.view.accessibility.AccessibilityNodeInfo?) {
            if (node == null) return
            node.text?.let { append(it).append('\n') }
            for (index in 0 until node.childCount) visit(node.getChild(index))
        }
        visit(uiAutomation.rootInActiveWindow)
        uiAutomation.windows.forEach { visit(it.root) }
    }

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
            .put("stationary", InspectionController::class.java.getDeclaredField("stationarySnapshot").apply { isAccessible = true }.get(controller)?.toString() ?: JSONObject.NULL)
            .put("foreground", InspectionController::class.java.getDeclaredField("foreground").apply { isAccessible = true }.get(controller))
            .put("batteryTarget", kr.taeuk.pleos.rentalcheck.model.BatteryReturn.from(current.record?.pickup, current.current).target ?: JSONObject.NULL)
            .put("batteryCurrent", kr.taeuk.pleos.rentalcheck.model.BatteryReturn.from(current.record?.pickup, current.current).current ?: JSONObject.NULL)
            .put("batteryShortfall", kr.taeuk.pleos.rentalcheck.model.BatteryReturn.from(current.record?.pickup, current.current).shortfall ?: JSONObject.NULL)
            .put("voicePhase", current.voiceMemo.phase.name).put("voiceProblem", current.voiceMemo.problem ?: JSONObject.NULL)
            .put("voiceTranscript", current.voiceMemo.draft.transcript).put("voiceItem", current.voiceMemo.draft.item)
            .put("voiceSpace", current.voiceMemo.draft.space?.name ?: JSONObject.NULL)
            .put("memory", UsageMemoryCodec.encode(current.assistance.memory))
            .put("arrivalPrompt", current.arrivalPrompt).put("arrivalSpeechStarts", current.arrivalSpeech.starts)
            .put("arrivalSpeechDone", current.arrivalSpeech.completions).put("arrivalSpeechStatus", current.arrivalSpeech.status.name)
            .put("memoNotice", current.returnMemoNotice).put("windowDialog", current.returnWindowDialog != null)
            .put("returnSpeechText", current.returnSeatSpeech.text).put("returnSpeechStarts", current.returnSeatSpeech.starts)
            .put("returnSpeechDone", current.returnSeatSpeech.completions)
        waitForIdleSync(); Thread.sleep(150)
        json.put("screenTexts", screenTexts())
        val bitmap = checkNotNull(uiAutomation.takeScreenshot())
        targetContext.filesDir.resolve("$prefix-$sequence.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle(); targetContext.filesDir.resolve("$prefix-state.json").writeText(json.toString())
    }
}
