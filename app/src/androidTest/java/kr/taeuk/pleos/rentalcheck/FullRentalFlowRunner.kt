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
import kr.taeuk.pleos.rentalcheck.model.SnapshotComparison
import kr.taeuk.pleos.rentalcheck.model.StorageSpace
import org.json.JSONObject

/** Exercise the installed production controller and genuine SDK in a private test namespace without changing the user's rental. */
class FullRentalFlowRunner : Instrumentation() {
    private lateinit var controller: InspectionController
    private var prefix = "e2e_test"
    private val checks = JSONObject()

    // Admit only a bounded, explicit test namespace; the host supplies credentials in one app-private file rather than command arguments.
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        prefix = arguments?.getString("prefix") ?: prefix
        require(prefix.matches(Regex("e2e_[a-z0-9_]{1,40}")))
        start()
    }

    // Keep one controller alive from pairing to receipt, so pending QR ownership and SDK listener lifetimes follow the real app flow.
    override fun onStart() {
        val result = Bundle()
        var code = Activity.RESULT_OK
        val isolated = object : ContextWrapper(targetContext) {
            // Keep stores that request an application context inside the same isolated test namespace.
            override fun getApplicationContext(): Context = this
            // Store only this run's pairing, settings, rental and space memory under unique names; original preferences remain untouched.
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = targetContext.getSharedPreferences(prefix + "_" + name, mode)
        }
        try {
            runOnMainSync { controller = InspectionController(isolated); controller.start() }
            idle()
            check(state().record == null && state().emulatorTestAvailable)
            send(InspectionEvent.SetEmulatorTestMode(true))
            val file = targetContext.filesDir.resolve("$prefix-config.json")
            val config = JSONObject(file.readText())
            send(InspectionEvent.ConfigureServer(config.getString("serverUrl"), config.getString("deviceToken")))
            check(file.delete())
            send(InspectionEvent.CreateAccountQr)
            stage("PAIRING", JSONObject().put("pairingUrl", state().pairing!!.pairingUrl))
            until("verified recipient QR approval", 180_000) { it.pairedBooking != null }
            checks.put("accountApproved", true)
            send(InspectionEvent.MarkStorageSpace(StorageSpace.TRUNK, true))
            send(InspectionEvent.OpenPractice)
            send(InspectionEvent.StartPractice("window.ROW_1_LEFT.position", "앞쪽 왼쪽 창문"))
            until("P and closed practice baseline") { it.assistance.practice?.step == PracticeStep.OPEN }
            stage("PRACTICE_OPEN")
            until("real practice opening") { it.assistance.practice?.step == PracticeStep.CLOSE }
            stage("PRACTICE_CLOSE")
            until("real practice closing") { it.assistance.practice?.step == PracticeStep.COMPLETE }
            check(state().assistance.practiceCompleted)
            checks.put("realWindowPractice", true)
            send(InspectionEvent.ClosePractice)
            stage("SPACES_OPEN")
            until("real rear-right and trunk events") { it.assistance.memory.openedSpaces.containsAll(setOf(StorageSpace.REAR_RIGHT, StorageSpace.TRUNK)) }
            checks.put("realDoorCallbacks", state().assistance.receivedCallbacks > 0)
            stage("SPACES_CLOSE")
            freshUntil("closed doors") { it.current?.find("door.ROW_2_RIGHT.closed")?.value == "true" && it.current.find("door.REAR.closed")?.value == "true" }
            send(InspectionEvent.ContinueAfterConnection)
            send(InspectionEvent.CapturePickup)
            check(state().stage == InspectionStage.ACTIVE && state().record?.revision == state().record?.syncedRevision)
            val pickup = state().record!!.pickup
            val odometer = pickup.find("odometer.km")?.number ?: error("Real odometer not received")
            stage("DRIVE", JSONObject().put("pickupOdometerKm", odometer))
            freshUntil("simulated travel through real SDK") {
                (it.current?.find("odometer.km")?.number ?: -1.0) >= odometer + 3 && it.current?.find("gear.selection")?.value == "GEAR_PARK"
            }
            stage("RETURN_OPEN_WINDOW")
            freshUntil("real open return window and occupied rear seat") {
                it.current?.find("window.ROW_2_RIGHT.closed")?.value == "false" && it.current.find("seat.ROW_2_RIGHT.occupancy")?.value == "true"
            }
            send(InspectionEvent.BeginReturn)
            check(state().returnWindowDialog?.openWindows?.isNotEmpty() == true)
            send(InspectionEvent.CaptureReturn)
            check(state().record?.returned == null && state().record?.revision == 1)
            checks.put("openWindowBlocked", true)
            send(InspectionEvent.CheckWindowManually(true))
            send(InspectionEvent.CaptureReturn)
            check(state().record?.returned == null && state().returnWindowDialog?.openWindows?.isNotEmpty() == true)
            checks.put("manualCannotOverrideOpenWindow", true)
            check(state().returnOccupiedSeats == listOf("뒤쪽 오른쪽"))
            checks.put("windowGuardWithOccupiedSeat", true)
            stage("RETURN_CLOSE_WINDOW")
            freshUntil("closed return window") { it.current?.find("window.ROW_2_RIGHT.closed")?.value == "true" }
            check(state().returnWindowDialog == null && state().returnOccupiedSeats == listOf("뒤쪽 오른쪽"))
            checks.put("occupiedSeatNoticeAtReturn", true)
            send(InspectionEvent.DismissReturnSeatNotice)
            check(state().returnOccupiedSeats.isEmpty() && state().record?.returned == null)
            checks.put("seatNoticeDismissible", true)
            stage("RETURN_SEATS_EMPTY")
            freshUntil("vacant seats clear the reminder") {
                listOf("ROW_1_LEFT", "ROW_1_RIGHT", "ROW_2_LEFT", "ROW_2_RIGHT").all { area -> it.current?.find("seat.$area.occupancy")?.value == "false" }
            }
            check(state().returnOccupiedSeats.isEmpty())
            checks.put("vacantSeatsNoNotice", true)
            stage("RETURN_SEAT_CHANGE")
            until("new live driver/passenger occupancy before save") {
                listOf("ROW_1_LEFT", "ROW_1_RIGHT").all { area -> it.assistance.seatReadings.any { seat -> seat.key == "seat.$area.occupancy" && seat.value == "true" } }
            }
            check(state().current?.find("seat.ROW_1_RIGHT.occupancy")?.value == "false")
            send(InspectionEvent.CaptureReturn)
            check(state().stage == InspectionStage.EVIDENCE && state().record?.returned != null)
            check(state().returnOccupiedSeats.toSet() == setOf("앞쪽 왼쪽", "앞쪽 오른쪽"))
            check(state().record!!.returned!!.find("seat.ROW_1_RIGHT.rawOccupancy")?.value == "2")
            checks.put("returnSaveRechecksSeats", true)
            checks.put("occupiedSeatAllowsReturn", true)
            send(InspectionEvent.DismissReturnSeatNotice)
            check(state().returnOccupiedSeats.isEmpty() && state().stage == InspectionStage.EVIDENCE)
            val returned = state().record!!
            check(returned.syncedRevision == returned.revision)
            check(SnapshotComparison.compare(pickup, returned.returned!!).distanceKm == 3.0)
            check(returned.usageMemory.openedSpaces.containsAll(setOf(StorageSpace.REAR_RIGHT, StorageSpace.TRUNK)))
            val memory = returned.usageMemory
            send(InspectionEvent.MarkStorageSpace(StorageSpace.REAR_LEFT, true))
            check(state().assistance.memory == memory && state().record?.usageMemory == memory)
            checks.put("returnMemoryFrozen", true)
            stage("EVIDENCE", JSONObject().put("recordId", returned.id).put("revision", returned.revision))
            until("phone submission and matching server receipt", 180_000) { it.stage == InspectionStage.RESULT && it.record?.receiptId != null }
            checks.put("nativeReceiptReceived", true)
            val saved = state().record!!
            result.putString("rentalE2e", JSONObject().put("checks", checks).put("stage", state().stage.name)
                .put("recordId", saved.id).put("receiptId", saved.receiptId).put("revision", saved.revision)
                .put("distanceKm", SnapshotComparison.compare(saved.pickup, saved.returned!!).distanceKm)
                .put("memory", UsageMemoryCodec.encode(saved.usageMemory))
                .put("pickup", InspectionStore(isolated).encode(saved.pickup))
                .put("returned", InspectionStore(isolated).encode(saved.returned!!)).toString())
        } catch (error: Exception) {
            code = Activity.RESULT_CANCELED
            result.putString("rentalE2eError", error.stackTraceToString())
        } finally { if (::controller.isInitialized) runOnMainSync { controller.dispose() } }
        finish(code, result)
    }

    // Serialize state inspection with Compose/controller updates so tests never assert against a partially delivered SDK callback.
    private fun state(): InspectionUiState {
        lateinit var value: InspectionUiState
        runOnMainSync { value = controller.state }
        return value
    }

    // Wait for the production busy guards before and after dispatch rather than invoking private storage or bypassing account checks.
    private fun send(event: InspectionEvent) { idle(); runOnMainSync { controller.dispatch(event) }; idle() }

    // Surface real server errors promptly and keep every operation bounded, including slow SDK initialization.
    private fun idle() { until("idle", 60_000) { !it.busy && !it.cloudBusy && !it.accountBusy } }

    // Publish private stage data only to the host driver, which redacts QR credentials from user-facing progress and reports.
    private fun stage(name: String, details: JSONObject = JSONObject()) { sendStatus(1, Bundle().apply { putString("rentalStage", details.put("name", name).toString()) }) }

    // Require a fresh SDK snapshot for physical transitions; an earlier UI value cannot satisfy the next action.
    private fun freshUntil(label: String, condition: (InspectionUiState) -> Boolean) {
        val deadline = System.currentTimeMillis() + 120_000
        while (System.currentTimeMillis() < deadline) {
            send(InspectionEvent.Refresh)
            if (condition(state())) return
            Thread.sleep(200)
        }
        error("No fresh SDK confirmation for $label")
    }

    // Fail on timeout or interrupted practice instead of silently treating missing SDK or account signals as success.
    private fun until(label: String, timeout: Long = 90_000, condition: (InspectionUiState) -> Boolean) {
        val deadline = System.currentTimeMillis() + timeout
        while (System.currentTimeMillis() < deadline) {
            val current = state()
            check(current.cloudError == null) { current.cloudError.orEmpty() }
            check(current.assistance.practice?.step != PracticeStep.INTERRUPTED) { "Practice interrupted: ${current.assistance.practice}" }
            if (condition(current)) return
            Thread.sleep(100)
        }
        error("Timed out waiting for $label: ${state().stage}; ${state().message}")
    }
}
