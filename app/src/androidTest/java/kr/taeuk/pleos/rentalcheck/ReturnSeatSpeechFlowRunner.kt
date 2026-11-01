package kr.taeuk.pleos.rentalcheck

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Bundle
import kr.taeuk.pleos.rentalcheck.speech.SeatSpeechState
import kr.taeuk.pleos.rentalcheck.speech.SeatSpeechStatus
import org.json.JSONObject

/** Exercise genuine seat/window SDK reads and Pleos TTS through the production return controller in private preferences. */
class ReturnSeatSpeechFlowRunner : Instrumentation() {
    private lateinit var controller: InspectionController
    private var prefix = "tts_test"
    private val checks = JSONObject()

    // Accept only an explicit test namespace so the normal installed account, settings and completed rental remain untouched.
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        prefix = arguments?.getString("prefix") ?: prefix
        require(prefix.matches(Regex("tts_[a-z0-9_]{1,40}")))
        start()
    }

    // Drive normal account/pickup/return events while the host changes only the AVD's published simulator signals.
    override fun onStart() {
        val result = Bundle(); var code = Activity.RESULT_OK
        val isolated = object : ContextWrapper(targetContext) {
            // Keep stores that ask for applicationContext inside this diagnostic namespace.
            override fun getApplicationContext(): Context = this
            // Scope preferences only; package identity and genuine service permissions still belong to the installed production app.
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = targetContext.getSharedPreferences(prefix + "_" + name, mode)
        }
        try {
            runOnMainSync { controller = InspectionController(isolated); controller.start() }; idle()
            check(state().record == null && state().emulatorTestAvailable)
            send(InspectionEvent.SetEmulatorTestMode(true))
            val file = targetContext.filesDir.resolve("$prefix-config.json"); val config = JSONObject(file.readText())
            send(InspectionEvent.ConfigureServer(config.getString("serverUrl"), config.getString("deviceToken")))
            check(file.delete()); send(InspectionEvent.CreateAccountQr)
            stage("PAIRING", JSONObject().put("pairingUrl", state().pairing!!.pairingUrl))
            until("account approval") { it.pairedBooking != null }
            send(InspectionEvent.ContinueAfterConnection); send(InspectionEvent.CapturePickup)
            check(state().stage == InspectionStage.ACTIVE)
            send(InspectionEvent.BeginReturn)
            check(state().returnOccupiedSeats.isEmpty() && state().returnSeatSpeech.requests == 0)
            checks.put("vacantSeatsSilent", true)

            stage("OPEN_WINDOW_OCCUPIED")
            freshUntil("occupied seat and open window") {
                it.current?.find("seat.ROW_2_RIGHT.occupancy")?.value == "true" && it.current.find("window.ROW_2_RIGHT.closed")?.value == "false"
            }
            send(InspectionEvent.CheckWindowManually(true)); send(InspectionEvent.CaptureReturn)
            check(state().returnWindowDialog?.openWindows?.isNotEmpty() == true && state().record?.returned == null)
            check(state().returnSeatSpeech.requests == 0)
            checks.put("windowGuardPrecedesVoice", true)

            stage("CLOSE_WINDOW")
            freshUntil("closed window") { it.current?.find("window.ROW_2_RIGHT.closed")?.value == "true" }
            until("first actual playback start") { it.returnSeatSpeech.status == SeatSpeechStatus.SPEAKING }
            stage("FIRST_STARTED")
            until("first actual playback completion") { it.returnSeatSpeech.status == SeatSpeechStatus.DONE }
            val first = state().returnSeatSpeech
            check(first.requests == 1 && first.starts == 1 && first.completions == 1 && first.text.contains("뒤쪽 오른쪽"))
            checks.put("automaticPleosPlayback", true)
            repeat(2) { send(InspectionEvent.Refresh) }
            check(state().returnSeatSpeech.requests == 1)
            checks.put("refreshDoesNotRepeat", true)

            stage("CHANGE_SEATS")
            freshUntil("new occupied seat") {
                it.current?.find("seat.ROW_1_RIGHT.occupancy")?.value == "true" && it.current.find("seat.ROW_2_RIGHT.occupancy")?.value == "false"
            }
            until("changed-seat playback completion") { it.returnSeatSpeech.status == SeatSpeechStatus.DONE && it.returnSeatSpeech.completions == 2 }
            check(state().returnSeatSpeech.text.contains("앞쪽 오른쪽") && !state().returnSeatSpeech.text.contains("뒤쪽 오른쪽"))
            checks.put("changedSeatAnnounced", true)
            send(InspectionEvent.CaptureReturn)
            check(state().stage == InspectionStage.EVIDENCE && state().record?.returned != null)
            check(state().record?.revision == state().record?.syncedRevision && state().returnSeatSpeech.requests == 2)
            checks.put("returnSavedWithoutDuplicateVoice", true)

            send(InspectionEvent.ReplayReturnSeatSpeech)
            until("explicit replay starts") { it.returnSeatSpeech.status == SeatSpeechStatus.SPEAKING }
            send(InspectionEvent.StopReturnSeatSpeech)
            check(state().returnSeatSpeech.status == SeatSpeechStatus.STOPPED && state().stage == InspectionStage.EVIDENCE)
            checks.put("manualStopWorks", true)
            send(InspectionEvent.ReplayReturnSeatSpeech)
            until("explicit replay completes") { it.returnSeatSpeech.status == SeatSpeechStatus.DONE && it.returnSeatSpeech.completions == 3 }
            checks.put("manualReplayWorks", true)
            val beforeStop = state().returnSeatSpeech
            send(InspectionEvent.ReplayReturnSeatSpeech)
            runOnMainSync { controller.stop() }
            Thread.sleep(1_500)
            check(state().returnSeatSpeech.starts == beforeStop.starts && state().returnSeatSpeech.completions == beforeStop.completions)
            runOnMainSync { controller.start() }; idle()
            check(state().returnSeatSpeech.requests == beforeStop.requests + 1 && state().returnSeatSpeech.status == SeatSpeechStatus.STOPPED)
            checks.put("backgroundCancelsQueuedVoice", true)
            val requests = state().returnSeatSpeech.requests
            send(InspectionEvent.DismissReturnSeatNotice); send(InspectionEvent.ReplayReturnSeatSpeech)
            check(state().returnOccupiedSeats.isEmpty() && state().returnSeatSpeech.requests == requests)
            checks.put("dismissedNoticeCannotReplay", true)
            result.putString("ttsFlow", JSONObject().put("checks", checks).put("first", encode(first))
                .put("final", encode(state().returnSeatSpeech)).put("stage", state().stage.name)
                .put("recordId", state().record!!.id).put("mode", "ON_DEVICE").put("locale", "ko-KR").toString())
        } catch (error: Exception) {
            code = Activity.RESULT_CANCELED; result.putString("ttsFlowError", error.stackTraceToString())
            if (::controller.isInitialized) result.putString("ttsLastState", encode(state().returnSeatSpeech).toString())
        } finally { if (::controller.isInitialized) runOnMainSync { controller.dispose() } }
        finish(code, result)
    }

    // Report only generic reminder text and callback diagnostics; no account tokens or VIN are included in public results.
    private fun encode(s: SeatSpeechState): JSONObject = JSONObject().put("status", s.status.name).put("text", s.text)
        .put("requests", s.requests).put("starts", s.starts).put("completions", s.completions).put("peakRms", s.peakRms)
        .put("error", s.error ?: JSONObject.NULL)

    // Serialize state reads with the production controller and SDK callback dispatcher.
    private fun state(): InspectionUiState { lateinit var value: InspectionUiState; runOnMainSync { value = controller.state }; return value }

    // Honor the real event guards and wait for storage/server operations before asserting results.
    private fun send(event: InspectionEvent) { idle(); runOnMainSync { controller.dispatch(event) }; idle() }

    // Bound controller work independently from audio completion so a slow SDK read cannot be mistaken for a successful test step.
    private fun idle() { until("idle") { !it.busy && !it.cloudBusy && !it.accountBusy } }

    // Publish stage markers only to the private host driver so controls and playback capture begin at deterministic points.
    private fun stage(name: String, details: JSONObject = JSONObject()) { sendStatus(1, Bundle().apply { putString("ttsStage", details.put("name", name).toString()) }) }

    // Re-query real vehicle data after each Console transition instead of satisfying conditions from a cached or simulated app value.
    private fun freshUntil(label: String, condition: (InspectionUiState) -> Boolean) {
        val deadline = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < deadline) { send(InspectionEvent.Refresh); if (condition(state())) return; Thread.sleep(150) }
        error("No fresh SDK confirmation: $label")
    }

    // Fail explicitly on service errors or missing playback callbacks; elapsed time is never treated as successful speech.
    private fun until(label: String, condition: (InspectionUiState) -> Boolean) {
        val deadline = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < deadline) {
            val s = state(); check(s.cloudError == null) { s.cloudError.orEmpty() }
            check(s.returnSeatSpeech.status != SeatSpeechStatus.FAILED) { "Pleos TTS failed: ${s.returnSeatSpeech.error}" }
            if (condition(s)) return
            Thread.sleep(50)
        }
        error("Timed out: $label; ${encode(state().returnSeatSpeech)}")
    }
}
