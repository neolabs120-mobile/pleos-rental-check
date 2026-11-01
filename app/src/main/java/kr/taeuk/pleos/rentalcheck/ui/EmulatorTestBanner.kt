package kr.taeuk.pleos.rentalcheck.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

// Keep the simulated identity visible on every vehicle screen so test receipts can never be mistaken for a real VIN observation.
@Composable
fun EmulatorTestBanner() {
    Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
        Text("에뮬레이터 시험 모드 · 테스트 VIN 사용 · 실제 차량 VIN이 아닙니다", Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onErrorContainer)
    }
}
