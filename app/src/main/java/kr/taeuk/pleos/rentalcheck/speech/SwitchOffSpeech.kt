package kr.taeuk.pleos.rentalcheck.speech

/** Speak the "switch off before leaving" reminder; playback never changes the check result or saves a return. */
class SwitchOffSpeech(private val engine: SeatSpeechEngine, private val changed: (SeatSpeechState) -> Unit) {
    var state = SeatSpeechState(); private set
    private var generation = 0

    // Read the exact sentence once and record genuine engine start/completion, ignoring callbacks from a retired request.
    fun play(text: String) {
        stop(); val token = generation
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

    // Stop only an active request so frequent UI refreshes do not repeatedly reset the speech service.
    fun stop() {
        if (state.status !in listOf(SeatSpeechStatus.PREPARING, SeatSpeechStatus.SPEAKING)) return
        generation++; engine.stop()
        state = state.copy(status = SeatSpeechStatus.STOPPED); changed(state)
    }
}
