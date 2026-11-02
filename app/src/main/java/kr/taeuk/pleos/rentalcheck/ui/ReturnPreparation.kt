package kr.taeuk.pleos.rentalcheck.ui

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import kr.taeuk.pleos.rentalcheck.InspectionEvent
import kr.taeuk.pleos.rentalcheck.InspectionUiState
import kr.taeuk.pleos.rentalcheck.model.BatteryReturn
import kr.taeuk.pleos.rentalcheck.navigation.ChargingStop
import kr.taeuk.pleos.rentalcheck.navigation.ReturnLocation
import java.util.Locale

// Explain the pickup-derived target and last received value without predicting range, deciding fees, or hiding unknown battery data.
@Composable
internal fun BatteryPreparation(state: InspectionUiState, onEvent: (InspectionEvent) -> Unit) {
    val battery = BatteryReturn.from(state.record?.pickup, state.current)
    var selected by remember(state.pairedBooking?.id) { mutableStateOf<ChargingStop?>(null) }
    HorizontalDivider(Modifier.padding(vertical = 16.dp))
    Text("배터리 반납 기준", style = MaterialTheme.typography.titleMedium)
    Text("인수 때 ${battery.target?.let(::percentage) ?: "확인 불가"} · 최근 잔량 ${battery.current?.let(::percentage) ?: "확인 불가"}")
    Text(when {
        battery.target == null -> "인수 때 잔량을 받지 못해 자동 기준을 정할 수 없어요. 예약한 곳에 확인해 주세요."
        battery.current == null -> "현재 잔량을 받지 못했어요. 다시 읽기 후 확인해 주세요."
        battery.shortfall!! > 0 -> "인수 기준보다 ${percentage(battery.shortfall!!)}p 부족해요. 충전 후 반납을 준비해 주세요."
        else -> "최근 조회 값은 인수 때 배터리 기준 이상이에요."
    }, style = MaterialTheme.typography.bodySmall)
    Text("최근 차량 조회 기준이며 충전 요금·실제 반납 승인을 결정하지 않아요.", style = MaterialTheme.typography.bodySmall)
    if (battery.shortfall?.let { it > 0 } == true) {
        val stops = state.pairedBooking?.returnLocation?.chargingStops.orEmpty()
        if (stops.isEmpty()) Text("등록된 충전 경유지가 없어요. 예약한 곳의 충전 안내를 확인해 주세요.")
        for (stop in stops) OutlinedButton(onClick = { selected = stop }, enabled = !state.busy && state.returnGuide.navigation.connected &&
            !state.returnGuide.navigation.busy && state.record?.returned == null) { Text("${stop.name} 경유") }
    }
    selected?.let { stop -> AlertDialog(onDismissRequest = { selected = null }, title = { Text("충전소를 경유할까요?") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stop.name); Text(stop.address); Text("충전소 → ${state.pairedBooking?.returnLocation?.name}")
            Text("운영자가 등록한 장소예요. 영업·충전기 호환·이용 가능 여부를 직접 확인해 주세요. 현재 내비 경로가 바뀌며 P·VIN·배터리를 다시 조회해요.")
        }
    }, confirmButton = { TextButton(onClick = { selected = null; onEvent(InspectionEvent.RequestChargeDirections(stop)) }) { Text("경유 길 안내 시작") } },
        dismissButton = { TextButton(onClick = { selected = null }) { Text("취소") } }) }
}

// Keep percentage labels consistent with the saved numeric reference while making small nonzero deficits visible.
private fun percentage(value: Double): String = String.format(Locale.KOREA, "%.3f", value).trimEnd('0').trimEnd('.') + "%"

// Display operator-issued sequential instructions and embedded sanitized photos; no arbitrary remote image or vehicle location is fetched.
@Composable
internal fun ParkingPreparation(place: ReturnLocation) {
    if (place.parkingSteps.isEmpty()) Text("주차장 입구·층·구역별 상세 안내가 아직 등록되지 않았어요. 예약한 곳의 안내를 확인해 주세요.")
    for ((index, step) in place.parkingSteps.withIndex()) {
        Text("${index + 1}. ${step.title}", style = MaterialTheme.typography.titleMedium)
        Text(step.detail)
        val picture = remember(step.imageBase64) { runCatching {
            step.imageBase64?.let { encoded -> val bytes = Base64.decode(encoded, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }
        }.getOrNull() }
        if (picture != null) Image(picture, contentDescription = step.title + " 안내 이미지", modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp))
        else if (step.imageBase64 != null) Text("안내 이미지를 열지 못했어요. 위 설명을 확인해 주세요.")
    }
    if (place.isTest) Text("시험용 안내 예시예요. 실제 주차장 경로로 사용하지 마세요.")
}
