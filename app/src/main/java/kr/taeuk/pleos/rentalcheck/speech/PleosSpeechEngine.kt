package kr.taeuk.pleos.rentalcheck.speech

import android.content.Context
import android.media.AudioAttributes
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class PleosSpeechEngine(private val context: Context, private val scope: CoroutineScope) : SeatSpeechEngine {
    private var client: TextToSpeech? = null
    private var listener: EventListener? = null
    private var deadline: Job? = null
    private var generation = 0

    // Use Pleos's local Korean engine with a fresh listener lifetime per reminder; no account registration, microphone or network synthesis is needed.
    override fun speak(text: String, onEvent: (SpeechEvent) -> Unit) {
        stop(); val token = generation
        try {
            val tts = TextToSpeech(context, Mode.ON_DEVICE); client = tts
            val events = object : EventListener {
                // Report readiness separately from playback so initializing the SDK is never mistaken for audible speech.
                override fun onReady() = deliver(token) { onEvent(SpeechEvent(SpeechEventKind.READY)) }
                // Forward the actual service playback start on the controller's owning thread.
                override fun onStart() = deliver(token) { onEvent(SpeechEvent(SpeechEventKind.STARTED)) }
                // Preserve the playback-level signal for bounded emulator verification without reading the microphone.
                override fun onUpdatedRms(rms: Double) = deliver(token) { onEvent(SpeechEvent(SpeechEventKind.RMS, rms)) }
                // Release the completed SDK instance after forwarding its real completion event.
                override fun onDone() = finish(token, SpeechEvent(SpeechEventKind.DONE), onEvent)
                // An external audio stop retires this request rather than being reported as a successful completion.
                override fun onStop() = finish(token, SpeechEvent(SpeechEventKind.STOPPED), onEvent)
                // Keep service/engine failures visible to the reminder while leaving the return process usable.
                override fun onError(errorType: ErrorType) = finish(token, SpeechEvent(SpeechEventKind.FAILED, error = errorType.name), onEvent)
            }
            listener = events
            deadline = scope.launch {
                delay(35_000)
                finish(token, SpeechEvent(SpeechEventKind.FAILED, error = "TIMEOUT"), onEvent)
            }
            tts.initialize(object : OnInitializeListener {
                // Only a live initialization may register callbacks and enqueue the one complete, Korean-language utterance.
                override fun onSuccess() = deliver(token) {
                    try {
                        tts.addEventListener(events)
                        tts.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                        tts.setLocale(Locale.KOREA)
                        tts.speak(text, true)
                    } catch (error: Exception) { finish(token, SpeechEvent(SpeechEventKind.FAILED, error = error.javaClass.simpleName), onEvent) }
                }
                // Surface missing service/permission/engine support without silently substituting Android or browser speech.
                override fun onFailure(errorType: ErrorType) = finish(token, SpeechEvent(SpeechEventKind.FAILED, error = errorType.name), onEvent)
            })
        } catch (error: Exception) { finish(token, SpeechEvent(SpeechEventKind.FAILED, error = error.javaClass.simpleName), onEvent) }
    }

    // Marshal Binder callbacks to the UI/controller scope and reject every callback from a stopped or replaced instance.
    private fun deliver(token: Int, action: () -> Unit) { scope.launch { if (token == generation) action() } }

    // Terminal events drain once and close the SDK; the generation changes before stop/release can generate more callbacks.
    private fun finish(token: Int, event: SpeechEvent, onEvent: (SpeechEvent) -> Unit) = deliver(token) { stop(); onEvent(event) }

    // Cancel startup, timeout and playback together so no speech can escape a dismissed notice or stopped Activity.
    override fun stop() {
        generation++; deadline?.cancel(); deadline = null
        val tts = client; val events = listener; client = null; listener = null
        if (tts != null) {
            if (events != null) runCatching { tts.removeEventListener(events) }
            runCatching { tts.stop() }; runCatching { tts.release() }
        }
    }
}
