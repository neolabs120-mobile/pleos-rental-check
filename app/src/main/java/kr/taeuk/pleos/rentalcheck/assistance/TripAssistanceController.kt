package kr.taeuk.pleos.rentalcheck.assistance

import android.content.Context
import android.os.SystemClock
import java.io.Closeable
import kr.taeuk.pleos.rentalcheck.data.UsageMemoryCodec
import kr.taeuk.pleos.rentalcheck.model.*
import kr.taeuk.pleos.rentalcheck.vehicle.InteractionObserver
import kr.taeuk.pleos.rentalcheck.vehicle.VehicleDataSource
import kr.taeuk.pleos.rentalcheck.vehicle.VehicleInteraction
import kotlinx.coroutines.*
import org.json.JSONObject

data class TripAssistanceState(val memory: UsageMemory = UsageMemory(), val practice: WindowPractice? = null,
    val practiceOpen: Boolean = false, val practiceCompleted: Boolean = false, val listening: Boolean = false,
    val receivedCallbacks: Int = 0, val problem: String? = null, val seatReadings: List<VehicleReading> = emptyList())

/** One renter's foreground-only guidance. It cannot save inspections or grant account ownership. */
class TripAssistanceController(context: Context, private val source: VehicleDataSource, private val scope: CoroutineScope,
    private val ensureSource: () -> Unit, private val changed: (TripAssistanceState) -> Unit) {
    private val preferences = context.applicationContext.getSharedPreferences("trip_assistance", Context.MODE_PRIVATE)
    private var bookingId: String? = null
    private var vin: String? = null
    private var frozen = false
    private var foreground = false
    private var generation = 0
    private var watcher: Job? = null
    private var practiceJob: Job? = null
    private var subscription: Closeable? = null
    var state = TripAssistanceState()
        private set

    // Restore only the same booking AND VIN; data from a previous renter must never appear as the new renter's luggage history.
    fun attach(id: String, vehicleVin: String, record: InspectionRecord?) {
        if (id != bookingId || vehicleVin != vin) {
            stopWatching()
            bookingId = id; vin = vehicleVin
            state = TripAssistanceState()
            runCatching {
                val saved = preferences.getString("session", null)?.let(::JSONObject)
                if (saved?.optString("bookingId") == id && saved.optString("vin") == vehicleVin)
                    state = state.copy(memory = UsageMemoryCodec.decode(saved.optJSONObject("memory")), practiceCompleted = saved.optBoolean("practiceCompleted"))
            }.onFailure { state = state.copy(problem = "소지품 확인 메모를 읽지 못했어요. 직접 확인해 주세요.") }
        }
        frozen = record?.returned != null
        if (frozen) { stopWatching(); state = state.copy(memory = record!!.usageMemory, seatReadings = SeatOccupancy.readings(record.returned)) }
        if (foreground && !frozen) watch()
        publish()
    }

    // Observe only while the vehicle screen is active; returning from background explicitly retains incomplete coverage.
    fun start() { foreground = true; if (bookingId != null && !frozen) watch(); publish() }

    // Cancel bounded reads and unregister listeners before the owning controller releases the SDK.
    fun stop() {
        foreground = false; stopWatching()
        state = state.copy(practiceOpen = false, practice = state.practice?.let {
            if (it.running) it.copy(step = PracticeStep.INTERRUPTED, problem = "화면을 떠나 연습을 중단했어요. 정차 후 다시 시작하세요.") else it
        })
        publish()
    }

    // Detach authorization immediately while retaining the private booking-scoped recovery file for a legitimate reconnect.
    fun detach() { stopWatching(); bookingId = null; vin = null; frozen = false; state = TripAssistanceState(); publish() }

    // Luggage locations are explicit user marks and never replace SDK door observations or mandatory return checks.
    fun mark(space: StorageSpace, marked: Boolean) {
        if (bookingId == null || frozen) return
        state = state.copy(memory = state.memory.mark(space, marked)); persist(); publish()
    }

    // Save only the reviewed item/location and roll back the visible change if durable storage fails; a returned record is immutable.
    fun rememberItem(memo: LuggageMemo): Boolean {
        if (bookingId == null || frozen || !foreground) return false
        val previous = state.memory; state = state.copy(memory = previous.remember(memo), problem = null)
        val saved = persist()
        if (!saved) state = state.copy(memory = previous)
        publish(); return saved
    }

    // Delete only the selected user note, preserving observed openings and marks; failed persistence keeps the previous memo available.
    fun forgetItem(space: StorageSpace) {
        if (bookingId == null || frozen || !foreground) return
        val previous = state.memory; state = state.copy(memory = previous.forget(space), problem = null)
        if (!persist()) state = state.copy(memory = previous)
        publish()
    }

    // Persist a parked renter's per-item recovery acknowledgement before the return snapshot freezes this memory.
    fun collectItem(space: StorageSpace, checked: Boolean) {
        if (bookingId == null || frozen || !foreground) return
        val previous = state.memory; state = state.copy(memory = previous.collect(space, checked), problem = null)
        if (!persist()) state = state.copy(memory = previous)
        publish()
    }

    // Opening the optional guide does not auto-start an exercise, control a vehicle, or mark a manual checklist as complete.
    fun openPractice() { if (bookingId != null && !frozen) { state = state.copy(practiceOpen = true); publish() } }

    // Start a fresh 90-second sequence; all progress comes from new real SDK reads, including a fresh P-gear check.
    fun startPractice(key: String, label: String) {
        if (!foreground || frozen || bookingId == null || !key.startsWith("window.") || !key.endsWith(".position")) return
        practiceJob?.cancel()
        state = state.copy(practiceOpen = true, practice = WindowPractice(key, label, SystemClock.elapsedRealtime()))
        publish()
        val currentGeneration = generation
        practiceJob = scope.launch {
            while (isActive && foreground && currentGeneration == generation && state.practice?.running == true) {
                val practice = state.practice ?: break
                if (practice.remainingSeconds(SystemClock.elapsedRealtime()) == 0) {
                    state = state.copy(practice = practice.copy(step = PracticeStep.TIMED_OUT, problem = "90초가 지났어요. 연 창문을 직접 닫고 다시 시작하거나 건너뛰세요.")); publish(); break
                }
                try {
                    ensureSource()
                    val snapshot = source.readSnapshot()
                    if (currentGeneration != generation || !foreground) break
                    val updated = (state.practice ?: break).observe(snapshot, SystemClock.elapsedRealtime())
                    state = state.copy(practice = updated, practiceCompleted = state.practiceCompleted || updated.step == PracticeStep.COMPLETE)
                    receiveSnapshot(snapshot)
                    if (updated.step == PracticeStep.COMPLETE) persist()
                } catch (error: CancellationException) { throw error }
                catch (error: Exception) { state = state.copy(practice = state.practice?.copy(problem = "차량 값을 읽지 못했어요. 확인되지 않은 동작은 완료 처리하지 않아요.")) }
                publish(); delay(1_000)
            }
        }
    }

    // Leaving early is explicitly a skip, not a successful exercise; the UI reminds the driver to close any opened window.
    fun closePractice() {
        practiceJob?.cancel(); practiceJob = null
        state = state.copy(practiceOpen = false, practice = state.practice?.let {
            if (it.running) it.copy(step = PracticeStep.INTERRUPTED, problem = "연습을 건너뛰었어요. 열었던 창문은 직접 닫아 주세요.") else it
        }); publish()
    }

    // Seed observed open compartments from bounded reads as well as callbacks; only a validated derived closure can mark an opening.
    fun receiveSnapshot(snapshot: VehicleSnapshot) {
        if (bookingId == null || frozen) return
        var memory = state.memory
        for (space in StorageSpace.entries) if (snapshot.find(space.doorKey.removeSuffix(".position") + ".closed")?.let { it.available && it.value == "false" } == true)
            memory = memory.observe(space.doorKey, 1, 0, 1, true)
        val seats = SeatOccupancy.readings(snapshot).fold(state.seatReadings, SeatOccupancy::update)
        if (memory != state.memory || seats != state.seatReadings) {
            val memoryChanged = memory != state.memory
            state = state.copy(memory = memory, seatReadings = seats)
            if (memoryChanged) persist()
            publish()
        }
    }

    // Register once per foreground generation and reject late callbacks after a pause, account switch or return save.
    private fun watch() {
        if (watcher?.isActive == true || subscription != null) return
        val currentGeneration = generation
        watcher = scope.launch {
            try {
                ensureSource()
                val observer = source as? InteractionObserver ?: error("이 차량 환경에서는 변경 알림을 지원하지 않아요.")
                val handle = observer.observeInteractions { event -> scope.launch {
                    if (foreground && !frozen && currentGeneration == generation && bookingId != null) receive(event)
                } }
                if (currentGeneration != generation || !foreground || frozen) { handle.close(); return@launch }
                subscription = handle
                state = state.copy(listening = true); publish()
                receiveSnapshot(source.readSnapshot())
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { if (currentGeneration == generation) { state = state.copy(listening = false, problem = "변경 알림을 확인하지 못했어요. 사용한 공간을 직접 표시해 주세요."); publish() } }
        }
    }

    // Keep observed openings separate from user marks and retain errors as incomplete monitoring, never as absence of belongings.
    private fun receive(event: VehicleInteraction) {
        event.reading?.takeIf { SeatOccupancy.isSeat(it.key) }?.let { reading ->
            state = state.copy(seatReadings = SeatOccupancy.update(state.seatReadings, reading), receivedCallbacks = state.receivedCallbacks + 1)
            publish(); return
        }
        if (event.status != ReadStatus.AVAILABLE || event.value == null) {
            state = state.copy(problem = "일부 차량 변경 알림을 받지 못했어요. 소지품을 직접 확인해 주세요."); publish(); return
        }
        val memory = state.memory.observe(event.key, event.value, event.minimum, event.maximum, true)
        val memoryChanged = memory != state.memory
        state = state.copy(memory = memory, receivedCallbacks = state.receivedCallbacks + 1)
        if (memoryChanged) persist()
        publish()
    }

    // Persist a bounded, account-scoped set of places; failures stay visible and never clear the inspection record.
    private fun persist(): Boolean {
        val id = bookingId ?: return false
        return runCatching {
            val json = JSONObject().put("bookingId", id).put("vin", vin).put("memory", UsageMemoryCodec.encode(state.memory))
                .put("practiceCompleted", state.practiceCompleted)
            check(preferences.edit().putString("session", json.toString()).commit())
        }.onFailure { state = state.copy(problem = "메모를 기기에 저장하지 못했어요. 소지품을 직접 확인해 주세요.") }.isSuccess
    }

    // Invalidate callbacks before unregistering so a queued event cannot contaminate a later renter or a frozen return record.
    private fun stopWatching() {
        generation++; practiceJob?.cancel(); practiceJob = null; watcher?.cancel(); watcher = null
        subscription?.close(); subscription = null
        state = state.copy(listening = false, seatReadings = emptyList())
    }

    // Deliver only the manager's presentation state; the parent remains responsible for authorization and inspection navigation.
    private fun publish() { changed(state) }
}
