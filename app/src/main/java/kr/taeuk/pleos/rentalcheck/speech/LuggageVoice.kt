package kr.taeuk.pleos.rentalcheck.speech

import kr.taeuk.pleos.rentalcheck.model.LuggageDraft
import kr.taeuk.pleos.rentalcheck.model.LuggageMemo
import kr.taeuk.pleos.rentalcheck.model.StorageSpace

enum class RecognitionKind { READY, STARTED, PARTIAL, FINAL, ENDED, ERROR }
data class RecognitionEvent(val kind: RecognitionKind, val text: String = "", val problem: String? = null)
interface LuggageRecognitionEngine {
    // Start exactly one explicit recognition session; the SDK implementation owns initialization, timeout and microphone release.
    fun start(receive: (RecognitionEvent) -> Unit)
    // Retire the complete session so a late callback cannot change another renter's draft.
    fun stop()
}
enum class VoiceMemoPhase { CLOSED, EDITING, PREPARING, LISTENING, FINALIZING }
data class VoiceMemoState(val phase: VoiceMemoPhase = VoiceMemoPhase.CLOSED, val draft: LuggageDraft = LuggageDraft(),
    val problem: String? = null) {
    val open: Boolean get() = phase != VoiceMemoPhase.CLOSED
    val capturing: Boolean get() = phase in listOf(VoiceMemoPhase.PREPARING, VoiceMemoPhase.LISTENING, VoiceMemoPhase.FINALIZING)
}

class LuggageVoice(private val engine: LuggageRecognitionEngine, private val changed: (VoiceMemoState) -> Unit) {
    var state = VoiceMemoState(); private set
    private var identity: String? = null
    private var generation = 0

    // Destroy drafts on an authorization/session change; no raw transcript or audio is saved to a preference or server.
    fun attach(key: String?) { if (key != identity) { close(); identity = key } }

    // Opening a note editor is separate from microphone activation and permits direct entry even when recognition is unavailable.
    fun open(memo: LuggageMemo? = null) {
        if (identity == null) return
        close(); state = VoiceMemoState(VoiceMemoPhase.EDITING, LuggageDraft(space = memo?.space, item = memo?.item.orEmpty())); changed(state)
    }

    // The owning controller must recheck P and VIN immediately before calling this method; final text remains a reviewable draft.
    fun start() {
        if (!state.open || identity == null) return
        engine.stop(); val token = ++generation
        state = state.copy(phase = VoiceMemoPhase.PREPARING, problem = null); changed(state)
        try { engine.start { event -> if (token == generation && state.capturing) receive(event) } }
        catch (error: Exception) { problem("음성 인식을 시작하지 못했어요. 직접 입력해 주세요.") }
    }

    // Allow corrections only outside capture; item limits are enforced again when the user deliberately saves.
    fun edit(space: StorageSpace?, item: String) {
        if (state.open && !state.capturing) { state = state.copy(draft = state.draft.copy(space = space, item = item.take(40))); changed(state) }
    }

    // Cancel capture on a gear/lifecycle/error change while retaining the draft for explicit review, never treating interruption as confirmation.
    fun problem(message: String) {
        engine.stop(); generation++
        if (state.open) { state = state.copy(phase = VoiceMemoPhase.EDITING, problem = message); changed(state) }
    }

    // A cancelled or saved dialog has no active microphone, recognized text or callback capable of reopening it.
    fun close() { generation++; engine.stop(); state = VoiceMemoState(); changed(state) }

    // Partial text is a preview only; a final SDK result seeds editable fields and releases the microphone before any save action.
    private fun receive(event: RecognitionEvent) {
        when (event.kind) {
            RecognitionKind.READY, RecognitionKind.STARTED -> state = state.copy(phase = VoiceMemoPhase.LISTENING)
            RecognitionKind.PARTIAL -> state = state.copy(draft = state.draft.copy(transcript = event.text.take(200)))
            RecognitionKind.FINAL -> {
                generation++; engine.stop()
                state = state.copy(phase = VoiceMemoPhase.EDITING, draft = LuggageDraft.fromSpeech(event.text),
                    problem = if (event.text.isBlank()) "음성을 알아듣지 못했어요. 다시 말하거나 직접 입력해 주세요." else null)
            }
            RecognitionKind.ENDED -> state = state.copy(phase = VoiceMemoPhase.FINALIZING)
            RecognitionKind.ERROR -> { problem("음성 인식을 완료하지 못했어요. 다시 말하거나 직접 입력해 주세요."); return }
        }
        changed(state)
    }
}
