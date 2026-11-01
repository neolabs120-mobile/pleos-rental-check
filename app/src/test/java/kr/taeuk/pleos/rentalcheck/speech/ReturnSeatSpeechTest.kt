package kr.taeuk.pleos.rentalcheck.speech

import org.junit.Assert.*
import org.junit.Test

class ReturnSeatSpeechTest {
    private class Engine : SeatSpeechEngine {
        val texts = mutableListOf<String>()
        val callbacks = mutableListOf<(SpeechEvent) -> Unit>()
        var throws = false
        // Retain callbacks after stop deliberately, so tests can prove the coordinator rejects obsolete service events.
        override fun speak(text: String, onEvent: (SpeechEvent) -> Unit) {
            if (throws) error("unavailable")
            texts += text; callbacks += onEvent
        }
        // The fake does not cancel callbacks; lifecycle safety must come from the production generation guards.
        override fun stop() = Unit
        // Drive actual callback semantics independently from accepting a synthesis request.
        fun emit(kind: SpeechEventKind, index: Int = callbacks.lastIndex, rms: Double = 0.0) = callbacks[index](SpeechEvent(kind, rms))
    }

    // A missing account, empty/unknown occupancy or a foreground/window guard must never start a speculative reminder.
    @Test fun onlyAnEligibleOccupiedReturnCanSpeak() {
        val engine = Engine(); val voice = ReturnSeatSpeech(engine) {}
        voice.update(null, listOf("뒤쪽 오른쪽"), true)
        voice.update("rental", emptyList(), true)
        voice.update("rental", listOf("뒤쪽 오른쪽"), false)
        voice.replay()
        assertTrue(engine.texts.isEmpty())
    }

    // A request is not playback: require service callbacks before declaring speech started or completed.
    @Test fun requestAloneDoesNotClaimSuccessfulPlayback() {
        val engine = Engine(); val voice = ReturnSeatSpeech(engine) {}
        voice.update("secret-rental-id", listOf("뒤쪽 오른쪽"), true)
        assertEquals(SeatSpeechStatus.PREPARING, voice.state.status)
        assertEquals(0, voice.state.starts); assertEquals(0, voice.state.completions)
        assertTrue(engine.texts.single().contains("뒤쪽 오른쪽 좌석과 발밑"))
        assertFalse(engine.texts.single().contains("secret-rental-id"))
        assertFalse(engine.texts.single().contains("소지품이 남아"))
    }

    // Reordered/duplicated SDK area lists and refresh/save busy transitions cannot replay the same reminder automatically.
    @Test fun repeatedRefreshAndSaveAreDeduplicated() {
        val engine = Engine(); val voice = ReturnSeatSpeech(engine) {}
        voice.update("rental", listOf("앞쪽 왼쪽", "뒤쪽 오른쪽"), true)
        engine.emit(SpeechEventKind.STARTED); engine.emit(SpeechEventKind.DONE)
        repeat(3) {
            voice.update("rental", emptyList(), false)
            voice.update("rental", listOf("뒤쪽 오른쪽", "앞쪽 왼쪽", "앞쪽 왼쪽"), true)
        }
        assertEquals(1, engine.texts.size); assertEquals(1, voice.state.completions)
    }

    // Changing occupied locations must replace the utterance and prevent the earlier request's delayed completion from completing the new one.
    @Test fun changedSeatsUseFreshLabelsAndRejectOldEvents() {
        val engine = Engine(); val voice = ReturnSeatSpeech(engine) {}
        voice.update("rental", listOf("뒤쪽 오른쪽"), true)
        voice.update("rental", listOf("앞쪽 오른쪽"), true)
        engine.emit(SpeechEventKind.DONE, 0)
        assertEquals(SeatSpeechStatus.PREPARING, voice.state.status)
        assertTrue(engine.texts.last().contains("앞쪽 오른쪽 좌석과 발밑"))
        assertFalse(engine.texts.last().contains("뒤쪽 오른쪽"))
    }

    // Dismissal/backgrounding must cancel queued startup events and must not automatically start talking again after foreground resume.
    @Test fun stoppedInitializationCannotSpeakAfterResume() {
        val engine = Engine(); val voice = ReturnSeatSpeech(engine) {}
        voice.update("rental", listOf("뒤쪽 오른쪽"), true); voice.stop()
        engine.emit(SpeechEventKind.STARTED); engine.emit(SpeechEventKind.DONE)
        voice.update("rental", listOf("뒤쪽 오른쪽"), true)
        assertEquals(SeatSpeechStatus.STOPPED, voice.state.status)
        assertEquals(0, voice.state.starts); assertEquals(1, engine.texts.size)
    }

    // A hidden or window-blocked notice cannot be replayed even if the user/event queue still contains an older replay action.
    @Test fun hiddenNoticeCannotReplay() {
        val engine = Engine(); val voice = ReturnSeatSpeech(engine) {}
        voice.update("rental", listOf("뒤쪽 오른쪽"), true)
        voice.update("rental", emptyList(), false); voice.replay()
        assertEquals(1, engine.texts.size)
    }

    // Engine failure must stay explicit, avoid retry storms on polling, and permit a deliberate retry without changing return evidence.
    @Test fun failureNeedsExplicitReplay() {
        val engine = Engine(); val voice = ReturnSeatSpeech(engine) {}
        voice.update("rental", listOf("뒤쪽 오른쪽"), true)
        engine.emit(SpeechEventKind.FAILED)
        repeat(5) { voice.update("rental", listOf("뒤쪽 오른쪽"), true) }
        assertEquals(SeatSpeechStatus.FAILED, voice.state.status); assertEquals(1, engine.texts.size)
        voice.replay(); assertEquals(2, engine.texts.size)
    }

    // A new return attempt or rental is a fresh user action, while request counters reset only when rental identity changes.
    @Test fun newAttemptAndRentalCanAnnounceAgain() {
        val engine = Engine(); val voice = ReturnSeatSpeech(engine) {}
        voice.update("rental", listOf("뒤쪽 오른쪽"), true)
        voice.newAttempt(); voice.update("rental", listOf("뒤쪽 오른쪽"), true)
        assertEquals(2, voice.state.requests)
        voice.update("another-rental", listOf("뒤쪽 오른쪽"), true)
        assertEquals(3, engine.texts.size); assertEquals(1, voice.state.requests)
    }

    // Only finite playback levels and one start/completion per live request enter diagnostics; late RMS cannot rewrite a finished result.
    @Test fun playbackEvidenceIsBoundedAndTerminalEventsAreFinal() {
        val engine = Engine(); val voice = ReturnSeatSpeech(engine) {}
        voice.update("rental", listOf("뒤쪽 오른쪽"), true)
        engine.emit(SpeechEventKind.RMS, rms = Double.NaN)
        engine.emit(SpeechEventKind.RMS, rms = Double.POSITIVE_INFINITY)
        engine.emit(SpeechEventKind.STARTED); engine.emit(SpeechEventKind.STARTED)
        engine.emit(SpeechEventKind.RMS, rms = 0.4); engine.emit(SpeechEventKind.DONE)
        engine.emit(SpeechEventKind.RMS, rms = 1.0); engine.emit(SpeechEventKind.DONE)
        assertEquals(1, voice.state.starts); assertEquals(1, voice.state.completions)
        assertEquals(0.4, voice.state.peakRms, 0.0001)
    }

    // Even a synchronous SDK construction error must become a recoverable voice state rather than crashing the return controller.
    @Test fun synchronousEngineFailureDoesNotEscape() {
        val engine = Engine().also { it.throws = true }; val voice = ReturnSeatSpeech(engine) {}
        voice.update("rental", listOf("뒤쪽 오른쪽"), true)
        assertEquals(SeatSpeechStatus.FAILED, voice.state.status)
        assertEquals(1, voice.state.requests)
    }
}
