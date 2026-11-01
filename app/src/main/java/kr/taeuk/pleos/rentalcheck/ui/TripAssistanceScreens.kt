package kr.taeuk.pleos.rentalcheck.ui

import android.os.SystemClock
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kr.taeuk.pleos.rentalcheck.*
import kr.taeuk.pleos.rentalcheck.model.*
import kotlinx.coroutines.delay

// Present limited seat signals separately from luggage memory; neither a missing signal nor an unoccupied result proves belongings are absent.
@Composable
internal fun SeatOccupancyCard(readings: List<VehicleReading>, saved: Boolean) {
    Panel {
        PanelTitle("소지품 확인", "좌석 점유 신호")
        val occupied = readings.count { it.available && it.value == "true" && it.displayValue == "점유" }
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            StateBadge(if (occupied > 0) "점유 신호 ${occupied}곳 · 좌석과 발밑 확인" else "좌석과 발밑을 직접 확인하세요", occupied > 0)
            SeatMap(readings)
            Text("사람과 짐은 구분하지 못해요. 비점유 좌석과 트렁크도 직접 살펴보세요.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val at = readings.maxOfOrNull { it.observedAtMillis } ?: 0
            if (at > 0) Muted((if (saved) "반납 저장 시점 · " else "최근 수신 · ") + inspectionTime(at))
        }
    }
}

// Introduce the optional stationary exercise without replacing pickup/return inspection or making it a prerequisite for rental use.
@Composable
internal fun FamiliarizationCard(state: InspectionUiState, onEvent: (InspectionEvent) -> Unit) {
    Panel {
        PanelTitle("처음 타는 차 · 90초 적응 가이드")
        Text(if (state.assistance.practiceCompleted) "창문을 실제로 열고 닫는 연습을 마쳤어요." else "출발 전에 실제 차량 버튼으로 창문 조작을 익혀 보세요.")
        Muted("완전히 정차하고 P단인 경우에만 진행해요. 연습은 선택 사항이며, 차량을 자동 조작하지 않아요.")
        OutlinedButton(onClick = { onEvent(InspectionEvent.OpenPractice) }, enabled = !state.busy && !state.cloudBusy) {
            Text(if (state.assistance.practiceCompleted) "다시 연습하기" else "90초 적응 가이드 열기")
        }
    }
}

// Show observed openings and explicit luggage marks separately; neither is a declaration that an object exists or that belongings are all cleared.
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun UsageMemoryCard(memory: UsageMemory, onMark: ((StorageSpace, Boolean) -> Unit)? = null, problem: String? = null) {
    Panel {
        PanelTitle(if (onMark == null) "사용한 공간 다시 확인" else "짐을 둔 곳", if (onMark != null) "직접 표시" else "저장된 메모")
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (onMark != null) {
                Text("짐을 둔 공간을 표시해 두면 반납할 때 다시 보여드려요.", style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    StorageSpace.entries.forEach { space -> FilterChip(selected = space in memory.markedSpaces,
                        onClick = { onMark(space, space !in memory.markedSpaces) }, modifier = Modifier.heightIn(min = 48.dp),
                        label = { Text(storageLabel(space), style = MaterialTheme.typography.labelMedium) }) }
                }
            } else Text(if (memory.markedSpaces.isEmpty()) "직접 표시한 짐 메모가 없어요. 사용한 공간을 직접 확인하세요."
                else "직접 표시 · " + memory.markedSpaces.joinToString { storageLabel(it) }, style = MaterialTheme.typography.bodyMedium)
            Muted(if (memory.openedSpaces.isEmpty()) "열림 관측 기록이 없어요. 사용하지 않았다는 뜻은 아니에요."
                else "문·트렁크 열림 관측 · " + memory.openedSpaces.joinToString { storageLabel(it) })
            Muted("물건의 유무는 판단하지 않아요. 화면이 꺼진 동안의 동작은 놓칠 수 있어요.")
            problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

// Shorten only display labels so the same stored location identifiers and mark/unmark events remain intact on compact cards.
private fun storageLabel(space: StorageSpace) = when (space) {
    StorageSpace.FRONT_LEFT -> "운전석"
    StorageSpace.FRONT_RIGHT -> "동승석"
    StorageSpace.REAR_LEFT -> "뒤 왼쪽"
    StorageSpace.REAR_RIGHT -> "뒤 오른쪽"
    StorageSpace.TRUNK -> "트렁크"
}

// Guide a real closed→open→closed sequence with a bounded timer; SDK failures, an expired timer or a non-P gear never count as completion.
@Composable
internal fun FamiliarizationDialog(state: InspectionUiState, onEvent: (InspectionEvent) -> Unit) {
    val practice = state.assistance.practice
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(practice?.startedElapsedMillis) { while (true) { now = SystemClock.elapsedRealtime(); delay(250) } }
    val windows = state.current?.readings.orEmpty().filter { it.key.startsWith("window.") && it.key.endsWith(".position") &&
        state.current?.find(it.key.removeSuffix(".position") + ".closed")?.available == true }
    AlertDialog(onDismissRequest = { onEvent(InspectionEvent.ClosePractice) }, title = { Text("정차 후 창문 조작 연습") }, text = {
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("출발하기 전 완전히 정차해 주세요. P단을 실제로 수신해야 연습이 진행돼요.")
            if (practice == null || !practice.running) {
                if (practice?.step == PracticeStep.COMPLETE) Text("연습 완료! 실제 창문의 닫힘 → 열림 → 닫힘을 확인했어요.")
                practice?.problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Text("연습할 창문을 선택하세요. 차량의 실제 버튼으로 조작하고 각 단계의 수신을 기다려 주세요.")
                if (windows.isEmpty()) Text("지원 범위와 현재값을 확인한 창문이 없어요. 차량 정보를 다시 읽거나 가이드를 건너뛰세요.")
                windows.forEach { window -> OutlinedButton(onClick = { onEvent(InspectionEvent.StartPractice(window.key, window.label)) }) { Text(window.label) } }
            } else {
                Text("${practice.label} · 남은 시간 ${practice.remainingSeconds(now)}초")
                LinearProgressIndicator(progress = { (practice.remainingSeconds(now) / 90f).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                Text(when (practice.step) {
                    PracticeStep.CLOSE_FIRST -> "먼저 선택한 창문을 닫아 주세요. 닫힌 기준 상태를 확인해요."
                    PracticeStep.OPEN -> "차량의 실제 창문 버튼으로 조금 열고 기다려 주세요."
                    PracticeStep.CLOSE -> "열림을 확인했어요. 이제 창문을 완전히 닫고 기다려 주세요."
                    else -> "확인 중"
                }, style = MaterialTheme.typography.titleMedium)
                practice.problem?.let { Text(it) }
            }
            Muted("확인되지 않은 동작은 완료 처리하지 않아요. 연습을 중단해도 인수·반납을 진행할 수 있어요. 열었던 창문은 직접 닫아 주세요.")
        }
    }, confirmButton = { TextButton(onClick = { onEvent(InspectionEvent.ClosePractice) }) {
        Text(if (practice?.step == PracticeStep.COMPLETE) "완료하고 돌아가기" else "건너뛰기 · 닫기")
    } })
}
