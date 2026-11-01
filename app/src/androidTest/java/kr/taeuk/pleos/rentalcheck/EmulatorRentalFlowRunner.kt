package kr.taeuk.pleos.rentalcheck

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import kr.taeuk.pleos.rentalcheck.data.InspectionStore
import kr.taeuk.pleos.rentalcheck.model.VehicleIdentity
import kr.taeuk.pleos.rentalcheck.vehicle.EMULATOR_TEST_SOURCE
import org.json.JSONArray
import org.json.JSONObject

class EmulatorRentalFlowRunner : Instrumentation() {
    private var action = "setup"
    private lateinit var controller: InspectionController

    override fun onCreate(arguments: Bundle?) {
        // Run a device-side integration scenario against the production controller without adding automation entry points to the app.
        super.onCreate(arguments)
        action = arguments?.getString("action") ?: action
        start()
    }

    override fun onStart() {
        // Each phase reuses the persisted rental and genuine SDK readings; server credentials are read once from an app-private test file.
        val result = Bundle()
        var code = Activity.RESULT_OK
        try {
            runOnMainSync { controller = InspectionController(targetContext); controller.start() }
            idle()
            when (action) {
                "prepare-pickup" -> {
                    // Prepare the user's next manual voice trial through normal ownership/storage events, replacing only an already submitted local rental.
                    check(state().record == null || state().record?.submittedAtMillis != null) { "An unfinished rental must be preserved." }
                    if (state().record != null || state().pairedBooking != null || state().pairing != null) send(InspectionEvent.DisconnectAccount)
                    check(state().record == null && state().emulatorTestAvailable)
                    if (!state().emulatorTestEnabled) send(InspectionEvent.SetEmulatorTestMode(true))
                    val file = targetContext.filesDir.resolve("emulator-flow-config.json"); val config = JSONObject(file.readText())
                    send(InspectionEvent.ConfigureServer(config.getString("serverUrl"), config.getString("deviceToken")))
                    check(file.delete()); send(InspectionEvent.CreateAccountQr)
                    sendStatus(1, Bundle().apply { putString("manualPickupPairing", state().pairing!!.pairingUrl) })
                    paired(); send(InspectionEvent.ContinueAfterConnection); send(InspectionEvent.CapturePickup)
                    check(state().stage == InspectionStage.ACTIVE && state().record?.returned == null)
                    check(state().record?.revision == state().record?.syncedRevision && state().returnSeatSpeech.requests == 0)
                    // Flush pending apply() writes before instrumentation exits, so the real Activity resumes the same approved pairing and pickup.
                    targetContext.dataDir.resolve("shared_prefs").listFiles().orEmpty().filter { it.extension == "xml" }.forEach {
                        check(targetContext.getSharedPreferences(it.nameWithoutExtension, android.content.Context.MODE_PRIVATE).edit().commit())
                    }
                }
                "setup" -> {
                    check(state().record == null) { "An existing rental must be preserved; disconnect it before this test." }
                    check(state().emulatorTestAvailable)
                    send(InspectionEvent.SetEmulatorTestMode(true))
                    check(state().emulatorTestEnabled && state().current?.source == EMULATOR_TEST_SOURCE)
                    val file = targetContext.filesDir.resolve("emulator-flow-config.json")
                    val config = JSONObject(file.readText())
                    send(InspectionEvent.ConfigureServer(config.getString("serverUrl"), config.getString("deviceToken")))
                    check(file.delete())
                    check(state().serverConfigured)
                    send(InspectionEvent.CreateAccountQr)
                    check(state().pairing != null) { state().accountMessage }
                }
                "pickup" -> {
                    paired()
                    send(InspectionEvent.ContinueAfterConnection)
                    check(state().stage == InspectionStage.PICKUP)
                    send(InspectionEvent.CapturePickup)
                    check(state().stage == InspectionStage.ACTIVE && state().record?.syncedRevision == state().record?.revision)
                    send(InspectionEvent.SetEmulatorTestMode(false))
                    check(state().emulatorTestEnabled) { "Identity changed during an active rental." }
                }
                "return-open" -> {
                    paired()
                    send(InspectionEvent.BeginReturn)
                    check(state().returnWindowDialog?.openWindows?.contains("뒤쪽 오른쪽 창문") == true)
                    send(InspectionEvent.CaptureReturn)
                    check(state().record?.returned == null)
                    send(InspectionEvent.CheckWindowManually(true))
                    send(InspectionEvent.CaptureReturn)
                    check(state().record?.returned == null && state().returnWindowDialog?.openWindows?.isNotEmpty() == true)
                }
                "return-closed" -> {
                    paired()
                    send(InspectionEvent.BeginReturn)
                    check(state().returnWindowDialog == null)
                    send(InspectionEvent.CaptureReturn)
                    check(state().stage == InspectionStage.EVIDENCE && state().record?.returned != null)
                    check(state().record?.syncedRevision == state().record?.revision)
                }
                else -> error("Unknown integration phase")
            }
            val current = state()
            val record = current.record
            val report = JSONObject().put("action", action).put("stage", current.stage.name)
                .put("testModeAvailable", current.emulatorTestAvailable).put("testModeEnabled", current.emulatorTestEnabled)
                .put("vin", VehicleIdentity.vin(current.current)).put("source", current.current?.source)
                .put("recordId", record?.id ?: JSONObject.NULL).put("revision", record?.revision ?: 0)
                .put("syncedRevision", record?.syncedRevision ?: 0).put("hasReturn", record?.returned != null)
                .put("pairingUrl", current.pairing?.pairingUrl ?: JSONObject.NULL)
                .put("openWindows", JSONArray(current.returnWindowDialog?.openWindows.orEmpty()))
                .put("snapshot", current.current?.let { InspectionStore(targetContext).encode(it) } ?: JSONObject.NULL)
            result.putString("emulatorFlow", report.toString())
        } catch (error: Exception) {
            code = Activity.RESULT_CANCELED
            result.putString("emulatorFlowError", error.stackTraceToString())
        } finally {
            if (::controller.isInitialized) runOnMainSync { controller.dispose() }
        }
        finish(code, result)
    }

    private fun state(): InspectionUiState {
        // Read controller state on its owning thread so asynchronous SDK and server callbacks cannot race with assertions.
        lateinit var value: InspectionUiState
        runOnMainSync { value = controller.state }
        return value
    }

    private fun idle(): InspectionUiState {
        // Bound every integration wait and include the real controller error on failure instead of treating a timeout as success.
        val until = System.currentTimeMillis() + 45_000
        while (System.currentTimeMillis() < until) {
            val current = state()
            if (!current.busy && !current.cloudBusy && !current.accountBusy) {
                check(current.cloudError == null) { current.cloudError.orEmpty() }
                return current
            }
            Thread.sleep(100)
        }
        error("Timed out waiting for controller: ${state().message}; ${state().cloudError}")
    }

    private fun send(event: InspectionEvent) {
        // Exercise the same guarded events used by Compose, including fresh SDK reads and normal server ownership checks.
        idle()
        runOnMainSync { controller.dispatch(event) }
        idle()
    }

    private fun paired() {
        // Await an explicit phone-side approval; a test VIN or test booking alone must never grant rental ownership.
        val until = System.currentTimeMillis() + 45_000
        while (System.currentTimeMillis() < until) {
            idle()
            if (state().pairedBooking != null) return
            send(InspectionEvent.CheckAccountConnection)
            Thread.sleep(100)
        }
        error("Owner approval missing: ${state().accountMessage}")
    }
}
