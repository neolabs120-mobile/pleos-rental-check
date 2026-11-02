package kr.taeuk.pleos.rentalcheck.speech

/** A short optional prompt; it never executes an inspection action from a playback event. */
class ArrivalSpeech(private val engine: SeatSpeechEngine, private val changed: (SeatSpeechState) -> Unit) {
    var state = SeatSpeechState(); private set
    private var generation = 0

    // Speak only after the owner has established a fresh nearby/P state, retaining actual service starts and completion as evidence.
    fun play() {
        stop(); val token = generation
        val text = "반납 장소 근처예요. 지정 주차구역에 주차하셨다면 반납 준비를 시작해 주세요."
        state = state.copy(status = SeatSpeechStatus.PREPARING, text = text, error = null, requests = state.requests + 1); changed(state)
        engine.speak(text) { event -> if (token == generation) {
            state = when (event.kind) {
                SpeechEventKind.STARTED -> state.copy(status = SeatSpeechStatus.SPEAKING, starts = state.starts + 1)
                SpeechEventKind.DONE -> state.copy(status = SeatSpeechStatus.DONE, completions = state.completions + 1)
                SpeechEventKind.FAILED -> state.copy(status = SeatSpeechStatus.FAILED, error = event.error)
                SpeechEventKind.STOPPED -> state.copy(status = SeatSpeechStatus.STOPPED)
                else -> state
            }; changed(state)
        } }
    }

    // Foreground loss, gear changes, dismissal and microphone capture all stop the reminder immediately and invalidate late callbacks.
    fun stop() {
        generation++; engine.stop()
        if (state.status in listOf(SeatSpeechStatus.PREPARING, SeatSpeechStatus.SPEAKING)) {
            state = state.copy(status = SeatSpeechStatus.STOPPED); changed(state)
        }
    }
}
