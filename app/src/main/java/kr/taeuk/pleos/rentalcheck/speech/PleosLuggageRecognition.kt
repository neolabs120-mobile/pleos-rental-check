package kr.taeuk.pleos.rentalcheck.speech

import android.content.Context
import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.ErrorType
import ai.pleos.playground.stt.constant.Mode
import ai.pleos.playground.stt.listener.OnInitializeListener
import ai.pleos.playground.stt.listener.ResultListener
import java.util.Locale
import kotlinx.coroutines.*

class PleosLuggageRecognition(private val context: Context, private val scope: CoroutineScope,
    private val input: (SpeechToText) -> Unit = { it.request() }) : LuggageRecognitionEngine {
    private var client: SpeechToText? = null
    private var listener: ResultListener? = null
    private var deadline: Job? = null
    private var generation = 0

    // Use only Pleos's local Korean recognizer; audio is transient and capture begins solely after the driver's microphone action.
    override fun start(receive: (RecognitionEvent) -> Unit) {
        stop(); val token = generation
        try {
            val stt = SpeechToText(context, Mode.ON_DEVICE); client = stt
            val events = object : ResultListener {
                // Service readiness is displayed independently from recognized text.
                override fun onReady() = deliver(token) { receive(RecognitionEvent(RecognitionKind.READY)) }
                // Report actual capture start rather than assuming initialize() enabled the microphone.
                override fun onStartedRecognition() = deliver(token) { receive(RecognitionEvent(RecognitionKind.STARTED)) }
                // Keep interim hypotheses separate from the service's explicit final result.
                override fun onUpdated(stt: String, completed: Boolean) = deliver(token) {
                    receive(RecognitionEvent(if (completed) RecognitionKind.FINAL else RecognitionKind.PARTIAL, stt))
                }
                // Speech boundaries are not evidence of a confirmed memo and are intentionally not retained.
                override fun onUpdatedEpdData(on: Long, off: Long) = Unit
                // Microphone level is not stored or used to infer an object, location or completed recognition.
                override fun onUpdatedRms(rms: Double) = Unit
                // End events without a final result leave the user an editable, unsaved draft.
                override fun onEndedRecognition() = deliver(token) { receive(RecognitionEvent(RecognitionKind.ENDED)) }
                // Permission/service failures remain failures and never fall back to a network recognizer.
                override fun onError(errorType: ErrorType) = deliver(token) { receive(RecognitionEvent(RecognitionKind.ERROR, problem = errorType.name)); stop() }
            }
            listener = events
            deadline = scope.launch { delay(20_000); deliver(token) { receive(RecognitionEvent(RecognitionKind.ERROR, problem = "TIMEOUT")); stop() } }
            stt.initialize(object : OnInitializeListener {
                // Configure Korean and register the same removable listener before requesting vehicle microphone input.
                override fun onSuccess() = deliver(token) {
                    try { stt.addListener(events); stt.setLocale(Locale.KOREA); input(stt) }
                    catch (error: Exception) { receive(RecognitionEvent(RecognitionKind.ERROR, problem = error.javaClass.simpleName)); stop() }
                }
                // Failed initialization must not leave a live listener or microphone behind.
                override fun onFailure(errorType: ErrorType) = deliver(token) {
                    receive(RecognitionEvent(RecognitionKind.ERROR, problem = errorType.name)); stop()
                }
            })
        } catch (error: Exception) { receive(RecognitionEvent(RecognitionKind.ERROR, problem = error.javaClass.simpleName)); stop() }
    }

    // Marshal service callbacks onto the controller's thread and discard every retired session.
    private fun deliver(token: Int, action: () -> Unit) { scope.launch { if (token == generation) action() } }

    // Close the microphone, deadline and SDK together; remove the exact listener before releasing the service.
    override fun stop() {
        generation++; deadline?.cancel(); deadline = null
        val stt = client; val events = listener; client = null; listener = null
        if (stt != null) {
            if (events != null) runCatching { stt.removeListener(events) }
            runCatching { stt.stop() }; runCatching { stt.release() }
        }
    }
}
