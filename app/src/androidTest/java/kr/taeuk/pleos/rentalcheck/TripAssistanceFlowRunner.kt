package kr.taeuk.pleos.rentalcheck

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Bundle
import kr.taeuk.pleos.rentalcheck.data.InspectionStore
import kr.taeuk.pleos.rentalcheck.data.UsageMemoryCodec
import kr.taeuk.pleos.rentalcheck.model.PracticeStep
import kr.taeuk.pleos.rentalcheck.model.StorageSpace
import org.json.JSONObject

/** Real SDK/controller integration with isolated preference names; the user's current rental is never overwritten. */
class TripAssistanceFlowRunner : Instrumentation() {
    private var action = "setup"
    private var prefix = "feature_test"
    private lateinit var controller: InspectionController

    // Run only explicit diagnostic phases, with a per-run private namespace supplied by the host fixture.
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        action = arguments?.getString("action") ?: action
        prefix = arguments?.getString("prefix") ?: prefix
        require(prefix.matches(Regex("feature_[a-z0-9_]{1,40}")))
        start()
    }

    // Exercise production events and genuine callbacks, reporting progress without inserting fake vehicle readings or changing the live user's preferences.
    override fun onStart() {
        val result = Bundle()
        var code = Activity.RESULT_OK
        val isolated = object : ContextWrapper(targetContext) {
            // Preserve the diagnostic namespace when production stores request the application context.
            override fun getApplicationContext(): Context = this
            // Keep all test inspection, pairing, configuration and guidance data apart from the user's original preferences.
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = targetContext.getSharedPreferences(prefix + "_" + name, mode)
        }
        try {
            runOnMainSync { controller = InspectionController(isolated); controller.start() }
            idle()
            when (action) {
                "prepare-evidence" -> {
                    // Prepare fresh genuine SDK evidence for browser-only photo tests while keeping the installed user's rental in its original namespace.
                    check(state().record == null)
                    send(InspectionEvent.SetEmulatorTestMode(true))
                    val file = targetContext.filesDir.resolve("feature-test-config.json")
                    val config = JSONObject(file.readText())
                    send(InspectionEvent.ConfigureServer(config.getString("serverUrl"), config.getString("deviceToken")))
                    check(file.delete())
                    send(InspectionEvent.CreateAccountQr)
                    sendStatus(1, Bundle().apply { putString("featurePairing", state().pairing!!.pairingUrl) })
                    paired()
                    send(InspectionEvent.ContinueAfterConnection)
                    send(InspectionEvent.CapturePickup)
                    check(state().stage == InspectionStage.ACTIVE)
                    send(InspectionEvent.BeginReturn)
                    send(InspectionEvent.CaptureReturn)
                    check(state().stage == InspectionStage.EVIDENCE && state().record?.returned != null)
                    check(state().record?.revision == state().record?.syncedRevision)
                }
                "setup" -> {
                    check(state().record == null)
                    send(InspectionEvent.SetEmulatorTestMode(true))
                    val file = targetContext.filesDir.resolve("feature-test-config.json")
                    val config = JSONObject(file.readText())
                    send(InspectionEvent.ConfigureServer(config.getString("serverUrl"), config.getString("deviceToken")))
                    check(file.delete())
                    send(InspectionEvent.CreateAccountQr)
                    check(state().pairing != null)
                }
                "practice" -> {
                    paired()
                    send(InspectionEvent.OpenPractice)
                    send(InspectionEvent.StartPractice("window.ROW_1_LEFT.position", "앞쪽 왼쪽 창문"))
                    until("P gear and initial closed baseline") { it.assistance.practice?.step == PracticeStep.OPEN }
                    stage("OPEN_WINDOW")
                    until("real opening") { it.assistance.practice?.step == PracticeStep.CLOSE }
                    stage("CLOSE_WINDOW")
                    until("real closing") { it.assistance.practice?.step == PracticeStep.COMPLETE }
                    check(state().assistance.practiceCompleted)
                    send(InspectionEvent.ClosePractice)
                    send(InspectionEvent.MarkStorageSpace(StorageSpace.TRUNK, true))
                    stage("OPEN_SPACES")
                    until("actual rear-right and trunk callbacks") { it.assistance.memory.openedSpaces.containsAll(setOf(StorageSpace.REAR_RIGHT, StorageSpace.TRUNK)) }
                    check(state().assistance.receivedCallbacks > 0)
                    stage("CLOSE_SPACES")
                    val deadline = System.currentTimeMillis() + 120_000
                    while (System.currentTimeMillis() < deadline) {
                        send(InspectionEvent.Refresh)
                        val current = state().current
                        if (current?.find("door.ROW_2_RIGHT.closed")?.value == "true" && current.find("door.REAR.closed")?.value == "true") break
                        Thread.sleep(250)
                    }
                    check(state().current?.find("door.REAR.closed")?.value == "true")
                    send(InspectionEvent.ContinueAfterConnection)
                    send(InspectionEvent.CapturePickup)
                    check(state().stage == InspectionStage.ACTIVE && state().record?.syncedRevision == state().record?.revision)
                }
                "return" -> {
                    paired()
                    check(state().assistance.practiceCompleted)
                    check(state().assistance.memory.openedSpaces.containsAll(setOf(StorageSpace.REAR_RIGHT, StorageSpace.TRUNK)))
                    check(StorageSpace.TRUNK in state().assistance.memory.markedSpaces)
                    send(InspectionEvent.BeginReturn)
                    check(state().returnWindowDialog == null)
                    send(InspectionEvent.CaptureReturn)
                    check(state().stage == InspectionStage.EVIDENCE)
                    check(state().record?.usageMemory == state().assistance.memory)
                    val frozen = state().record!!.usageMemory
                    send(InspectionEvent.MarkStorageSpace(StorageSpace.REAR_LEFT, true))
                    check(state().record?.usageMemory == frozen && state().assistance.memory == frozen)
                    stage("EVIDENCE_READY")
                    until("server final receipt", 180_000) { it.stage == InspectionStage.RESULT && it.record?.receiptId != null }
                }
                "reconnect" -> {
                    paired()
                    val id = state().record!!.id
                    send(InspectionEvent.DisconnectAccount)
                    check(state().record?.id == id && state().pairedBooking == null)
                    send(InspectionEvent.CreateAccountQr)
                }
                else -> error("Unknown feature test phase")
            }
            val state = state()
            val report = JSONObject().put("action", action).put("stage", state.stage.name)
                .put("recordId", state.record?.id ?: JSONObject.NULL).put("revision", state.record?.revision ?: 0)
                .put("receiptId", state.record?.receiptId ?: JSONObject.NULL)
                .put("practiceCompleted", state.assistance.practiceCompleted)
                .put("receivedCallbacks", state.assistance.receivedCallbacks)
                .put("memory", UsageMemoryCodec.encode(state.record?.takeIf { it.returned != null }?.usageMemory ?: state.assistance.memory))
                .put("pairingUrl", state.pairing?.pairingUrl ?: JSONObject.NULL)
                .put("snapshot", state.current?.let { InspectionStore(isolated).encode(it) } ?: JSONObject.NULL)
            result.putString("featureFlow", report.toString())
        } catch (error: Exception) {
            code = Activity.RESULT_CANCELED
            result.putString("featureFlowError", error.stackTraceToString())
        } finally { if (::controller.isInitialized) runOnMainSync { controller.dispose() } }
        finish(code, result)
    }

    // Keep all state reads on the same main thread used by the production controller and SDK callback receiver.
    private fun state(): InspectionUiState {
        lateinit var value: InspectionUiState
        runOnMainSync { value = controller.state }
        return value
    }

    // Wait for bounded SDK/server operations and treat actual transport errors as failures rather than success by timeout.
    private fun idle() { until("idle", 45_000) { !it.busy && !it.cloudBusy && !it.accountBusy } }

    // Emit only a fixed stage name so the host can safely coordinate manual Console controls without exposing account or QR tokens.
    private fun stage(value: String) { sendStatus(1, Bundle().apply { putString("featureStage", value) }) }

    // Use the exact UI event guards for every test action; diagnostics never bypass pairing, VIN, window or submission rules.
    private fun send(event: InspectionEvent) { idle(); runOnMainSync { controller.dispatch(event) }; idle() }

    // Separate a real account approval from the presence of a test VIN so testing does not weaken account ownership.
    private fun paired() { until("owner approval", 45_000) { it.pairedBooking != null } }

    // Keep longer interactive phases bounded and include the observed guide state when a requested real SDK signal never arrives.
    private fun until(label: String, timeout: Long = 120_000, condition: (InspectionUiState) -> Boolean) {
        val deadline = System.currentTimeMillis() + timeout
        while (System.currentTimeMillis() < deadline) {
            val current = state()
            check(current.cloudError == null) { current.cloudError.orEmpty() }
            if (condition(current)) return
            if (current.assistance.practice?.step == PracticeStep.INTERRUPTED) error("Practice interrupted: ${current.assistance.practice}")
            Thread.sleep(100)
        }
        error("Timed out waiting for $label: ${state().assistance}; ${state().accountMessage}")
    }
}
