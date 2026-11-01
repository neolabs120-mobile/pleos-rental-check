package kr.taeuk.pleos.rentalcheck.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kr.taeuk.pleos.rentalcheck.InspectionEvent
import kr.taeuk.pleos.rentalcheck.InspectionUiState
import kr.taeuk.pleos.rentalcheck.navigation.ReturnLocation
import java.util.Locale

// Show the operator's booking-specific destination with a qualified distance estimate; it never marks parking or rental return complete.
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReturnLocationCard(state: InspectionUiState, onEvent: (InspectionEvent) -> Unit) {
    val guide = state.returnGuide; val place = state.pairedBooking?.returnLocation
    var confirmation by remember(state.pairedBooking?.id) { mutableStateOf<ReturnLocation?>(null) }
    Panel {
        PanelTitle("반납 장소 안내")
        if (place == null) Muted("이 예약의 반납 장소가 등록되지 않았어요. 예약한 곳에 확인해 주세요.")
        else {
            if (place.isTest) StateBadge("에뮬레이터 시험 장소 · 실제 반납 거점 아님", true)
            Text(place.name, style = MaterialTheme.typography.titleLarge)
            Text(place.address)
            if (place.instructions.isNotBlank()) Text(place.instructions)
            val distance = guide.distanceMeters
            Text(if (distance == null) "현재 직선거리를 확인할 수 없어요." else if (distance < 1000)
                "반납 장소까지 직선 약 ${distance.toInt()}m" else "반납 장소까지 직선 약 ${String.format(Locale.KOREA, "%.1f", distance / 1000)}km")
            if (distance != null && distance <= 150) Text("좌표상 가까워요. 지정된 주차구역과 반납 조건을 직접 확인해 주세요.")
            if (distance == null) Muted(if (guide.fix != null) "최근 1분 이내 위치를 확인하지 못했어요. 이전 위치로 도착을 판단하지 않아요."
                else guide.locationMessage)
            Muted("직선거리는 주행 경로·도착 확인이 아니에요. 차량 위치는 화면이 열려 있는 동안만 사용하고 서버에 보내지 않아요.")
            Text(guide.navigation.message)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (guide.locationPermissionRequired) OutlinedButton(onClick = { onEvent(InspectionEvent.RequestReturnLocationPermission) },
                    enabled = !state.busy) { Text("차량 위치 사용 허용") }
                Button(onClick = { confirmation = place }, enabled = !state.busy && !state.cloudBusy && !state.accountBusy &&
                    guide.navigation.connected && !guide.navigation.busy) { Text("반납 장소 길 안내") }
                OutlinedButton(onClick = { onEvent(InspectionEvent.OpenReturnNavigation) }, enabled = guide.navigation.connected && !state.busy) { Text("내비 화면 열기") }
            }
        }
    }
    confirmation?.let { selected -> AlertDialog(onDismissRequest = { confirmation = null }, title = { Text("반납 장소로 안내할까요?") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(selected.name); Text(selected.address)
            if (selected.isTest) Text("실제 반납 거점이 아닌 에뮬레이터 시험 장소예요.")
            Text("현재 내비게이션 목적지가 바뀔 수 있어요. P 상태를 다시 확인한 뒤 요청해요.")
        } }, confirmButton = { TextButton(onClick = { confirmation = null; onEvent(InspectionEvent.RequestReturnDirections(selected)) }) { Text("길 안내 시작") } },
        dismissButton = { TextButton(onClick = { confirmation = null }) { Text("취소") } }) }
}
