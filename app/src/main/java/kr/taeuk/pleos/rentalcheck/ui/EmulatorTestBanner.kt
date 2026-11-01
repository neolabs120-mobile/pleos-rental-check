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
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.fillMaxWidth()) {
        Text("에뮬레이터 테스트 VIN 사용 중 · 실제 차량 VIN 아님 · 나머지 항목은 SDK 조회", Modifier.padding(12.dp),
            style = MaterialTheme.typography.labelLarge)
    }
}
