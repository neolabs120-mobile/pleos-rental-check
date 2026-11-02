package kr.taeuk.pleos.rentalcheck.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kr.taeuk.pleos.rentalcheck.InspectionEvent
import kr.taeuk.pleos.rentalcheck.InspectionUiState
import kr.taeuk.pleos.rentalcheck.navigation.ReturnLocation
import java.util.Locale

// Give the destination and qualified distance a clear hierarchy while preserving explicit approval, real P/VIN checks and location failure behavior.
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReturnLocationCard(state: InspectionUiState, onEvent: (InspectionEvent) -> Unit) {
    val guide = state.returnGuide; val place = state.pairedBooking?.returnLocation
    var confirmation by remember(state.pairedBooking?.id) { mutableStateOf<ReturnLocation?>(null) }
    var details by remember(state.pairedBooking?.id) { mutableStateOf(false) }
    Panel {
        PanelTitle("반납 장소", if (guide.navigation.connected) "내비 연결됨" else null)
        if (place == null) Muted("등록된 반납 장소가 없어요. 예약한 곳에 확인해 주세요.")
        else {
            BoxWithConstraints {
                val description: @Composable () -> Unit = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (place.isTest) StateBadge("시험 장소 · 실제 반납 거점 아님", true)
                        Text(place.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(place.address, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (place.instructions.isNotBlank()) Text(place.instructions, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (maxWidth >= 660.dp && LocalDensity.current.fontScale <= 1.3f)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        Box(Modifier.weight(1f)) { description() }; ReturnDistance(guide.distanceMeters)
                    }
                else Column(verticalArrangement = Arrangement.spacedBy(16.dp)) { description(); ReturnDistance(guide.distanceMeters) }
            }
            Spacer(Modifier.height(18.dp))
            Text(if (guide.distanceMeters?.let { it <= 150 } == true) "좌표상 가까워요. 지정 주차구역과 반납 조건을 직접 확인하세요."
                else "직선거리 참고값이에요. 지정된 주차구역은 직접 확인하세요.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (guide.distanceMeters == null) {
                Spacer(Modifier.height(8.dp)); Muted(if (guide.fix != null) "최근 1분 이내 위치를 확인하지 못했어요." else guide.locationMessage)
            }
            Spacer(Modifier.height(20.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (guide.locationPermissionRequired) OutlinedButton(onClick = { onEvent(InspectionEvent.RequestReturnLocationPermission) },
                    enabled = !state.busy, modifier = Modifier.heightIn(min = 52.dp)) { Text("차량 위치 사용 허용") }
                Button(onClick = { confirmation = place }, enabled = !state.busy && !state.cloudBusy && !state.accountBusy &&
                    guide.navigation.connected && !guide.navigation.busy, modifier = Modifier.heightIn(min = 52.dp)) { Text("반납 장소 길 안내") }
                OutlinedButton(onClick = { onEvent(InspectionEvent.OpenReturnNavigation) }, enabled = guide.navigation.connected && !state.busy,
                    modifier = Modifier.heightIn(min = 52.dp)) { Text("내비 화면 열기") }
                TextButton(onClick = { details = true }, modifier = Modifier.heightIn(min = 52.dp)) { Text("장소 · 위치 안내") }
            }
            Spacer(Modifier.height(12.dp)); Muted(guide.navigation.message)
            BatteryPreparation(state, onEvent)
        }
    }
    confirmation?.let { selected -> AlertDialog(onDismissRequest = { confirmation = null },
        title = { Text("이 장소로 안내할까요?") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(selected.name, style = MaterialTheme.typography.titleMedium); Text(selected.address)
                if (selected.isTest) StateBadge("시험 장소 · 실제 반납 거점 아님", true)
                Text("현재 내비 목적지가 바뀔 수 있어요. P 상태와 차량 VIN을 다시 확인한 뒤 요청해요.", style = MaterialTheme.typography.bodySmall)
            }
        }, confirmButton = { Button(onClick = { confirmation = null; onEvent(InspectionEvent.RequestReturnDirections(selected)) },
            modifier = Modifier.heightIn(min = 52.dp)) { Text("길 안내 시작") } },
        dismissButton = { TextButton(onClick = { confirmation = null }, modifier = Modifier.heightIn(min = 52.dp)) { Text("취소") } }) }
    if (details && place != null) AlertDialog(onDismissRequest = { details = false }, title = { Text("반납 장소 · 위치 안내") }, text = {
        Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(place.name, style = MaterialTheme.typography.titleMedium); Text(place.address)
            if (place.instructions.isNotBlank()) Text(place.instructions)
            ParkingPreparation(place)
            if (place.isTest) Text("에뮬레이터용 시험 장소이며 실제 반납 거점이 아니에요.")
            Text("최근 1분 이내 차량 위치로 직선거리를 계산해요. 주행 거리나 주차구역 도착을 확인하는 값이 아니며 자동 반납하지 않아요.")
            Text("차량 위치는 이 앱이 화면에 열려 있는 동안만 사용하며 서버에 보내거나 반납 기록에 저장하지 않아요.")
        }
    }, confirmButton = { Button(onClick = { details = false }, modifier = Modifier.heightIn(min = 52.dp).widthIn(min = 140.dp)) { Text("확인") } },
        shape = RoundedCornerShape(28.dp))
}

// Reserve prominent numerals for the estimate; unavailable location cannot be mistaken for zero distance or verified arrival.
@Composable
private fun ReturnDistance(distance: Double?) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.primaryContainer) {
        Column(Modifier.widthIn(min = 180.dp).padding(horizontal = 24.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("현재 직선거리", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(when {
                distance == null -> "확인 불가"
                distance < 1000 -> "약 ${distance.toInt()} m"
                else -> "약 ${String.format(Locale.KOREA, "%.1f", distance / 1000)} km"
            }, style = if (distance == null) MaterialTheme.typography.titleLarge else MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        }
    }
}
