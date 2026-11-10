package kr.taeuk.pleos.rentalcheck.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kr.taeuk.pleos.rentalcheck.InspectionEvent
import kr.taeuk.pleos.rentalcheck.model.SwitchOffCheck
import kr.taeuk.pleos.rentalcheck.speech.SeatSpeechState
import kr.taeuk.pleos.rentalcheck.speech.SeatSpeechStatus

// Ask the renter to switch off what is still on and close what is still open; the renter may recheck or knowingly continue, never auto-control.
@Composable
internal fun ReturnSwitchOffDialog(check: SwitchOffCheck, speech: SeatSpeechState, onEvent: (InspectionEvent) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val playing = speech.status in listOf(SeatSpeechStatus.PREPARING, SeatSpeechStatus.SPEAKING)
    AlertDialog(
        onDismissRequest = { onEvent(InspectionEvent.DismissSwitchOffCheck) },
        shape = RoundedCornerShape(28.dp),
        title = { Text(if (check.leftOpen.isEmpty()) "내리기 전에 꺼 주세요" else "내리기 전에 확인해 주세요", fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (check.leftOpen.isNotEmpty()) {
                    StateBadge("열려 있는 곳 ${check.leftOpen.size}곳", attention = true)
                    check.leftOpen.forEach { AttentionRow(it.label, it.state) }
                    Text("내리기 전에 닫아 주세요. 열어 둔 채 두면 차 안의 물건이 그대로 드러나요.", style = MaterialTheme.typography.bodyMedium)
                }
                if (check.leftOn.isNotEmpty()) {
                    StateBadge("켜져 있는 항목 ${check.leftOn.size}개", attention = true)
                    check.leftOn.forEach { AttentionRow(it.label, it.state) }
                    Text("다음 이용자와 배터리를 위해 꺼 주세요. 시동을 끄면 자동으로 꺼지는 차량도 있어요.",
                        style = MaterialTheme.typography.bodyMedium)
                }
                if (check.unlocked.isNotEmpty()) Muted("문이 잠겨 있지 않아요. 내린 뒤 잠겼는지 확인해 주세요.")
                if (check.unknown.isNotEmpty()) Muted("확인하지 못한 항목: ${check.unknown.joinToString()} · 직접 살펴봐 주세요.")
                Muted("앱은 차량을 조작하지 않아요. 직접 끄거나 닫은 뒤 다시 확인하면 새로 조회해요.")
                HorizontalDivider(color = colors.outlineVariant)
                Text(when (speech.status) {
                    SeatSpeechStatus.PREPARING -> "음성 안내를 준비하고 있어요."
                    SeatSpeechStatus.SPEAKING -> "확인할 항목을 읽고 있어요."
                    SeatSpeechStatus.DONE -> "음성 안내를 마쳤어요."
                    SeatSpeechStatus.STOPPED -> "음성 안내를 중지했어요."
                    SeatSpeechStatus.FAILED -> "음성 안내를 재생하지 못했어요. 화면을 확인해 주세요."
                    SeatSpeechStatus.IDLE -> "필요하면 음성 안내를 들어보세요."
                }, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                FilledTonalButton(onClick = { onEvent(if (playing) InspectionEvent.StopSwitchOffSpeech else InspectionEvent.ReplaySwitchOffSpeech) },
                    modifier = Modifier.heightIn(min = 52.dp)) { Text(if (playing) "■  음성 중지" else "▶  음성 다시 듣기") }
            }
        },
        confirmButton = {
            Button(onClick = { onEvent(InspectionEvent.RetrySwitchOffCheck) }, modifier = Modifier.heightIn(min = 52.dp)) {
                Text(if (check.leftOpen.isEmpty()) "끈 뒤 다시 확인" else "다시 확인")
            }
        },
        dismissButton = {
            TextButton(onClick = { onEvent(InspectionEvent.ContinueWithSwitchOn) }, modifier = Modifier.heightIn(min = 52.dp)) {
                Text(if (check.leftOpen.isEmpty()) "켜진 채로 진행" else "그대로 진행")
            }
        },
    )
}

// One highlighted row per item that is still on or still open, with its current state on the right.
@Composable
private fun AttentionRow(label: String, state: String) {
    val colors = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(14.dp), color = colors.errorContainer) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(shape = CircleShape, color = colors.error) {
                Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                    Text("!", style = MaterialTheme.typography.labelLarge, color = colors.errorContainer)
                }
            }
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold, color = colors.onErrorContainer)
            Text(state, style = MaterialTheme.typography.bodySmall, color = colors.onErrorContainer)
        }
    }
}
