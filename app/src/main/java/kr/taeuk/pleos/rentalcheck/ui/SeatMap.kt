package kr.taeuk.pleos.rentalcheck.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kr.taeuk.pleos.rentalcheck.model.VehicleReading

// Render supported seat locations spatially from normalized readings only; missing areas stay empty instead of inventing a vacant seat.
@Composable
internal fun SeatMap(readings: List<VehicleReading>) {
    val areas = listOf("ROW_1_LEFT" to "앞쪽 왼쪽", "ROW_1_RIGHT" to "앞쪽 오른쪽",
        "ROW_2_LEFT" to "뒤쪽 왼쪽", "ROW_2_CENTER" to "뒤쪽 가운데", "ROW_2_RIGHT" to "뒤쪽 오른쪽")
    val values = areas.associate { (area, _) -> area to readings.firstOrNull { it.key == "seat.$area.occupancy" } }
    if (values.values.all { it == null }) {
        readings.forEach { SmallComparison(it.label, valueText(it)) }
        if (readings.isEmpty()) Muted("아직 좌석 점유 정보를 받지 못했어요. 차량 정보를 다시 읽어 주세요.")
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("차량 앞쪽 ↑", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        listOf(areas.take(2), areas.drop(2)).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { (area, label) ->
                    Box(Modifier.weight(1f)) { values[area]?.let { SeatTile(label, it) } }
                }
            }
        }
    }
}

// Separate occupied, unoccupied and unavailable visually without asserting that the seat contains a person or that belongings are absent.
@Composable
private fun SeatTile(label: String, reading: VehicleReading) {
    val occupied = if (!reading.available) null else when {
        reading.value == "true" && reading.displayValue == "점유" -> true
        reading.value == "false" && reading.displayValue == "비점유" -> false
        else -> null
    }
    val colors = MaterialTheme.colorScheme
    val fill = when (occupied) { true -> colors.errorContainer; false -> colors.surfaceVariant; null -> colors.surface }
    val ink = if (occupied == true) colors.onErrorContainer else colors.onSurfaceVariant
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = fill,
        border = BorderStroke(1.dp, if (occupied == true) colors.error.copy(alpha = .4f) else colors.outlineVariant)) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(5.dp)) {
            SeatGlyph(ink)
            Text(label, style = MaterialTheme.typography.labelMedium, color = ink)
            Text(when (occupied) { true -> "점유"; false -> "비점유"; null -> valueText(reading) },
                style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = ink)
        }
    }
}

// Draw a quiet seat silhouette as a code-native visual cue, keeping the textual state available to accessibility services.
@Composable
private fun SeatGlyph(color: Color) {
    Canvas(Modifier.size(30.dp)) {
        val w = size.width; val h = size.height
        drawRoundRect(color.copy(alpha = .8f), Offset(w * .22f, h * .08f), Size(w * .56f, h * .58f), CornerRadius(4.dp.toPx()))
        drawRoundRect(color, Offset(w * .15f, h * .68f), Size(w * .7f, h * .22f), CornerRadius(3.dp.toPx()))
    }
}
