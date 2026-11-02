package kr.taeuk.pleos.rentalcheck.speech

import kr.taeuk.pleos.rentalcheck.model.LuggageMemo

enum class SeatSpeechStatus { IDLE, PREPARING, SPEAKING, DONE, STOPPED, FAILED }
enum class SpeechEventKind { READY, STARTED, RMS, DONE, STOPPED, FAILED }
data class SpeechEvent(val kind: SpeechEventKind, val rms: Double = 0.0, val error: String? = null)
data class SeatSpeechState(
    val status: SeatSpeechStatus = SeatSpeechStatus.IDLE, val text: String = "", val error: String? = null,
    val requests: Int = 0, val starts: Int = 0, val completions: Int = 0, val peakRms: Double = 0.0,
)

interface SeatSpeechEngine {
    // Synthesize one complete reminder and report actual playback events without claiming that a request alone is audible output.
    fun speak(text: String, onEvent: (SpeechEvent) -> Unit)
    // Cancel pending initialization and playback, discarding callbacks belonging to the retired request.
    fun stop()
}

class ReturnSeatSpeech(private val engine: SeatSpeechEngine, private val onChanged: (SeatSpeechState) -> Unit) {
    var state = SeatSpeechState()
        private set
    private var identity: String? = null
    private var seats = emptyList<String>()
    private var memos = emptyList<LuggageMemo>()
    private var lastKey: String? = null
    private var allowed = false
    private var generation = 0
    private var active = false
    private var started = false

    // Announce a changed occupied-seat set only while its fresh, authorized return notice can be shown; refresh/save must not replay the same reminder.
    fun update(rental: String?, occupied: List<String>, eligible: Boolean, notes: List<LuggageMemo> = emptyList()) {
        if (identity != rental) {
            stop(); identity = rental; lastKey = null; state = SeatSpeechState(); onChanged(state)
        }
        seats = occupied.distinct()
        memos = notes
        allowed = eligible && rental != null && (seats.isNotEmpty() || memos.isNotEmpty())
        if (!allowed) { stop(); return }
        if (key() != lastKey) play()
    }

    // A deliberate new return attempt may announce again after the renter backs out; incidental foreground changes keep deduplication intact.
    fun newAttempt() { stop(); lastKey = null }

    // Explicit replay permits a user to hear a missed or failed reminder without retrying automatically on every state publication.
    fun replay() { if (allowed) play() }

    // Silence a dismissed/backgrounded notice immediately and invalidate both queued initialization results and late playback events.
    fun stop() {
        generation++; active = false; engine.stop()
        if (state.status in listOf(SeatSpeechStatus.PREPARING, SeatSpeechStatus.SPEAKING)) {
            state = state.copy(status = SeatSpeechStatus.STOPPED); onChanged(state)
        }
    }

    // Normalize only the deduplication key so SDK ordering cannot cause repeated speech while the spoken labels keep their natural display order.
    private fun key(): String = identity + "|" + seats.sorted().joinToString("|") + "|" + memos.sortedBy { it.space.ordinal }.joinToString { it.space.name + ":" + it.item }

    // Send only generic seat locations, never VIN/account data, and keep voice failure independent of return storage and photo requirements.
    private fun play() {
        stop(); val token = generation; active = true; started = false; lastKey = key()
        val text = buildString {
            if (memos.isNotEmpty()) append("저장한 짐 메모예요. " + memos.joinToString(". ") { it.space.label + "에 " + it.item + " 메모를 남기셨어요" } + ". 직접 확인해 주세요. ")
            if (seats.isNotEmpty()) append("내리기 전 ${seats.joinToString(", ")} 좌석과 발밑에 두고 내리는 소지품이 없는지 확인해 주세요. ")
            append("다른 좌석과 트렁크도 직접 확인해 주세요.")
        }
        state = state.copy(status = SeatSpeechStatus.PREPARING, text = text, error = null,
            requests = state.requests + 1, peakRms = 0.0)
        onChanged(state)
        try { engine.speak(text) { event -> if (generation == token && active) receive(event) } }
        catch (error: Exception) { receive(SpeechEvent(SpeechEventKind.FAILED, error = error.javaClass.simpleName)) }
    }

    // Count real SDK starts/completions, reject duplicate terminal events, and retain finite playback RMS as test evidence rather than a UI success guess.
    private fun receive(event: SpeechEvent) {
        state = when (event.kind) {
            SpeechEventKind.READY -> state
            SpeechEventKind.STARTED -> state.copy(status = SeatSpeechStatus.SPEAKING,
                starts = state.starts + if (started) 0 else 1).also { started = true }
            SpeechEventKind.RMS -> if (event.rms.isFinite() && event.rms > state.peakRms) state.copy(peakRms = event.rms) else state
            SpeechEventKind.DONE -> state.copy(status = SeatSpeechStatus.DONE, completions = state.completions + 1).also { active = false }
            SpeechEventKind.STOPPED -> state.copy(status = SeatSpeechStatus.STOPPED).also { active = false }
            SpeechEventKind.FAILED -> state.copy(status = SeatSpeechStatus.FAILED, error = event.error).also { active = false }
        }
        onChanged(state)
    }
}
