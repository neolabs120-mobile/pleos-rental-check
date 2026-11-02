package kr.taeuk.pleos.rentalcheck

import kr.taeuk.pleos.rentalcheck.model.InspectionRecord

enum class InspectionStage(val title: String) {
    CONNECT("연결"), PICKUP("인수"), ACTIVE("이용 중"), RETURN("반납"), EVIDENCE("사진·확인"), RESULT("결과");

    companion object {
        fun restored(record: InspectionRecord?): InspectionStage =
            if (record?.returned != null) afterSave(record) else CONNECT

        fun afterSave(record: InspectionRecord): InspectionStage =
            if (record.returned == null) ACTIVE else if (record.submittedAtMillis != null) RESULT else EVIDENCE
    }
}

sealed interface InspectionEvent {
    data class OpenVoiceMemo(val memo: kr.taeuk.pleos.rentalcheck.model.LuggageMemo? = null) : InspectionEvent
    data class CollectLuggage(val space: kr.taeuk.pleos.rentalcheck.model.StorageSpace, val checked: Boolean) : InspectionEvent
    data class RequestChargeDirections(val stop: kr.taeuk.pleos.rentalcheck.navigation.ChargingStop) : InspectionEvent
    data object StartVoiceRecognition : InspectionEvent
    data object StopVoiceRecognition : InspectionEvent
    data object CloseVoiceMemo : InspectionEvent
    data class EditVoiceMemo(val space: kr.taeuk.pleos.rentalcheck.model.StorageSpace?, val item: String) : InspectionEvent
    data object ConfirmVoiceMemo : InspectionEvent
    data class ForgetLuggageMemo(val space: kr.taeuk.pleos.rentalcheck.model.StorageSpace) : InspectionEvent
    data object DismissArrivalPrompt : InspectionEvent
    data object BeginArrivalReturn : InspectionEvent
    data object ReplayArrivalSpeech : InspectionEvent
    data object StopArrivalSpeech : InspectionEvent
    data object RequestReturnLocationPermission : InspectionEvent
    data object RetryReturnLocation : InspectionEvent
    data class RequestReturnDirections(val destination: kr.taeuk.pleos.rentalcheck.navigation.ReturnLocation) : InspectionEvent
    data object OpenReturnNavigation : InspectionEvent
    data object OpenPractice : InspectionEvent
    data class StartPractice(val key: String, val label: String) : InspectionEvent
    data object ClosePractice : InspectionEvent
    data class MarkStorageSpace(val space: kr.taeuk.pleos.rentalcheck.model.StorageSpace, val marked: Boolean) : InspectionEvent
    data object Refresh : InspectionEvent
    data object ContinueAfterConnection : InspectionEvent
    data object CapturePickup : InspectionEvent
    data object CaptureReturn : InspectionEvent
    data object BeginReturn : InspectionEvent
    data object RetryReturnWindowCheck : InspectionEvent
    data object DismissReturnWindowCheck : InspectionEvent
    data object DismissReturnSeatNotice : InspectionEvent
    data object ReplayReturnSeatSpeech : InspectionEvent
    data object StopReturnSeatSpeech : InspectionEvent
    data object ConfirmReturnWindowsManually : InspectionEvent
    data object RetrySwitchOffCheck : InspectionEvent
    data object ContinueWithSwitchOn : InspectionEvent
    data object DismissSwitchOffCheck : InspectionEvent
    data object ReplaySwitchOffSpeech : InspectionEvent
    data object StopSwitchOffSpeech : InspectionEvent
    data object StartNewPickup : InspectionEvent
    data object SyncServer : InspectionEvent
    data object CreateAccountQr : InspectionEvent
    data object CheckAccountConnection : InspectionEvent
    data object DisconnectAccount : InspectionEvent
    data object SubmitReturn : InspectionEvent
    data class CreatePhotoLink(val phase: String) : InspectionEvent
    data class ConfigureServer(val url: String, val token: String) : InspectionEvent
    data class SetEmulatorTestMode(val enabled: Boolean) : InspectionEvent
    data object Back : InspectionEvent
    data class CheckWindowManually(val checked: Boolean) : InspectionEvent
}
