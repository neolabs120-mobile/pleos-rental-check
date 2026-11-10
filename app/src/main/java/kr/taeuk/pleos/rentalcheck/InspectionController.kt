package kr.taeuk.pleos.rentalcheck

import android.content.Context
import kr.taeuk.pleos.rentalcheck.assistance.TripAssistanceController
import kr.taeuk.pleos.rentalcheck.assistance.TripAssistanceState
import java.util.UUID
import kr.taeuk.pleos.rentalcheck.data.InspectionStore
import kr.taeuk.pleos.rentalcheck.model.InspectionRecord
import kr.taeuk.pleos.rentalcheck.model.VehicleSnapshot
import kr.taeuk.pleos.rentalcheck.model.VehicleIdentity
import kr.taeuk.pleos.rentalcheck.model.ReturnWindowCheck
import kr.taeuk.pleos.rentalcheck.model.SwitchOffCheck
import kr.taeuk.pleos.rentalcheck.speech.SwitchOffSpeech
import kr.taeuk.pleos.rentalcheck.model.SeatOccupancy
import kr.taeuk.pleos.rentalcheck.speech.PleosSpeechEngine
import kr.taeuk.pleos.rentalcheck.speech.ReturnSeatSpeech
import kr.taeuk.pleos.rentalcheck.speech.SeatSpeechState
import kr.taeuk.pleos.rentalcheck.speech.*
import kr.taeuk.pleos.rentalcheck.cloud.*
import kr.taeuk.pleos.rentalcheck.navigation.*
import kr.taeuk.pleos.rentalcheck.vehicle.createVehicleEnvironment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

data class InspectionUiState(
    val busy: Boolean = false,
    val current: VehicleSnapshot? = null,
    val record: InspectionRecord? = null,
    val message: String = "차량 상태를 불러올 준비가 되었습니다.",
    val stage: InspectionStage = InspectionStage.CONNECT,
    val connectionDestination: InspectionStage = InspectionStage.PICKUP,
    val windowManuallyChecked: Boolean = false,
    val returnWindowDialog: ReturnWindowCheck? = null,
    val returnSwitchOffDialog: SwitchOffCheck? = null,
    val switchOffAcknowledged: String? = null,
    val switchOffSpeech: SeatSpeechState = SeatSpeechState(),
    val returnOccupiedSeats: List<String> = emptyList(),
    val returnSeatSpeech: SeatSpeechState = SeatSpeechState(),
    val returnMemoNotice: Boolean = false,
    val voiceMemo: VoiceMemoState = VoiceMemoState(),
    val arrivalPrompt: Boolean = false,
    val arrivalSpeech: SeatSpeechState = SeatSpeechState(),
    val retryReturnSave: Boolean = false,
    val errorMessage: String? = null,
    val serverConfigured: Boolean = false,
    val serverUrl: String = "",
    val cloudBusy: Boolean = false,
    val cloudMessage: String = "서버를 설정하면 기록과 사진을 전송할 수 있어요.",
    val cloudError: String? = null,
    val cloudSummary: CloudSummary? = null,
    val photoLink: PhotoLink? = null,
    val accountBusy: Boolean = false,
    val pairing: VehiclePairing? = null,
    val pairedBooking: PairedBooking? = null,
    val accountMessage: String = "휴대전화에서 로그인하고 이 차량 연결을 승인해 주세요.",
    val resultVisible: Boolean = false,
    val emulatorTestAvailable: Boolean = false,
    val emulatorTestEnabled: Boolean = false,
    val assistance: TripAssistanceState = TripAssistanceState(),
    val returnGuide: ReturnGuideState = ReturnGuideState(),
)

/** Retained across Activity recreation. In-flight bounded reads drain before release(). */
class InspectionController(context: Context, recognitionEngine: LuggageRecognitionEngine? = null, navigationEngine: ReturnGuideEngine? = null) {
    private val environment = createVehicleEnvironment(context.applicationContext)
    private val source = environment.source
    private val store = InspectionStore(context.applicationContext)
    private val settings = ServerSettingsStore(context.applicationContext)
    private val pairingPreferences = context.applicationContext.getSharedPreferences("vehicle_pairing", Context.MODE_PRIVATE)
    private var pairingId: String? = pairingPreferences.getString("id", null)
    @Volatile private var serverConfig = settings.load()
    private val server = RentalServer({ serverConfig }, store)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var readJob: Job? = null
    private var pairingPoll: Job? = null
    private var foreground = false
    private var disposing = false
    private var initialized = false
    private var stationaryJob: Job? = null
    private var stationaryReading = false
    private var stationarySnapshot: VehicleSnapshot? = null
    private var stationaryGeneration = 0
    private val arrival = ReturnArrival()
    var onChanged: ((InspectionUiState) -> Unit)? = null
    var state = InspectionUiState()
        private set
    private val voiceMemo = LuggageVoice(recognitionEngine ?: PleosLuggageRecognition(context.applicationContext, scope)) {
        // Recognition modifies only the transient editor; the normal save event owns durable memo changes.
        state = state.copy(voiceMemo = it)
        if (foreground) onChanged?.invoke(state)
    }
    private val arrivalSpeech = ArrivalSpeech(PleosSpeechEngine(context.applicationContext, scope)) {
        // Playback events cannot accept the arrival suggestion or advance the return stage.
        state = state.copy(arrivalSpeech = it)
        if (foreground) onChanged?.invoke(state)
    }
    private val switchOffSpeech = SwitchOffSpeech(PleosSpeechEngine(context.applicationContext, scope)) {
        // Playback status is presentation only; it cannot acknowledge the reminder or save the return.
        state = state.copy(switchOffSpeech = it)
        if (foreground) onChanged?.invoke(state)
    }
    private var spokenSwitchOff: String? = null
    private val returnSpeech = ReturnSeatSpeech(PleosSpeechEngine(context.applicationContext, scope)) {
        // Speech events update presentation only; they must not recursively retrigger automatic playback or mutate inspection evidence.
        state = state.copy(returnSeatSpeech = it)
        if (foreground) onChanged?.invoke(state)
    }
    private val assistance = TripAssistanceController(context.applicationContext, source, scope, ::ensureSource) {
        state = state.copy(assistance = it); publish()
    }
    private val returnGuide = ReturnGuideController(navigationEngine ?: PleosReturnGuideEngine(context.applicationContext, scope), scope, {
        // Location/navigation callbacks update presentation without recursively restarting subscriptions or changing inspection records.
        state = state.copy(returnGuide = it)
        updateArrival()
        if (foreground) onChanged?.invoke(state)
    })

    init {
        try {
            val record = store.load()
            state = state.copy(record = record,
                stage = InspectionStage.restored(record),
                connectionDestination = if (record == null) InspectionStage.PICKUP else InspectionStage.ACTIVE,
                windowManuallyChecked = if (record?.returned != null) record.returnWindowManuallyChecked
                    else record?.pickupWindowManuallyChecked ?: false)
        } catch (e: Exception) {
            state = state.copy(message = "저장된 기록을 읽지 못했습니다: ${e.message}",
                errorMessage = "저장된 점검 기록을 열지 못했어요.")
        }
        state = state.copy(serverConfigured = serverConfig.configured, serverUrl = serverConfig.baseUrl,
            emulatorTestAvailable = environment.testMode.available, emulatorTestEnabled = environment.testMode.enabled,
            cloudMessage = if (serverConfig.configured) "서버 전송 상태를 확인해 주세요." else state.cloudMessage)
    }

    fun start() {
        foreground = true
        assistance.start()
        publish()
        if ((state.pairedBooking == null || state.stage !in listOf(InspectionStage.EVIDENCE, InspectionStage.RESULT)) && state.current == null && readJob?.isActive != true) refresh()
        else if (state.record != null && state.serverConfigured && !state.cloudBusy && !state.busy) syncServer()
        pairingPoll?.cancel()
        pairingPoll = scope.launch {
            while (isActive) {
                if (foreground && pairingId != null && !state.busy && !state.cloudBusy && !state.accountBusy) checkAccount()
                delay(4_000)
            }
        }
        monitorStationaryState()
    }

    fun stop() {
        foreground = false
        stationaryGeneration++
        voiceMemo.attach(null)
        arrivalSpeech.stop()
        stationarySnapshot = null
        if (!stationaryReading) { stationaryJob?.cancel(); stationaryJob = null }
        returnGuide.stop()
        returnSpeech.stop()
        switchOffSpeech.stop()
        assistance.stop()
        pairingPoll?.cancel()
        onChanged = null
        if (readJob?.isActive != true && !stationaryReading) release()
    }

    fun dispose() {
        disposing = true
        stop()
        if (readJob?.isActive != true && !stationaryReading) scope.cancel()
    }

    fun refresh() = collect(CaptureAction.REFRESH)
    fun capturePickup() = collect(CaptureAction.PICKUP)
    fun captureReturn() = collect(CaptureAction.RETURN)

    fun dispatch(event: InspectionEvent) {
        // Keep navigation and storage behind the paired-account guards; only disconnected emulator setup may change test identity.
        val voiceControl = event in listOf(InspectionEvent.DismissReturnSeatNotice, InspectionEvent.StopReturnSeatSpeech,
            InspectionEvent.ReplayReturnSeatSpeech, InspectionEvent.CloseVoiceMemo, InspectionEvent.StopVoiceRecognition,
            InspectionEvent.DismissArrivalPrompt, InspectionEvent.StopArrivalSpeech, InspectionEvent.StopSwitchOffSpeech,
            InspectionEvent.DismissSwitchOffCheck)
        if ((!voiceControl && (state.busy || state.cloudBusy || state.accountBusy)) || disposing) return
        if (state.pairedBooking == null && event !in listOf(InspectionEvent.Refresh,
                InspectionEvent.CreateAccountQr, InspectionEvent.CheckAccountConnection,
                InspectionEvent.DisconnectAccount, InspectionEvent.StartNewPickup) && event !is InspectionEvent.ConfigureServer &&
                event !is InspectionEvent.SetEmulatorTestMode) return
        when (event) {
            is InspectionEvent.OpenVoiceMemo -> if (memoEditable()) { arrivalSpeech.stop(); voiceMemo.open(event.memo); publish() }
            InspectionEvent.StartVoiceRecognition -> if (memoEditable() && state.voiceMemo.open) stationaryAction {
                if (state.voiceMemo.open) { arrivalSpeech.stop(); returnSpeech.stop(); voiceMemo.start() }
            }
            InspectionEvent.StopVoiceRecognition -> voiceMemo.problem("음성 입력을 중지했어요. 내용을 확인하거나 직접 입력해 주세요.")
            InspectionEvent.CloseVoiceMemo -> { voiceMemo.close(); publish() }
            is InspectionEvent.EditVoiceMemo -> voiceMemo.edit(event.space, event.item)
            InspectionEvent.ConfirmVoiceMemo -> if (memoEditable() && state.voiceMemo.open && !state.voiceMemo.capturing) {
                val memo = state.voiceMemo.draft.confirmed()
                if (memo == null) voiceMemo.problem("장소를 선택하고 물건 이름을 40자 이내로 적어 주세요.")
                else stationaryAction { if (state.voiceMemo.open && assistance.rememberItem(memo)) voiceMemo.close() }
            }
            is InspectionEvent.ForgetLuggageMemo -> if (memoEditable()) stationaryAction { assistance.forgetItem(event.space) }
            is InspectionEvent.RequestChargeDirections -> state.pairedBooking?.returnLocation?.let { requestReturnDirections(it, event.stop) }
            is InspectionEvent.CollectLuggage -> if (foreground && state.stage == InspectionStage.RETURN && state.record?.returned == null)
                stationaryAction { assistance.collectItem(event.space, event.checked) }
            InspectionEvent.DismissArrivalPrompt -> { arrival.dismiss(); arrivalSpeech.stop(); state = state.copy(arrivalPrompt = false); publish() }
            InspectionEvent.BeginArrivalReturn -> if (state.arrivalPrompt && state.stage == InspectionStage.ACTIVE) stationaryAction {
                if (state.stage == InspectionStage.ACTIVE && state.returnGuide.distanceMeters?.let { it <= 150 } == true &&
                    state.returnGuide.fix?.let { System.currentTimeMillis() - it.timestampMillis in -5_000..15_000 } == true) {
                    arrival.dismiss(); arrivalSpeech.stop(); state = state.copy(arrivalPrompt = false); dispatch(InspectionEvent.BeginReturn)
                } else state = state.copy(errorMessage = "현재 위치를 다시 확인해 주세요. 반납 점검은 직접 시작할 수 있어요.")
            }
            InspectionEvent.ReplayArrivalSpeech -> if (state.arrivalPrompt && stationaryFor(stationarySnapshot, state.pairedBooking?.vin, System.currentTimeMillis())) arrivalSpeech.play()
            InspectionEvent.StopArrivalSpeech -> arrivalSpeech.stop()
            InspectionEvent.RequestReturnLocationPermission -> Unit // The Activity owns the driver's explicit Android permission prompt.
            InspectionEvent.RetryReturnLocation -> if (foreground && state.stage in listOf(InspectionStage.ACTIVE, InspectionStage.RETURN)) returnGuide.retry()
            is InspectionEvent.RequestReturnDirections -> requestReturnDirections(event.destination)
            InspectionEvent.OpenReturnNavigation -> if (state.stage in listOf(InspectionStage.ACTIVE, InspectionStage.RETURN)) returnGuide.openNavigation()
            InspectionEvent.OpenPractice -> if (state.stage in listOf(InspectionStage.CONNECT, InspectionStage.PICKUP, InspectionStage.ACTIVE)) assistance.openPractice()
            is InspectionEvent.StartPractice -> if (state.assistance.practiceOpen) assistance.startPractice(event.key, event.label)
            InspectionEvent.ClosePractice -> assistance.closePractice()
            is InspectionEvent.MarkStorageSpace -> assistance.mark(event.space, event.marked)
            InspectionEvent.CreateAccountQr -> createAccountQr()
            InspectionEvent.CheckAccountConnection -> scope.launch { checkAccount() }
            InspectionEvent.DisconnectAccount -> disconnectAccount()
            InspectionEvent.Refresh -> refresh()
            InspectionEvent.ContinueAfterConnection -> if (state.stage == InspectionStage.CONNECT &&
                VehicleIdentity.vin(state.current) != null) {
                state = state.copy(stage = state.connectionDestination, errorMessage = null)
                publish()
            }
            InspectionEvent.CapturePickup -> if (state.stage == InspectionStage.PICKUP) capturePickup()
            InspectionEvent.CaptureReturn -> if (state.stage == InspectionStage.RETURN) captureReturn()
            InspectionEvent.BeginReturn -> if (state.record != null && state.record?.returned == null) {
                voiceMemo.close(); arrival.dismiss(); arrivalSpeech.stop()
                returnSpeech.newAttempt()
                spokenSwitchOff = null
                state = state.copy(stage = InspectionStage.RETURN, windowManuallyChecked = false, errorMessage = null,
                    switchOffAcknowledged = null, returnSwitchOffDialog = null)
                refresh()
            }
            InspectionEvent.RetryReturnWindowCheck -> if (state.stage == InspectionStage.RETURN && state.returnWindowDialog != null) {
                if (state.retryReturnSave) captureReturn() else refresh()
            }
            InspectionEvent.RetrySwitchOffCheck -> if (state.stage == InspectionStage.RETURN && state.returnSwitchOffDialog != null) {
                switchOffSpeech.stop()
                if (state.retryReturnSave) captureReturn() else refresh()
            }
            InspectionEvent.ContinueWithSwitchOn -> if (state.stage == InspectionStage.RETURN) state.returnSwitchOffDialog?.let { check ->
                // The renter explicitly accepts this exact set; a different item switched on later prompts again.
                switchOffSpeech.stop()
                val save = state.retryReturnSave
                state = state.copy(switchOffAcknowledged = check.signature, returnSwitchOffDialog = null)
                if (save) captureReturn() else publish()
            }
            InspectionEvent.DismissSwitchOffCheck -> {
                switchOffSpeech.stop()
                state = state.copy(returnSwitchOffDialog = null)
                publish()
            }
            InspectionEvent.ReplaySwitchOffSpeech -> state.returnSwitchOffDialog?.takeIf { foreground && state.stage == InspectionStage.RETURN }
                ?.let { switchOffSpeech.play(SwitchOffCheck.spokenReminder(it.leftOn, it.leftOpen)) }
            InspectionEvent.StopSwitchOffSpeech -> switchOffSpeech.stop()
            InspectionEvent.DismissReturnWindowCheck -> {
                state = state.copy(returnWindowDialog = null)
                publish()
            }
            InspectionEvent.DismissReturnSeatNotice -> {
                state = state.copy(returnOccupiedSeats = emptyList(), returnMemoNotice = false)
                publish()
            }
            InspectionEvent.StopReturnSeatSpeech -> returnSpeech.stop()
            InspectionEvent.ReplayReturnSeatSpeech -> if (canSpeakReturnNotice()) returnSpeech.replay()
            InspectionEvent.ConfirmReturnWindowsManually -> if (state.stage == InspectionStage.RETURN &&
                state.returnWindowDialog?.openWindows?.isEmpty() == true) {
                val save = state.retryReturnSave
                state = state.copy(windowManuallyChecked = true, returnWindowDialog = null)
                if (save) captureReturn() else publish()
            }
            InspectionEvent.StartNewPickup -> {
                if (state.resultVisible) { disconnectAccount(); return }
                if (state.record?.let { it.syncedRevision < it.revision && VehicleIdentity.vin(it.pickup) != null } == true) {
                    state = state.copy(cloudError = "이전 점검 기록을 서버에 전송한 뒤 새 이용을 시작해 주세요.")
                    publish()
                    return
                }
                // Keep the previous record until a new pickup has actually been saved.
                state = state.copy(stage = InspectionStage.CONNECT, connectionDestination = InspectionStage.PICKUP,
                    windowManuallyChecked = false, errorMessage = null)
                refresh()
            }
            InspectionEvent.SyncServer -> syncServer()
            InspectionEvent.SubmitReturn -> syncServer(submit = true)
            is InspectionEvent.CreatePhotoLink -> syncServer(phase = event.phase)
            is InspectionEvent.ConfigureServer -> configureServer(event.url, event.token)
            is InspectionEvent.SetEmulatorTestMode -> setEmulatorTestMode(event.enabled)
            InspectionEvent.Back -> {
                val record = state.record
                state = state.copy(stage = record?.let(InspectionStage::afterSave) ?: InspectionStage.CONNECT, errorMessage = null,
                    returnWindowDialog = null, returnSwitchOffDialog = null, returnOccupiedSeats = emptyList(), returnMemoNotice = false,
                    windowManuallyChecked = if (record?.returned != null) record.returnWindowManuallyChecked
                        else record?.pickupWindowManuallyChecked ?: false)
                publish()
            }
            is InspectionEvent.CheckWindowManually -> {
                if (state.stage == InspectionStage.PICKUP || state.stage == InspectionStage.RETURN) {
                    state = state.copy(windowManuallyChecked = event.checked)
                    publish()
                }
            }
        }
    }

    private fun setEmulatorTestMode(enabled: Boolean) {
        // Do not change identity during a rental or pending QR; a fresh, disconnected inspection must opt in before collecting data.
        if (!environment.testMode.available || state.record != null || pairingId != null || state.pairedBooking != null) {
            state = state.copy(errorMessage = "차량 연결과 이전 점검을 정리한 뒤 에뮬레이터 테스트 모드를 바꿔 주세요.")
            publish(); return
        }
        environment.testMode.setEnabled(enabled)
        state = state.copy(emulatorTestEnabled = environment.testMode.enabled, current = null, errorMessage = null)
        refresh()
    }

    private fun collect(action: CaptureAction) {
        // Use newly read seat evidence for each return check, keeping the reminder independent of the mandatory window and identity guards.
        if (state.busy || state.cloudBusy || disposing) return
        if (action == CaptureAction.RETURN && state.record == null) return
        val manuallyChecked = state.windowManuallyChecked
        var saved = false
        switchOffSpeech.stop(); spokenSwitchOff = null
        state = state.copy(busy = true, errorMessage = null, returnWindowDialog = null, returnSwitchOffDialog = null, returnOccupiedSeats = emptyList(),
            message = "차량에서 현재 상태를 조회하고 있습니다.")
        publish()
        readJob = scope.launch {
            try {
                ensureSource()
                val snapshot = source.readSnapshot()
                assistance.receiveSnapshot(snapshot)
                state = state.copy(current = snapshot)
                val identityError = when {
                    action == CaptureAction.REFRESH -> null
                    VehicleIdentity.vin(snapshot) == null -> "차량 VIN을 받지 못했어요. 차량 연결을 다시 확인해 주세요."
                    state.pairedBooking == null -> "먼저 휴대전화에서 차량 연결을 승인해 주세요."
                    state.pairedBooking?.vin != VehicleIdentity.vin(snapshot) -> "계정에 연결한 대여 차량과 현재 차량이 달라요. 연결을 다시 확인해 주세요."
                    action == CaptureAction.RETURN -> VehicleIdentity.comparisonProblem(state.record!!.pickup, snapshot)
                    else -> null
                }
                if (identityError != null) {
                    state = state.copy(errorMessage = identityError)
                    return@launch
                }
                if (state.stage == InspectionStage.RETURN && action != CaptureAction.PICKUP) {
                    state = state.copy(returnOccupiedSeats = SeatOccupancy.occupiedLabels(snapshot),
                        returnMemoNotice = assistance.state.memory.luggageMemos.isNotEmpty())
                    val windows = ReturnWindowCheck.from(snapshot)
                    if (!windows.canSave(manuallyChecked)) {
                        state = state.copy(returnWindowDialog = windows, retryReturnSave = action == CaptureAction.RETURN,
                            message = if (windows.openWindows.isNotEmpty()) "반납 전에 열린 창문을 닫아 주세요."
                                else "창문 상태를 확인하지 못했어요. 직접 닫힘을 확인해 주세요.")
                        if (action == CaptureAction.RETURN) return@launch
                    }
                    // Windows keep priority; then remind about anything still on and any door or trunk still open, without blocking a confirmed choice.
                    val switches = SwitchOffCheck.from(snapshot)
                    if (state.returnWindowDialog == null && switches.needsPrompt(state.switchOffAcknowledged)) {
                        state = state.copy(returnSwitchOffDialog = switches, retryReturnSave = action == CaptureAction.RETURN,
                            message = if (switches.leftOpen.isEmpty()) "내리기 전에 켜져 있는 항목을 확인해 주세요."
                                else "내리기 전에 열려 있거나 켜져 있는 항목을 확인해 주세요.")
                        if (action == CaptureAction.RETURN) return@launch
                    }
                }
                if (action != CaptureAction.REFRESH && snapshot.availableCount == 0) {
                    state = state.copy(message = "저장하지 않았습니다. 조회에 성공한 차량 항목이 없습니다.",
                        errorMessage = "차량 상태를 읽지 못해 저장하지 않았어요. 연결을 확인하고 다시 시도해 주세요.")
                } else {
                    val updated = when (action) {
                        CaptureAction.PICKUP -> InspectionRecord(UUID.randomUUID().toString(), snapshot,
                            pickupWindowManuallyChecked = manuallyChecked, bookingId = state.pairedBooking!!.id)
                        CaptureAction.RETURN -> state.record!!.copy(returned = snapshot,
                            returnWindowManuallyChecked = manuallyChecked, revision = state.record!!.revision + 1,
                            submittedAtMillis = null, receiptId = null, usageMemory = assistance.state.memory)
                        CaptureAction.REFRESH -> null
                    }
                    if (updated != null) {
                        withContext(Dispatchers.IO) { store.save(updated) }
                        state = state.copy(record = updated, stage = InspectionStage.afterSave(updated),
                            cloudSummary = null, photoLink = null, cloudError = null,
                            cloudMessage = "기기에 임시 저장했어요. 서버 전송을 기다리고 있어요.")
                        if (action == CaptureAction.RETURN) state.pairedBooking?.let { assistance.attach(serverConfig.baseUrl + "|" + it.id, it.vin, updated) }
                        saved = true
                    }
                    val result = "${snapshot.availableCount}/${snapshot.readings.size}개 항목 조회 성공"
                    state = state.copy(message = when (action) {
                        CaptureAction.PICKUP -> "인수 상태를 저장했습니다. $result"
                        CaptureAction.RETURN -> "반납 상태를 저장했습니다. $result"
                        CaptureAction.REFRESH -> if (snapshot.availableCount > 0) result
                            else "차량 데이터를 받지 못했습니다. Pleos 전용 환경과 항목별 오류를 확인해 주세요."
                    })
                }
            } catch (e: Exception) {
                state = state.copy(current = null, message = "조회 또는 저장 실패: ${e.message ?: e.javaClass.simpleName}",
                    errorMessage = "차량 조회 또는 기록 저장을 완료하지 못했어요. 다시 시도해 주세요.")
            } finally {
                state = state.copy(busy = false)
                if (!foreground || disposing) release()
                publish()
                if (disposing && !stationaryReading) scope.cancel()
                else if (saved && state.serverConfigured) syncServer()
            }
        }
    }

    private fun configureServer(url: String, token: String) {
        state = state.copy(cloudBusy = true, cloudError = null)
        publish()
        scope.launch {
            try {
                serverConfig = withContext(Dispatchers.IO) { settings.save(url, token) }
                pairingId = null
                assistance.detach()
                pairingPreferences.edit().remove("id").apply()
                state = state.copy(serverConfigured = true, serverUrl = serverConfig.baseUrl,
                    cloudSummary = null, photoLink = null,
                    pairing = null, pairedBooking = null, resultVisible = false,
                    returnWindowDialog = null, returnSwitchOffDialog = null, returnOccupiedSeats = emptyList(),
                    cloudMessage = "서버 설정을 저장했어요. 기록 전송으로 연결을 확인해 주세요.")
            } catch (e: Exception) {
                state = state.copy(cloudError = e.message?.take(240) ?: "서버 설정을 저장하지 못했어요.")
            } finally {
                state = state.copy(cloudBusy = false)
                publish()
            }
        }
    }

    private fun syncServer(phase: String? = null, submit: Boolean = false) {
        val record = state.record ?: return
        if (state.pairedBooking == null) return
        if (state.cloudBusy || state.busy || disposing) return
        if (!serverConfig.configured) {
            state = state.copy(cloudError = "서버 설정에서 주소와 차량 앱 접근 키를 입력해 주세요.")
            publish()
            return
        }
        state = state.copy(cloudBusy = true, cloudError = null, cloudMessage = "서버에 기록을 전송하고 있어요.")
        publish()
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    var remote = server.sync(record)
                    val link = if (phase != null) server.photoLink(record.id, phase) else null
                    if (submit) remote = server.submit(record)
                    require(remote.revision == record.revision) { "서버 기록 버전이 다릅니다. 다시 확인해 주세요." }
                    require(remote.submittedAtMillis == null || !remote.receiptId.isNullOrBlank()) { "제출 접수 번호가 없는 서버 응답입니다." }
                    require(record.submittedAtMillis == null || (record.submittedAtMillis == remote.submittedAtMillis && record.receiptId == remote.receiptId)) {
                        "저장된 접수 기록과 서버 응답이 다릅니다. 기존 제출 플랫폼을 확인해 주세요."
                    }
                    val updated = record.copy(syncedRevision = remote.revision,
                        submittedAtMillis = remote.submittedAtMillis, receiptId = remote.receiptId)
                    store.save(updated)
                    Triple(updated, remote, link)
                }
                state = state.copy(record = result.first, cloudSummary = result.second,
                    photoLink = result.third ?: state.photoLink,
                    cloudMessage = if (result.first.submittedAtMillis != null) "서버가 점검 제출을 접수했어요."
                        else "차량 관측 기록이 서버에 저장됐어요.",
                    stage = if (result.first.submittedAtMillis != null) InspectionStage.RESULT else state.stage)
                if (result.first.submittedAtMillis != null) state = state.copy(resultVisible = true)
            } catch (e: Exception) {
                state = state.copy(cloudError = e.message?.take(240) ?: "서버 전송에 실패했어요. 다시 시도해 주세요.",
                    cloudMessage = "기기 임시 기록은 유지돼요. 서버 상태를 확인한 뒤 다시 전송해 주세요.")
            } finally {
                state = state.copy(cloudBusy = false)
                publish()
            }
        }
    }

    private fun createAccountQr() {
        val vin = VehicleIdentity.vin(state.current)
        if (!serverConfig.configured || vin == null) {
            state = state.copy(accountMessage = "서버 설정을 마치고 차량 VIN을 받은 뒤 QR을 만들어 주세요.")
            publish(); return
        }
        state = state.copy(accountBusy = true, accountMessage = "차량 연결 QR을 만들고 있어요.")
        publish()
        scope.launch {
            try {
                val pair = withContext(Dispatchers.IO) { server.createPairing(vin, pairingId) }
                pairingId = pair.id
                pairingPreferences.edit().putString("id", pair.id).apply()
                state = state.copy(pairing = pair, pairedBooking = null, resultVisible = false,
                    accountMessage = "QR을 스캔하고 로그인한 뒤 대여 건에서 ‘이 차량 연결 승인’을 눌러 주세요.")
            } catch (e: Exception) {
                state = state.copy(accountMessage = e.message?.take(240) ?: "연결 QR을 만들지 못했어요.")
            } finally { state = state.copy(accountBusy = false); publish() }
        }
    }

    private suspend fun checkAccount() {
        val identifier = pairingId ?: return
        if (!serverConfig.configured || state.accountBusy || state.busy || state.cloudBusy || disposing) return
        state = state.copy(accountBusy = true)
        try {
            val status = withContext(Dispatchers.IO) { server.pairingStatus(identifier) }
            if (status.booking != null) {
                val previous = state.record
                if (previous != null && previous.bookingId != status.booking.id) {
                    if (previous.syncedRevision < previous.revision) {
                        // Preserve an unsent record; never silently assign it to another renter.
                        state = state.copy(accountMessage = "이전 임시 기록의 전송을 마친 뒤 새 대여 건을 연결해 주세요.")
                        return
                    }
                    withContext(Dispatchers.IO) { store.clear() }
                    state = state.copy(record = null, cloudSummary = null, photoLink = null, stage = InspectionStage.CONNECT)
                }
                val firstConnection = state.pairedBooking == null
                state = state.copy(pairedBooking = status.booking, pairing = null,
                    accountMessage = "${status.booking.nickname}님의 대여 건이 연결됐어요.",
                    stage = if (firstConnection && state.record != null) InspectionStage.afterSave(state.record!!) else state.stage,
                    resultVisible = false)
                assistance.attach(serverConfig.baseUrl + "|" + status.booking.id, status.booking.vin, state.record)
            } else if (status.status in listOf("expired", "disconnected")) {
                // The phone may have just submitted. Only reveal the receipt in this live, paired session.
                if (state.pairedBooking != null && state.record?.returned != null) {
                    val record = state.record!!
                    val remote = withContext(Dispatchers.IO) { server.summary(record.id) }
                    if (remote.submittedAtMillis != null && !remote.receiptId.isNullOrBlank()) {
                        val updated = record.copy(syncedRevision = remote.revision,
                            submittedAtMillis = remote.submittedAtMillis, receiptId = remote.receiptId)
                        withContext(Dispatchers.IO) { store.save(updated) }
                        state = state.copy(record = updated, cloudSummary = remote, stage = InspectionStage.RESULT,
                            resultVisible = true, cloudMessage = "서버가 점검 제출을 접수했어요.")
                    }
                }
                assistance.detach()
                state = state.copy(pairedBooking = null, pairing = null,
                    accountMessage = if (status.status == "expired") "QR이 만료됐어요. 새 QR을 만들어 주세요."
                        else "차량 연결이 해제됐어요. 새 이용은 다시 연결해 주세요.")
                pairingId = null
                pairingPreferences.edit().remove("id").apply()
            }
        } catch (e: Exception) {
            // Stop exposing renter screens and luggage history while authorization cannot be checked.
            assistance.detach()
            state = state.copy(pairedBooking = null, accountMessage = e.message?.take(240) ?: "연결 상태를 확인하지 못했어요.")
        } finally { state = state.copy(accountBusy = false); publish() }
    }

    private fun disconnectAccount() {
        // Preserve an unfinished rental ID so reconnecting cannot create a conflicting second pickup for the same booking.
        state = state.copy(accountBusy = true)
        publish()
        scope.launch {
            try {
                val record = state.record
                check(record == null || record.syncedRevision >= record.revision) { "임시 기록을 서버에 전송한 뒤 연결을 해제해 주세요." }
                pairingId?.let { id -> withContext(Dispatchers.IO) { server.disconnectPairing(id) } }
                val retained = record?.takeIf { it.submittedAtMillis == null }
                if (retained == null) withContext(Dispatchers.IO) { store.clear() }
                assistance.detach()
                pairingPreferences.edit().remove("id").apply()
                pairingId = null
                state = state.copy(record = retained, pairedBooking = null, pairing = null, resultVisible = false,
                    returnWindowDialog = null, returnSwitchOffDialog = null, returnOccupiedSeats = emptyList(),
                    cloudSummary = null, photoLink = null, stage = InspectionStage.CONNECT,
                    connectionDestination = retained?.let(InspectionStage::afterSave) ?: InspectionStage.PICKUP, windowManuallyChecked = false,
                    accountMessage = if (retained == null) "차량 연결을 해제했어요. 내 기록은 휴대전화 계정에서 확인할 수 있어요."
                        else "차량 연결을 해제하고 진행 중 점검은 보관했어요. 같은 대여 건으로 다시 승인하면 이어갈 수 있어요.")
            } catch (e: Exception) { state = state.copy(accountMessage = e.message?.take(240) ?: "연결을 해제하지 못했어요.") }
            finally { state = state.copy(accountBusy = false); publish() }
        }
    }

    // Share one SDK lifecycle between bounded inspections and optional foreground assistance.
    private fun ensureSource() { if (!initialized) { source.initialize(); initialized = true } }

    private fun release() {
        if (!initialized || stationaryReading) return
        try {
            source.close()
        } catch (e: Exception) {
            state = state.copy(message = "SDK 해제 중 오류: ${e.message}")
        } finally {
            initialized = false
        }
    }

    // Re-read actual gear and VIN after confirmation so a delayed tap cannot change Navigation while driving or for a different rental.
    private fun requestReturnDirections(destination: ReturnLocation, chargingStop: ChargingStop? = null) {
        if (!foreground || state.stage !in listOf(InspectionStage.ACTIVE, InspectionStage.RETURN) ||
            state.record?.returned != null || state.returnGuide.destination != destination || !state.returnGuide.navigation.connected ||
            state.returnGuide.navigation.busy || readJob?.isActive == true) return
        val booking = state.pairedBooking ?: return
        readJob = scope.launch {
            state = state.copy(busy = true, errorMessage = null); publish()
            try {
                val snapshot = withContext(Dispatchers.IO) { ensureSource(); source.readSnapshot() }
                check(foreground && state.pairedBooking?.id == booking.id && state.record?.bookingId == booking.id) { "현재 연결된 예약을 다시 확인해 주세요." }
                check(state.returnGuide.destination == destination) { "반납 장소가 변경됐어요. 다시 확인해 주세요." }
                check(VehicleIdentity.vin(snapshot) == booking.vin) { "연결한 차량 VIN을 확인하지 못했어요." }
                check(snapshot.find("gear.selection")?.let { it.available && it.value == "GEAR_PARK" } == true) { "길 안내 설정은 P 상태에서 진행해 주세요." }
                if (chargingStop != null) {
                    check(chargingStop in destination.chargingStops) { "예약에 등록된 충전소를 다시 선택해 주세요." }
                    check(kr.taeuk.pleos.rentalcheck.model.BatteryReturn.from(state.record?.pickup, snapshot).shortfall?.let { it > 0 } == true) {
                        "새 배터리 잔량에서 인수 기준의 부족분을 확인하지 못했어요. 차량 상태를 확인해 주세요."
                    }
                }
                state = state.copy(current = snapshot); returnGuide.navigate(chargingStop)
            } catch (error: Exception) { state = state.copy(errorMessage = error.message?.take(240) ?: "길 안내를 요청하지 못했어요.") }
            finally { state = state.copy(busy = false); publish(); if (!foreground || disposing) release(); if (disposing && !stationaryReading) scope.cancel() }
        }
    }

    // Give window/authorization/lifecycle guards priority over audio and let only fresh occupied-seat notices request automatic speech.
    private fun canSpeakReturnNotice(): Boolean = foreground && !disposing && !state.busy && state.pairedBooking != null &&
        state.record?.bookingId == state.pairedBooking?.id && VehicleIdentity.vin(state.current) == state.pairedBooking?.vin &&
        state.stage in listOf(InspectionStage.RETURN, InspectionStage.EVIDENCE) && state.returnWindowDialog == null &&
        state.returnSwitchOffDialog == null && (state.returnOccupiedSeats.isNotEmpty() || state.returnMemoNotice) && ReturnWindowCheck.from(state.current).canSave(state.windowManuallyChecked)

    // Restrict notes to an authorized unreturned rental; a return form or saved return cannot edit the luggage history.
    private fun memoEditable(): Boolean = foreground && !disposing && state.pairedBooking != null &&
        state.stage in listOf(InspectionStage.CONNECT, InspectionStage.PICKUP, InspectionStage.ACTIVE) && state.record?.returned == null &&
        VehicleIdentity.vin(state.current) == state.pairedBooking?.vin

    // Recheck real P and VIN for each capture/save/arrival acceptance, including after slow reads or a background/account change.
    private fun stationaryAction(action: () -> Unit) {
        if (state.busy || state.cloudBusy || state.accountBusy || !foreground) return
        val booking = state.pairedBooking ?: return
        val generation = stationaryGeneration
        state = state.copy(busy = true, errorMessage = null); publish()
        readJob = scope.launch {
            var confirmed = false
            try {
                ensureSource(); val snapshot = source.readStationarySnapshot()
                check(foreground && !disposing && generation == stationaryGeneration && state.pairedBooking?.id == booking.id) { "현재 연결된 예약을 다시 확인해 주세요." }
                check(stationaryFor(snapshot, booking.vin, System.currentTimeMillis())) { "차량 VIN과 P 상태를 확인하지 못했어요. 정차 후 다시 시도해 주세요." }
                stationarySnapshot = snapshot
                confirmed = true
            } catch (error: Exception) {
                if (state.voiceMemo.open) voiceMemo.problem(error.message ?: "정차 상태를 확인하지 못했어요.")
                else state = state.copy(errorMessage = error.message ?: "정차 상태를 확인하지 못했어요.")
            } finally {
                state = state.copy(busy = false); publish()
                if (!foreground || disposing) release()
                if (disposing && !stationaryReading) scope.cancel()
            }
            // Invoke after the probe has fully cleared its busy flag; a new inspection job must retain ownership of its own busy state.
            if (confirmed && foreground && !disposing) action()
        }
    }

    // Poll only the small P/VIN probe while a note is open or a fresh nearby location makes an arrival suggestion relevant.
    private fun monitorStationaryState() {
        if (stationaryJob?.isActive == true) return
        stationaryJob = scope.launch {
            while (foreground && !disposing) {
                if (state.pairedBooking != null && !state.busy && readJob?.isActive != true &&
                    (state.voiceMemo.open || (state.stage == InspectionStage.ACTIVE && state.returnGuide.distanceMeters?.let { it <= 200 } == true))) {
                    stationaryReading = true
                    val generation = stationaryGeneration; val booking = state.pairedBooking?.id
                    try {
                        ensureSource(); val snapshot = source.readStationarySnapshot()
                        stationarySnapshot = snapshot.takeIf { foreground && generation == stationaryGeneration && state.pairedBooking?.id == booking }
                        if (state.voiceMemo.capturing && !stationaryFor(stationarySnapshot, state.pairedBooking?.vin, System.currentTimeMillis()))
                            voiceMemo.problem("P 상태를 확인할 수 없어 음성 입력을 중지했어요. 정차 후 다시 시도해 주세요.")
                    } catch (error: Exception) {
                        stationarySnapshot = null
                        if (state.voiceMemo.capturing) voiceMemo.problem("차량 상태를 읽지 못해 음성 입력을 중지했어요.")
                    } finally { stationaryReading = false }
                    updateArrival()
                    if (foreground) onChanged?.invoke(state)
                }
                if (foreground && !disposing) delay(3_000)
            }
            if (readJob?.isActive != true) release()
            if (disposing && readJob?.isActive != true) scope.cancel()
        }
    }

    // A nearby/P prompt is optional and once per rental/destination; it never writes a return record or interprets GPS as the correct parking space.
    private fun updateArrival() {
        val booking = state.pairedBooking
        val eligible = foreground && !disposing && !state.busy && !state.cloudBusy &&
            state.stage == InspectionStage.ACTIVE && state.record?.returned == null && !state.voiceMemo.open && !state.assistance.practiceOpen
        val announce = arrival.update(booking?.let { serverConfig.baseUrl + "|" + it.id },
            state.returnGuide.copy(destination = booking?.returnLocation), stationarySnapshot, booking?.vin, System.currentTimeMillis(), eligible)
        state = state.copy(arrivalPrompt = arrival.visible)
        if (announce) arrivalSpeech.play()
        else if (!arrival.visible) arrivalSpeech.stop()
    }

    // Share one authoritative notice state with Compose and speech; cloud polling must not cause the same seat set to be announced repeatedly.
    private fun publish() {
        voiceMemo.attach(if (memoEditable()) serverConfig.baseUrl + "|" + state.pairedBooking!!.id + "|" + state.pairedBooking!!.vin else null)
        val guideEnabled = foreground && !disposing && state.stage in listOf(InspectionStage.ACTIVE, InspectionStage.RETURN) &&
            state.record?.returned == null && state.record?.bookingId == state.pairedBooking?.id &&
            state.pairedBooking != null && VehicleIdentity.vin(state.current) == state.pairedBooking?.vin
        returnGuide.attach(if (guideEnabled) serverConfig.baseUrl + "|" + state.pairedBooking!!.id else null,
            if (guideEnabled) state.pairedBooking?.returnLocation else null)
        val identity = state.record?.let { serverConfig.baseUrl + "|" + it.bookingId + "|" + it.id }
        updateArrival()
        val switchOff = state.returnSwitchOffDialog
        if (switchOff != null && foreground && !disposing && !state.busy && state.stage == InspectionStage.RETURN &&
            switchOff.signature != spokenSwitchOff) {
            spokenSwitchOff = switchOff.signature
            switchOffSpeech.play(SwitchOffCheck.spokenReminder(switchOff.leftOn, switchOff.leftOpen))
        }
        returnSpeech.update(identity, state.returnOccupiedSeats, canSpeakReturnNotice(),
            if (state.returnMemoNotice) state.assistance.memory.luggageMemos else emptyList())
        if (foreground) onChanged?.invoke(state)
    }
    private enum class CaptureAction { REFRESH, PICKUP, RETURN }
}
