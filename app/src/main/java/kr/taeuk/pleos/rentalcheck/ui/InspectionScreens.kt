package kr.taeuk.pleos.rentalcheck.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kr.taeuk.pleos.rentalcheck.InspectionEvent
import kr.taeuk.pleos.rentalcheck.InspectionStage
import kr.taeuk.pleos.rentalcheck.InspectionUiState
import kr.taeuk.pleos.rentalcheck.model.*

/** The controller owns navigation and records; composition only renders and emits events. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RentalCheckScreen(state: InspectionUiState, onEvent: (InspectionEvent) -> Unit) {
    var selectedDetail by rememberSaveable { mutableStateOf<String?>(null) }
    var expandedResult by rememberSaveable(state.record?.id) { mutableStateOf(false) }
    var confirmReplace by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var extrasExpanded by rememberSaveable { mutableStateOf(false) }
    val snapshot = if (state.stage in listOf(InspectionStage.EVIDENCE, InspectionStage.RESULT)) state.record?.returned else state.current
    val canGoBack = state.stage == InspectionStage.RETURN ||
        (state.stage in listOf(InspectionStage.CONNECT, InspectionStage.PICKUP) && state.record != null)
    BackHandler(enabled = canGoBack && !state.busy && selectedDetail == null) { onEvent(InspectionEvent.Back) }
    BackHandler(enabled = (state.busy || state.cloudBusy) && selectedDetail == null) { }
    val openDetail: (DetailGroup) -> Unit = { selectedDetail = it.name }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { AppHeader(state, onEvent, onSettings = { showSettings = true }) },
        bottomBar = {
            ActionBar(state, expandedResult, onPrimary = {
                when (state.stage) {
                    InspectionStage.CONNECT -> onEvent(if (VehicleIdentity.vin(state.current) != null)
                        InspectionEvent.ContinueAfterConnection else InspectionEvent.Refresh)
                    InspectionStage.PICKUP -> if (state.record == null) onEvent(InspectionEvent.CapturePickup)
                        else confirmReplace = true
                    InspectionStage.ACTIVE -> onEvent(InspectionEvent.BeginReturn)
                    InspectionStage.RETURN -> onEvent(InspectionEvent.CaptureReturn)
                    InspectionStage.EVIDENCE -> onEvent(InspectionEvent.SubmitReturn)
                    InspectionStage.RESULT -> expandedResult = !expandedResult
                }
            }, onBack = if (canGoBack) ({ onEvent(InspectionEvent.Back) }) else null)
        },
    ) { insets ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(insets)) {
            val wide = maxWidth >= 840.dp
            val dashboard = maxWidth >= 1080.dp && LocalDensity.current.fontScale <= 1.3f && state.stage == InspectionStage.ACTIVE
            val seats: @Composable () -> Unit = {
                SeatOccupancyCard(if (state.record?.returned != null) SeatOccupancy.readings(state.record.returned)
                    else state.assistance.seatReadings.ifEmpty { SeatOccupancy.readings(snapshot) }, state.record?.returned != null)
            }
            val memory: @Composable () -> Unit = {
                val editable = state.record?.returned == null && state.stage in listOf(InspectionStage.CONNECT, InspectionStage.PICKUP, InspectionStage.ACTIVE)
                UsageMemoryCard(state.record?.takeIf { it.returned != null }?.usageMemory ?: state.assistance.memory,
                    onMark = if (editable) ({ space, marked -> onEvent(InspectionEvent.MarkStorageSpace(space, marked)) }) else null,
                    problem = state.assistance.problem)
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(if (wide) 20.dp else 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                item("title") { ContentWidth { ScreenHeading(state) } }
                if (state.stage == InspectionStage.RETURN) item("return-location") {
                    ContentWidth { ReturnLocationCard(state, onEvent) }
                }
                if (state.stage in listOf(InspectionStage.CONNECT, InspectionStage.PICKUP)) item("practice") {
                    ContentWidth { FamiliarizationCard(state, onEvent) }
                }

                state.errorMessage?.let { error -> item("error") {
                    ContentWidth { Notice("처리를 완료하지 못했어요", error, true) }
                } }
                if (state.stage !in listOf(InspectionStage.CONNECT, InspectionStage.EVIDENCE, InspectionStage.RESULT) && !state.busy && state.current?.availableCount.let { it == null || it == 0 }) {
                    item("connection") { ContentWidth {
                        Notice("차량 정보를 기다리고 있어요", "차량 연결 후 ‘다시 읽기’를 눌러 주세요. 확인하지 못한 값은 정상으로 판단하지 않아요.")
                    } }
                }
                item("body") { ContentWidth {
                    when (state.stage) {
                        InspectionStage.CONNECT -> ConnectionContent(state, wide, openDetail,
                            retry = { onEvent(InspectionEvent.Refresh) })
                        InspectionStage.PICKUP -> TwoColumns(wide,
                            left = {
                                EnergyCard(snapshot, onClick = { openDetail(DetailGroup.ENERGY) })
                                WarningCard(snapshot, null, openDetail)
                            }, right = { VehicleCard(snapshot, state.windowManuallyChecked, openDetail) })
                        InspectionStage.ACTIVE -> if (dashboard) Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                            Column(Modifier.weight(1.45f), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                                ReturnLocationCard(state, onEvent)
                                TripSummaryCard(state) { openDetail(DetailGroup.ALL) }
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(20.dp)) { seats(); memory() }
                        } else Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                            ReturnLocationCard(state, onEvent); TripSummaryCard(state) { openDetail(DetailGroup.ALL) }; seats(); memory()
                        }
                        InspectionStage.RETURN -> TwoColumns(wide,
                            left = {
                                AttentionCard(state, snapshot, openDetail)
                                EnergyCard(snapshot, state.record?.pickup, onClick = { openDetail(DetailGroup.ENERGY) })
                            }, right = { VehicleCard(snapshot, state.windowManuallyChecked, openDetail, showReturnCheck = true) })
                        InspectionStage.RESULT -> ResultContent(state, wide, expandedResult, openDetail)
                        InspectionStage.EVIDENCE -> EvidenceContent(state, wide, onEvent)
                    }
                } }
                if (state.stage != InspectionStage.ACTIVE) item("seat-occupancy") { ContentWidth { seats() } }
                if (state.stage != InspectionStage.ACTIVE) item("luggage-memory") { ContentWidth {
                    if (state.stage == InspectionStage.ACTIVE) TwoColumns(wide, left = { memory() },
                        right = { FamiliarizationCard(state, onEvent) }) else memory()
                } }
                item("platform") { ContentWidth {
                    if (state.stage == InspectionStage.ACTIVE) {
                        OutlinedButton(onClick = { extrasExpanded = !extrasExpanded }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                            shape = RoundedCornerShape(14.dp)) { Text(if (extrasExpanded) "사진 · 차량 연습 · 전송 상태 접기  −" else "사진 · 차량 연습 · 전송 상태  ＋") }
                        if (extrasExpanded) Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            TwoColumns(wide, left = { FamiliarizationCard(state, onEvent) },
                                right = { CloudStatusCard(state, onEvent, onSettings = { showSettings = true }) })
                            PhotoLinkCard(state.photoLink?.takeIf { it.phase == "pickup" }, state.cloudBusy,
                                title = "인수 사진 첨부 (선택)", onCreate = { onEvent(InspectionEvent.CreatePhotoLink("pickup")) })
                        }
                    } else CloudStatusCard(state, onEvent, onSettings = { showSettings = true })
                } }
                item("secondary") { ContentWidth {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { openDetail(DetailGroup.ALL) }) { Text("전체 수집 내역") }
                        if (state.stage == InspectionStage.RESULT ||
                            (state.stage == InspectionStage.EVIDENCE && VehicleIdentity.vin(state.record?.pickup) == null)) TextButton(
                            onClick = { onEvent(InspectionEvent.StartNewPickup) }, enabled = !state.busy,
                        ) { Text("새 인수 점검") }
                    }
                    Muted("항목별 수집 시각은 다를 수 있어요. 기기에 임시 보관한 기록을 서버에 전송해요.")
                } }
            }
        }
    }
    if (state.assistance.practiceOpen) FamiliarizationDialog(state, onEvent)
    if (showSettings) ServerSettingsDialog(state.serverUrl,
        onDismiss = { showSettings = false }, onSave = { url, token ->
            showSettings = false; onEvent(InspectionEvent.ConfigureServer(url, token))
        })
    if (state.stage == InspectionStage.RETURN && !state.busy) {
        state.returnWindowDialog?.let { ReturnWindowDialog(it, onEvent) }
    }
    if (state.stage in listOf(InspectionStage.RETURN, InspectionStage.EVIDENCE) && !state.busy &&
        state.returnWindowDialog == null && state.returnOccupiedSeats.isNotEmpty()) {
        ReturnSeatNotice(state.returnOccupiedSeats, state.returnSeatSpeech, onEvent)
    }
    selectedDetail?.let { name ->
        ModalBottomSheet(onDismissRequest = { selectedDetail = null }) {
            ReadingDetails(DetailGroup.valueOf(name), snapshot, state, onEvent)
        }
    }
    if (confirmReplace) AlertDialog(
        onDismissRequest = { confirmReplace = false }, title = { Text("새 인수 기록으로 교체할까요?") },
        text = { Text("새 조회와 저장에 성공하면 기존 인수·반납 기록이 교체돼요. 같은 차량인지 확인해 주세요.") },
        confirmButton = { TextButton(enabled = !state.busy, onClick = {
            confirmReplace = false; onEvent(InspectionEvent.CapturePickup)
        }) { Text("새로 저장") } },
        dismissButton = { TextButton(onClick = { confirmReplace = false }) { Text("취소") } },
    )
}

// Keep brand, genuine connection status and a noninteractive progress trail distinct from the account/settings actions.
@Composable
private fun AppHeader(state: InspectionUiState, onEvent: (InspectionEvent) -> Unit, onSettings: () -> Unit) {
    Surface {
        Column(Modifier.statusBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            BoxWithConstraints {
                val compact = maxWidth < 720.dp
                val actions: @Composable () -> Unit = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (state.pairedBooking != null || state.resultVisible) TextButton(onClick = { onEvent(InspectionEvent.DisconnectAccount) },
                            enabled = !state.busy && !state.cloudBusy && !state.accountBusy) { Text("연결 해제") }
                        TextButton(onClick = onSettings, enabled = !state.busy && !state.cloudBusy) { Text("설정") }
                        if (state.stage !in listOf(InspectionStage.CONNECT, InspectionStage.EVIDENCE, InspectionStage.RESULT)) OutlinedButton(
                            onClick = { onEvent(InspectionEvent.Refresh) }, enabled = !state.busy && !state.cloudBusy) { Text("다시 읽기") }
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        RentalBrand()
                        if (!compact) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text("차량 연결 · 정차 후 이용", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                state.pairedBooking?.let { Text("예약 ${it.number}", style = MaterialTheme.typography.bodySmall) }
                            }
                            actions()
                        }
                    }
                    if (compact) actions()
                }
            }
            JourneyProgress(state.stage)
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

// Make the current task the visual entry point, preserving the exact workflow stage and distinguishing stored results from live reads.
@Composable
private fun ScreenHeading(state: InspectionUiState) {
    val (title, description) = when (state.stage) {
        InspectionStage.CONNECT -> "먼저 차량 연결을 확인해 주세요" to "이 화면이 실행 중인 차량에서 점검 정보를 읽을 수 있는지 확인해요."
        InspectionStage.PICKUP -> "인수할 차량을 확인하세요" to "출발 전 상태를 저장해 반납할 때 비교해요."
        InspectionStage.ACTIVE -> "이용 현황" to "반납 장소와 소지품 메모를 한곳에서 확인하세요."
        InspectionStage.RETURN -> "반납 전, 달라진 점을 확인하세요" to "인수 때와의 변화와 확인이 필요한 항목을 살펴보세요."
        InspectionStage.EVIDENCE -> "사진과 직접 확인 내역을 보내 주세요" to "휴대전화에서 창문·소지품·외관 사진을 첨부하고 확인 내용을 전송해요."
        InspectionStage.RESULT -> "점검 기록을 서버에 제출했어요" to "인수 ${inspectionTime(state.record?.pickup?.completedAtMillis ?: 0)} → 반납 점검 ${inspectionTime(state.record?.returned?.completedAtMillis ?: 0)}"
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// Keep the guarded primary action large and anchored so a visual refresh cannot change storage or submission eligibility.
@Composable
private fun ActionBar(state: InspectionUiState, expanded: Boolean, onPrimary: () -> Unit, onBack: (() -> Unit)?) {
    val label = when (state.stage) {
        InspectionStage.CONNECT -> if (VehicleIdentity.vin(state.current) != null)
            if (state.connectionDestination == InspectionStage.ACTIVE) "이용 이어가기" else "인수 점검 시작"
            else "차량 연결 확인"
        InspectionStage.PICKUP -> "인수 상태 저장"
        InspectionStage.ACTIVE -> "반납 점검 시작"
        InspectionStage.RETURN -> "반납 점검 저장"
        InspectionStage.EVIDENCE -> "점검 기록 제출"
        InspectionStage.RESULT -> if (expanded) "세부 기록 접기" else "세부 기록 보기"
    }
    val canSave = VehicleIdentity.vin(state.current) != null
    val enabled = !state.busy && !state.cloudBusy && when (state.stage) {
        InspectionStage.EVIDENCE -> state.cloudSummary?.readyToSubmit(state.record?.revision ?: 0) == true
        InspectionStage.CONNECT, InspectionStage.ACTIVE, InspectionStage.RESULT -> true
        else -> canSave
    }
    Surface(shadowElevation = 1.dp) {
        BoxWithConstraints(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp)) {
            val wide = maxWidth >= 800.dp
            val actions: @Composable () -> Unit = {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (onBack != null) OutlinedButton(onClick = onBack, enabled = !state.busy && !state.cloudBusy,
                        modifier = Modifier.heightIn(min = 52.dp)) { Text("이전") }
                    Button(onClick = onPrimary, enabled = enabled,
                        modifier = if (wide) Modifier.widthIn(min = 280.dp).heightIn(min = 60.dp)
                            else Modifier.weight(1f).heightIn(min = 60.dp), shape = RoundedCornerShape(16.dp)) {
                        Text(if (state.busy) "차량 상태 수집 중…" else if (state.cloudBusy) "서버 처리 중…" else label)
                    }
                }
            }
            val caption: @Composable () -> Unit = {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(when (state.stage) {
                        InspectionStage.CONNECT -> "차량 VIN을 받은 뒤 다음 단계로 진행할 수 있어요."
                        InspectionStage.PICKUP -> "기기에 임시 보관한 뒤 서버로 전송해요."
                        InspectionStage.ACTIVE -> "주차 후 반납 점검을 시작하세요."
                        InspectionStage.RETURN -> "차량 상태를 새로 읽은 뒤 사진 첨부로 진행해요."
                        InspectionStage.EVIDENCE -> "각 항목 사진 1장과 직접 확인 내역이 필요해요."
                        InspectionStage.RESULT -> "사업자의 반납 처리는 별도로 진행해요."
                    }, style = MaterialTheme.typography.bodySmall)
                    val saved = if (state.stage in listOf(InspectionStage.EVIDENCE, InspectionStage.RESULT)) state.record?.returned else state.current
                    if (state.stage != InspectionStage.ACTIVE) saved?.let { Muted("수집 ${inspectionTime(it.completedAtMillis)}") }
                }
            }
            if (wide) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Box(Modifier.weight(1f)) { caption() }; actions()
            } else Column(verticalArrangement = Arrangement.spacedBy(9.dp)) { caption(); actions() }
        }
    }
}

@Composable
private fun ConnectionContent(state: InspectionUiState, wide: Boolean, open: (DetailGroup) -> Unit, retry: () -> Unit) {
    val snapshot = state.current
    val received = snapshot?.availableCount ?: 0
    val unsupported = snapshot?.readings?.count { it.status == ReadStatus.UNSUPPORTED } ?: 0
    val unknown = snapshot?.readings?.count { !it.available && it.status != ReadStatus.UNSUPPORTED } ?: 0
    val denied = snapshot?.readings?.count { it.status == ReadStatus.PERMISSION_DENIED } ?: 0
    TwoColumns(wide, left = {
        Panel {
            PanelTitle("차량 연결 확인")
            val title = when {
                state.busy -> "차량 정보를 확인하고 있어요"
                received > 0 && VehicleIdentity.vin(snapshot) == null -> "차량 VIN을 확인하지 못했어요"
                received > 0 && unknown + unsupported > 0 -> "일부 차량 정보를 받았어요"
                received > 0 -> "차량 정보를 받았어요"
                denied > 0 -> "정보 조회 권한을 확인해 주세요"
                snapshot != null || state.errorMessage != null -> "차량 정보를 받지 못했어요"
                else -> "차량 화면에서 연결을 확인하세요"
            }
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            Text(when {
                state.busy -> "차량 서비스에 접근해 지원 항목과 현재 값을 조회하고 있어요. 잠시 기다려 주세요."
                received > 0 && VehicleIdentity.vin(snapshot) == null -> "일부 정보를 받았지만 차량을 구분할 VIN이 필요해요. 차량 연결을 다시 확인해 주세요."
                received > 0 -> "받은 항목으로 점검을 진행할 수 있어요. 미지원·확인 불가 항목도 결과에 함께 표시해요."
                denied > 0 -> "차량 데이터 조회가 제한됐어요. 차량 환경의 앱 접근 권한을 확인한 뒤 다시 시도해 주세요."
                else -> "차량의 Pleos Connect 화면에서 앱을 실행해 주세요. 일반 휴대전화에서는 차량 정보를 받을 수 없어요."
            }, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(16.dp))
            if (snapshot != null && !state.busy) {
                SmallComparison("차량 식별", VehicleIdentity.masked(snapshot))
                SmallComparison("수집된 항목", "${received}개")
                SmallComparison("차량에서 미지원", "${unsupported}개")
                SmallComparison("확인하지 못한 항목", "${unknown}개")
                if (denied > 0) Muted("이 중 ${denied}개는 조회 권한이 제한됐어요.")
            }
            Muted("문·창문·경고등·잔량 정보를 읽고, 인수·반납 시점의 상태를 이 기기에 기록해요.")
            if (snapshot != null && !state.busy) TextButton(onClick = { open(DetailGroup.ALL) },
                contentPadding = PaddingValues(0.dp)) { Text("연결 확인 결과 자세히  ›") }
            if (received > 0 && !state.busy) TextButton(onClick = retry,
                contentPadding = PaddingValues(0.dp)) { Text("차량 정보 다시 확인") }
        }
    }, right = {
        Panel {
            PanelTitle("점검할 차량 정보")
            listOf(DetailGroup.ENERGY, DetailGroup.DOORS, DetailGroup.WINDOWS, DetailGroup.WARNINGS).forEachIndexed { index, group ->
                val readings = group.readings(snapshot)
                StatusRow(group.title, when {
                    state.busy -> "조회 중"
                    readings.isEmpty() -> "확인 대기"
                    else -> "수집 ${readings.count { it.available }}개 · 전체 ${readings.size}개"
                }, attention = !state.busy && readings.isNotEmpty() && readings.none { it.available },
                    onClick = { open(group) })
                if (index < 3) Spacer(Modifier.height(10.dp))
            }
        }
    })
}

@Composable
private fun EnergyCard(snapshot: VehicleSnapshot?, before: VehicleSnapshot? = null, onClick: () -> Unit) {
    Panel {
        PanelTitle(if (before == null) "기록할 차량 정보" else "인수 때와 비교", "마지막 수집값")
        Muted(VehicleIdentity.masked(snapshot))
        Spacer(Modifier.height(10.dp))
        val energy = energyReadings(snapshot)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth >= 380.dp) Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                Column(Modifier.weight(1f)) { EnergyValues(energy, before) }
                Column(Modifier.weight(1.2f)) { DistanceValue(snapshot, before) }
            } else Column {
                EnergyValues(energy, before)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(12.dp))
                DistanceValue(snapshot, before)
            }
        }
        TextButton(onClick = onClick, contentPadding = PaddingValues(0.dp)) { Text("차량 정보 상세  ›") }
    }
}

@Composable
private fun EnergyValues(energy: List<VehicleReading>, before: VehicleSnapshot?) {
    if (energy.isEmpty()) Metric("연료 · 배터리", "확인 불가")
    energy.forEach { reading ->
        Metric(reading.label, reading.number?.let { displayNumber(it) + "%" } ?: "확인 불가")
        reading.number?.let { LinearProgressIndicator(progress = { (it / 100.0).toFloat().coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp)) }
        before?.find(reading.key)?.let { Muted("인수 시 ${valueText(it)}") }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun DistanceValue(snapshot: VehicleSnapshot?, before: VehicleSnapshot?) {
    Metric("누적 주행거리", snapshot?.find("odometer.km")?.number?.let { displayNumber(it) + " km" } ?: "확인 불가")
    before?.find("odometer.km")?.let { Muted("인수 시 ${valueText(it)}") }
}

@Composable
private fun WarningCard(snapshot: VehicleSnapshot?, before: VehicleSnapshot?, open: (DetailGroup) -> Unit) {
    val warnings = warningCodes(snapshot)
    val report = if (snapshot != null && before != null) SnapshotComparison.compare(before, snapshot) else null
    Panel {
        PanelTitle("경고등 확인")
        val new = report?.newWarningCodes
        StatusRow(when {
            warnings == null -> "경고등 상태 확인 불가"
            warnings.isEmpty() -> "수집된 경고등 · 켜진 항목 없음"
            new != null && new.isNotEmpty() -> "새로 켜진 경고등 ${new.size}개"
            else -> "켜진 경고등 ${warnings.size}개"
        }, if (warnings.isNullOrEmpty()) "수집된 범위의 결과를 기록해요."
            else warnings.joinToString { warningName(it) },
            attention = warnings == null || warnings.isNotEmpty(), onClick = { open(DetailGroup.WARNINGS) })
    }
}

@Composable
private fun VehicleCard(snapshot: VehicleSnapshot?, manuallyChecked: Boolean, open: (DetailGroup) -> Unit,
    showReturnCheck: Boolean = false) {
    Panel {
        PanelTitle("문 · 창문 확인")
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            CarSilhouette()
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("문 개폐 상태", style = MaterialTheme.typography.titleSmall)
                Text(doorSummary(snapshot), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { open(DetailGroup.DOORS) }, contentPadding = PaddingValues(0.dp)) { Text("영역별 상태 보기  ›") }
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outlineVariant)
        if (showReturnCheck) ReturnWindowStatus(snapshot, manuallyChecked, open)
        else StatusRow("창문 닫힘 · 직접 확인 필요",
            if (manuallyChecked) "직접 확인 완료 · 차량 수집값과 별도 기록"
            else "직접 확인 내역은 차량 조회 결과와 별도로 기록해요.",
            attention = true, onClick = { open(DetailGroup.WINDOWS) })
    }
}

@Composable
private fun ReturnWindowStatus(snapshot: VehicleSnapshot?, manuallyChecked: Boolean, open: (DetailGroup) -> Unit) {
    val check = ReturnWindowCheck.from(snapshot)
    StatusRow(when {
        check.openWindows.isNotEmpty() -> "열린 창문 ${check.openWindows.size}개 · 닫아 주세요"
        check.allClosed -> "조회한 창문 ${check.closedCount}개 · 모두 닫힘"
        manuallyChecked -> "창문 닫힘 · 직접 확인 완료"
        else -> "창문 닫힘 · 직접 확인 필요"
    }, when {
        check.openWindows.isNotEmpty() -> check.openWindows.joinToString() + " · 닫은 뒤 다시 확인해 주세요."
        check.allClosed -> "마지막 조회 결과예요. 반납 점검 저장 시 다시 조회해요."
        manuallyChecked -> "차량에서 확인하지 못한 상태는 직접 확인 내역으로 기록해요."
        else -> "차량에서 닫힘을 확인하지 못했어요. 창문을 직접 확인해 주세요."
    }, attention = !check.allClosed && (!manuallyChecked || check.openWindows.isNotEmpty()),
        onClick = { open(DetailGroup.WINDOWS) })
}

@Composable
private fun CarSilhouette() {
    val body = MaterialTheme.colorScheme.surfaceVariant
    val window = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .5f)
    val roof = MaterialTheme.colorScheme.surface
    Box(Modifier.width(92.dp).height(164.dp).clearAndSetSemantics { }, contentAlignment = Alignment.TopCenter) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width; val h = size.height
            drawRoundRect(body, Offset(w * .16f, h * .04f), Size(w * .68f, h * .92f), CornerRadius(w * .25f))
            drawRoundRect(window, Offset(w * .27f, h * .23f), Size(w * .46f, h * .53f), CornerRadius(8.dp.toPx()))
            drawRoundRect(roof, Offset(w * .30f, h * .34f), Size(w * .40f, h * .31f), CornerRadius(5.dp.toPx()))
            listOf(.23f, .73f).forEach { y ->
                drawRoundRect(window, Offset(w * .09f, h * y), Size(w * .07f, h * .13f), CornerRadius(2.dp.toPx()))
                drawRoundRect(window, Offset(w * .84f, h * y), Size(w * .07f, h * .13f), CornerRadius(2.dp.toPx()))
            }
        }
        Text("앞", modifier = Modifier.padding(top = 10.dp), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AttentionCard(state: InspectionUiState, snapshot: VehicleSnapshot?, open: (DetailGroup) -> Unit) {
    Panel {
        PanelTitle("먼저 확인하세요")
        val before = state.record?.pickup
        val report = if (before != null && snapshot != null) SnapshotComparison.compare(before, snapshot) else null
        val new = report?.newWarningCodes
        val current = warningCodes(snapshot)
        StatusRow(when {
            current == null -> "경고등 상태 확인 불가"
            new == null -> "경고등 변화 비교 불가"
            new.isNotEmpty() -> "새로 켜진 경고등 ${new.size}개"
            current.isNotEmpty() -> "인수 때부터 켜진 경고등 ${current.size}개"
            else -> "수집된 경고등 · 새로 켜진 항목 없음"
        }, (new?.takeIf { it.isNotEmpty() } ?: current)?.takeIf { it.isNotEmpty() }
            ?.joinToString { warningName(it) } ?: "인수 기록과 수집된 경고등을 비교해요.",
            attention = current == null || current.isNotEmpty() || new == null,
            onClick = { open(DetailGroup.WARNINGS) })
        HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
        ReturnWindowStatus(snapshot, state.windowManuallyChecked, open)
        Muted("경고와 확인 불가 항목도 점검 기록에 포함해요.")
    }
}

@Composable
private fun ResultContent(state: InspectionUiState, wide: Boolean, expanded: Boolean, open: (DetailGroup) -> Unit) {
    val record = state.record ?: return
    val returned = record.returned ?: return
    val report = remember(record) { SnapshotComparison.compare(record.pickup, returned) }
    TwoColumns(wide, left = {
        Panel {
            PanelTitle("이번 이용의 변화", "서버 접수됨")
            Metric("주행거리", report.distanceKm?.let { displayNumber(it) + " km" } ?: "비교 불가")
            report.distanceProblem?.let { Muted(it) }
            Spacer(Modifier.height(18.dp))
            if (report.fuelPercentagePointChange != null) SmallComparison("연료 잔량 변화", signedNumber(report.fuelPercentagePointChange) + "%p")
            if (report.batteryPercentagePointChange != null) SmallComparison("배터리 충전율 변화", signedNumber(report.batteryPercentagePointChange) + "%p")
            if (report.fuelPercentagePointChange == null && report.batteryPercentagePointChange == null) SmallComparison("연료 · 배터리 변화", "비교 불가")
            SmallComparison("새로 켜진 경고등", report.newWarningCodes?.let { "${it.size}개" } ?: "비교 불가")
            SmallComparison("비교 불가 항목", "${report.changes.count { it.kind == ChangeKind.NOT_COMPARABLE }}개")
        }
    }, right = {
        WarningCard(returned, record.pickup, open)
        Panel {
            PanelTitle("서버 접수 기록")
            SmallComparison("차량", VehicleIdentity.masked(record.pickup))
            SmallComparison("제출 시각", inspectionTime(record.submittedAtMillis ?: 0))
            record.receiptId?.let { Muted("접수 번호 $it") }
            state.cloudSummary?.let { summary ->
                SmallComparison("사진", "${summary.photos.count { it.phase == "return" }}장")
                SmallComparison("직접 확인", if (summary.readyToSubmit(record.revision)) "3개 항목 확인" else "내역 확인 필요")
            } ?: Muted("사진과 직접 확인 내역은 서버 상태를 다시 확인해 주세요.")
        }
    })
    if (expanded) {
        Spacer(Modifier.height(18.dp))
        Panel {
            PanelTitle("저장된 인수 · 반납 비교")
            report.changes.forEach { change ->
                Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(change.label, style = MaterialTheme.typography.titleSmall)
                    Text("${valueText(change.before)} → ${valueText(change.after)}", style = MaterialTheme.typography.bodyMedium)
                    Muted(when (change.kind) { ChangeKind.CHANGED -> "변화 있음"; ChangeKind.UNCHANGED -> "수집값 동일"; ChangeKind.NOT_COMPARABLE -> "비교 불가" })
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

@Composable
private fun ReadingDetails(group: DetailGroup, snapshot: VehicleSnapshot?, state: InspectionUiState, onEvent: (InspectionEvent) -> Unit) {
    val editable = state.stage == InspectionStage.PICKUP || state.stage == InspectionStage.RETURN
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 640.dp).navigationBarsPadding(),
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Text(group.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            if (group == DetailGroup.WINDOWS) {
                Spacer(Modifier.height(12.dp))
                Text("반납 시 창문 상태를 새로 조회해요. 확인할 수 없는 창문은 직접 닫힘을 확인해 주세요.", style = MaterialTheme.typography.bodyMedium)
                if (editable) Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = state.windowManuallyChecked, enabled = !state.busy && !state.cloudBusy,
                        onCheckedChange = { onEvent(InspectionEvent.CheckWindowManually(it)) })
                    Text("창문을 직접 확인했어요", style = MaterialTheme.typography.bodyLarge)
                } else Muted("직접 확인 여부는 저장된 기록에서 확인할 수 있어요.")
            }
            if (group == DetailGroup.ALL) {
                Muted(state.message)
                state.record?.let { record ->
                    SmallComparison("인수 기록 저장", inspectionTime(record.pickup.completedAtMillis))
                    SmallComparison("인수 시 창문 직접 확인", if (record.pickupWindowManuallyChecked) "확인 완료" else "미기록")
                }
            }
        }
        val readings = group.readings(snapshot)
        if (readings.isEmpty()) item { Notice("수집된 정보가 없어요", "차량에 연결한 뒤 다시 확인해 주세요.") }
        items(readings, key = { it.key }) { reading ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(reading.label, style = MaterialTheme.typography.titleMedium)
                Text(valueText(reading), style = MaterialTheme.typography.bodyLarge,
                    color = if (reading.available) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
                val before = state.record?.pickup?.find(reading.key)
                if (before != null && state.stage != InspectionStage.PICKUP) Muted("인수 시 ${valueText(before)}")
                if (reading.detail.isNotBlank()) Muted(reading.detail)
                if (reading.observedAtMillis > 0) Muted("수집 ${inspectionTime(reading.observedAtMillis)}")
            }
            HorizontalDivider(Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

// Use almost the entire automotive app surface instead of imposing a narrow desktop-style reading column on its landscape display.
@Composable
private fun ContentWidth(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.widthIn(max = 1600.dp).fillMaxWidth(), content = content)
}

// Stack content on smaller or enlarged-text layouts while keeping related cards beside one another when the app has enough width.
@Composable
internal fun TwoColumns(wide: Boolean, left: @Composable ColumnScope.() -> Unit, right: @Composable ColumnScope.() -> Unit) {
    if (wide) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1.12f), verticalArrangement = Arrangement.spacedBy(14.dp), content = left)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp), content = right)
    } else Column(verticalArrangement = Arrangement.spacedBy(14.dp)) { left(); right() }
}

// Group related readings with a quiet border and restrained padding so the limited automotive app area carries useful information.
@Composable
internal fun Panel(content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .7f))) {
        Column(Modifier.padding(24.dp), content = content)
    }
}

// Give each card a compact title row while allowing its genuine timestamp or saved-state caption to remain visible.
@Composable
internal fun PanelTitle(title: String, aside: String? = null) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        if (aside != null) Text(aside, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp))
    }
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun Metric(label: String, value: String) {
    Muted(label)
    Spacer(Modifier.height(4.dp))
    Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
}

@Composable
internal fun SmallComparison(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun StatusRow(title: String, description: String, attention: Boolean = false, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(10.dp),
        color = if (attention) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall,
                    color = if (attention) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer)
                Text(description, style = MaterialTheme.typography.bodySmall,
                    color = if (attention) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Text("›", style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
private fun Notice(title: String, message: String, error: Boolean = false) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
        color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(message, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
internal fun Muted(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
