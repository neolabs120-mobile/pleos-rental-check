package kr.taeuk.pleos.rentalcheck

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.lifecycle.ViewModelProvider
import org.json.JSONObject

/** Film the real Activity, ViewModel, SDK and server through explicit test-only production events, never fixture screen states. */
class SubmissionVideoFlowRunner : Instrumentation() {
    private lateinit var viewModel: InspectionViewModel

    // Require an explicit instrumentation launch; this driver is never included in the distributable Release APK.
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    // Keep the genuine Compose Activity visible while the host coordinates the owner's web approval and official emulator signals.
    override fun onStart() {
        val result = Bundle(); var code = Activity.RESULT_OK
        try {
            val activity = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            runOnMainSync { viewModel = ViewModelProvider(activity)[InspectionViewModel::class.java] }; idle()
            check(state().record == null || state().record?.submittedAtMillis != null) { "An unfinished rental must be preserved" }
            if (state().record != null || state().pairedBooking != null || state().pairing != null) send(InspectionEvent.DisconnectAccount)
            check(state().emulatorTestAvailable)
            send(InspectionEvent.SetEmulatorTestMode(true))
            val configFile = targetContext.filesDir.resolve("submission-video-config.json")
            val config = JSONObject(configFile.readText()); check(configFile.delete())
            send(InspectionEvent.ConfigureServer(config.getString("serverUrl"), config.getString("deviceToken")))
            send(InspectionEvent.CreateAccountQr); publish(0)
            val commandFile = targetContext.filesDir.resolve("submission-video-command.json")
            var sequence = 0
            val deadline = System.currentTimeMillis() + 30 * 60_000
            while (System.currentTimeMillis() < deadline) {
                if (!commandFile.exists()) { Thread.sleep(100); continue }
                val command = JSONObject(commandFile.readText())
                if (command.getInt("sequence") <= sequence) { Thread.sleep(100); continue }
                sequence = command.getInt("sequence")
                if (command.getString("action") == "finish") { publish(sequence); break }
                val event = when (command.getString("action")) {
                    "connection" -> InspectionEvent.CheckAccountConnection
                    "continue" -> InspectionEvent.ContinueAfterConnection
                    "pickup" -> InspectionEvent.CapturePickup
                    "begin-return" -> InspectionEvent.BeginReturn
                    "retry-windows" -> InspectionEvent.RetryReturnWindowCheck
                    "refresh" -> InspectionEvent.Refresh
                    "replay" -> InspectionEvent.ReplayReturnSeatSpeech
                    "stop" -> InspectionEvent.StopReturnSeatSpeech
                    "dismiss-seats" -> InspectionEvent.DismissReturnSeatNotice
                    "save-return" -> InspectionEvent.CaptureReturn
                    "sync" -> InspectionEvent.SyncServer
                    else -> error("Unsupported submission video event")
                }
                send(event); publish(sequence)
            }
            targetContext.dataDir.resolve("shared_prefs").listFiles().orEmpty().filter { it.extension == "xml" }.forEach {
                check(targetContext.getSharedPreferences(it.nameWithoutExtension, Context.MODE_PRIVATE).edit().commit())
            }
            result.putString("submissionVideo", "Actual Activity and guarded production events; explicit Debug AVD test VIN")
        } catch (error: Exception) { code = Activity.RESULT_CANCELED; result.putString("submissionVideoError", error.stackTraceToString()) }
        finish(code, result)
    }

    // Read on the main thread so callbacks, rendered Compose state and host assertions refer to the same production state.
    private fun state(): InspectionUiState { lateinit var value: InspectionUiState; runOnMainSync { value = viewModel.state.value }; return value }

    // Wait for real SDK/server work rather than advancing the film on an arbitrary delay or invented successful result.
    private fun idle() {
        val deadline = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < deadline) {
            val current = state()
            check(current.cloudError == null) { current.cloudError.orEmpty() }
            if (!current.busy && !current.cloudBusy && !current.accountBusy) return
            Thread.sleep(50)
        }
        error("Production operation timed out")
    }

    // Use exactly the ViewModel event path used by the app buttons, preserving ownership, VIN, window and storage guards.
    private fun send(event: InspectionEvent) { idle(); runOnMainSync { viewModel.dispatch(event) }; idle() }

    // Save private coordination metadata and an actual screenshot; the host must remove tokens before publishing evidence.
    private fun publish(sequence: Int) {
        val current = state(); val record = current.record
        val json = JSONObject().put("sequence", sequence).put("stage", current.stage.name)
            .put("pairingUrl", current.pairing?.pairingUrl ?: JSONObject.NULL).put("recordId", record?.id ?: JSONObject.NULL)
            .put("revision", record?.revision ?: 0).put("syncedRevision", record?.syncedRevision ?: 0)
            .put("paired", current.pairedBooking != null).put("receiptId", record?.receiptId ?: JSONObject.NULL)
            .put("speechStatus", current.returnSeatSpeech.status.name).put("speechStarts", current.returnSeatSpeech.starts)
            .put("speechDone", current.returnSeatSpeech.completions).put("speechText", current.returnSeatSpeech.text)
            .put("openWindows", current.returnWindowDialog?.openWindows?.joinToString() ?: "")
        waitForIdleSync(); Thread.sleep(250)
        val bitmap = checkNotNull(uiAutomation.takeScreenshot())
        targetContext.filesDir.resolve("submission-video-$sequence.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }; bitmap.recycle()
        targetContext.filesDir.resolve("submission-video-state.json").writeText(json.toString())
    }
}
