package kr.taeuk.pleos.rentalcheck.ui

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
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
    val short = battery.shortfall?.let { it > 0 } == true
    val colors = MaterialTheme.colorScheme
    Spacer(Modifier.height(20.dp))
    Surface(shape = RoundedCornerShape(18.dp), color = colors.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("배터리 반납 기준", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (short) StateBadge("${percentage(battery.shortfall!!)}p 부족", attention = true)
            }
            // Show the saved pickup reference and the latest reading side by side; the bar marks the reference without implying range.
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                BatteryFigure("인수 때 기준", battery.target?.let(::percentage) ?: "확인 불가", colors.onSurface)
                BatteryFigure("최근 잔량", battery.current?.let(::percentage) ?: "확인 불가", if (short) colors.error else colors.primary)
            }
            val target = battery.target; val current = battery.current
            if (target != null && current != null) BoxWithConstraints(Modifier.fillMaxWidth().height(12.dp)) {
                val width = maxWidth
                Box(Modifier.fillMaxSize().background(colors.surfaceContainerHighest, RoundedCornerShape(50)))
                Box(Modifier.fillMaxHeight().width(width * (current / 100.0).toFloat().coerceIn(0f, 1f))
                    .background(if (short) colors.error else colors.primary, RoundedCornerShape(50)))
                Box(Modifier.offset(x = width * (target / 100.0).toFloat().coerceIn(0f, 1f) - 1.5.dp).width(3.dp).fillMaxHeight()
                    .background(colors.onSurface))
            }
            Text(when {
                battery.target == null -> "인수 때 잔량을 받지 못해 자동 기준을 정할 수 없어요. 예약한 곳에 확인해 주세요."
                battery.current == null -> "현재 잔량을 받지 못했어요. 다시 읽기 후 확인해 주세요."
                battery.shortfall!! > 0 -> "인수 기준보다 ${percentage(battery.shortfall!!)}p 부족해요. 충전 후 반납을 준비해 주세요."
                else -> "최근 조회 값은 인수 때 배터리 기준 이상이에요."
            }, style = MaterialTheme.typography.bodyMedium)
            Text("최근 차량 조회 기준이며 충전 요금·실제 반납 승인을 결정하지 않아요.", style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant)
            if (short) {
                val stops = state.pairedBooking?.returnLocation?.chargingStops.orEmpty()
                if (stops.isEmpty()) Text("등록된 충전 경유지가 없어요. 예약한 곳의 충전 안내를 확인해 주세요.")
                for (stop in stops) OutlinedButton(onClick = { selected = stop }, enabled = !state.busy && state.returnGuide.navigation.connected &&
                    !state.returnGuide.navigation.busy && state.record?.returned == null, modifier = Modifier.heightIn(min = 52.dp)) { Text("${stop.name} 경유") }
            }
        }
    }
    selected?.let { stop -> AlertDialog(onDismissRequest = { selected = null }, title = { Text("충전소를 경유할까요?") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stop.name); Text(stop.address); Text("충전소 → ${state.pairedBooking?.returnLocation?.name}")
            Text("운영자가 등록한 장소예요. 영업·충전기 호환·이용 가능 여부를 직접 확인해 주세요. 현재 내비 경로가 바뀌며 P·VIN·배터리를 다시 조회해요.")
        }
    }, confirmButton = { TextButton(onClick = { selected = null; onEvent(InspectionEvent.RequestChargeDirections(stop)) }) { Text("경유 길 안내 시작") } },
        dismissButton = { TextButton(onClick = { selected = null }) { Text("취소") } }) }
}

// Present one battery figure as a small label over a large value so the pickup reference and latest reading compare at a glance.
@Composable
private fun BatteryFigure(label: String, value: String, color: androidx.compose.ui.graphics.Color) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = color)
    }
}

// Keep percentage labels consistent with the saved numeric reference while making small nonzero deficits visible.
private fun percentage(value: Double): String = String.format(Locale.KOREA, "%.3f", value).trimEnd('0').trimEnd('.') + "%"

// Display operator-issued sequential instructions and embedded sanitized photos; no arbitrary remote image or vehicle location is fetched.
@Composable
internal fun ParkingPreparation(place: ReturnLocation) {
    if (place.parkingSteps.isEmpty()) Text("주차장 입구·층·구역별 상세 안내가 아직 등록되지 않았어요. 예약한 곳의 안내를 확인해 주세요.")
    for ((index, step) in place.parkingSteps.withIndex()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.primary) {
                Text("${index + 1}", Modifier.padding(horizontal = 10.dp, vertical = 2.dp), style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimary)
            }
            Text(step.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
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
