package kr.taeuk.pleos.rentalcheck.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kr.taeuk.pleos.rentalcheck.InspectionUiState
import kr.taeuk.pleos.rentalcheck.model.SnapshotComparison

// Put the trip's two useful metrics on the same baseline while leaving every original reading and pickup value accessible in the detail sheet.
@Composable
internal fun TripSummaryCard(state: InspectionUiState, openDetails: () -> Unit) {
    val record = state.record ?: return
    val report = state.current?.let { SnapshotComparison.compare(record.pickup, it) }
    val energy = energyReadings(state.current)
    val current = energy.firstOrNull { it.available } ?: energy.firstOrNull()
    Panel {
        PanelTitle("이번 이용", "인수 ${inspectionTime(record.pickup.completedAtMillis)}")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            TripMetric("이용 거리", report?.distanceKm?.let { displayNumber(it) + " km" } ?: "확인 불가", Modifier.weight(1f))
            TripMetric(current?.label ?: "차량 잔량", if (current?.available == true) current.displayValue else "확인 불가", Modifier.weight(1f))
        }
        report?.distanceProblem?.let { Spacer(Modifier.height(12.dp)); Muted(it) }
        TextButton(onClick = openDetails, contentPadding = PaddingValues(vertical = 12.dp)) { Text("인수 기록 · 차량 정보 보기  ›") }
    }
}

// Reserve larger type for values and give both metrics identical padding, label style and alignment, including unavailable states.
@Composable
private fun TripMetric(label: String, value: String, modifier: Modifier) {
    Column(modifier.background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(14.dp)).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
    }
}
