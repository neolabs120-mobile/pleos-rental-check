package kr.taeuk.pleos.rentalcheck.speech

import org.junit.Assert.*
import org.junit.Test

class SwitchOffSpeechTest {
    private class Engine : SeatSpeechEngine {
        val texts = mutableListOf<String>()
        val callbacks = mutableListOf<(SpeechEvent) -> Unit>()
        var stops = 0
        override fun speak(text: String, onEvent: (SpeechEvent) -> Unit) { texts += text; callbacks += onEvent }
        override fun stop() { stops++ }
    }

    // A request is not playback; only genuine engine callbacks record a start and a completion.
    @Test fun playbackIsCountedOnlyFromEngineEvents() {
        val engine = Engine(); val speech = SwitchOffSpeech(engine) {}
        speech.play("실내등")
        assertEquals(SeatSpeechStatus.PREPARING, speech.state.status)
        engine.callbacks.last()(SpeechEvent(SpeechEventKind.STARTED))
        engine.callbacks.last()(SpeechEvent(SpeechEventKind.DONE))
        assertEquals(1, speech.state.starts); assertEquals(1, speech.state.completions)
        assertEquals(SeatSpeechStatus.DONE, speech.state.status)
    }

    // Stopping invalidates late callbacks, and an idle stop does not reset the speech service again.
    @Test fun stopRejectsLateCallbacksAndIgnoresIdleStops() {
        val engine = Engine(); val speech = SwitchOffSpeech(engine) {}
        speech.stop()
        assertEquals(0, engine.stops)
        speech.play("전조등")
        speech.stop()
        assertEquals(1, engine.stops)
        engine.callbacks.last()(SpeechEvent(SpeechEventKind.DONE))
        assertEquals(SeatSpeechStatus.STOPPED, speech.state.status)
        assertEquals(0, speech.state.completions)
    }
}
