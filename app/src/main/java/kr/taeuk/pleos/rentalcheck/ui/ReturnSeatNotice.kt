package kr.taeuk.pleos.rentalcheck.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kr.taeuk.pleos.rentalcheck.InspectionEvent
import kr.taeuk.pleos.rentalcheck.speech.SeatSpeechState
import kr.taeuk.pleos.rentalcheck.speech.SeatSpeechStatus
import kr.taeuk.pleos.rentalcheck.model.LuggageMemo

@Composable
internal fun ReturnSeatNotice(seats: List<String>, speech: SeatSpeechState, onEvent: (InspectionEvent) -> Unit,
    memos: List<LuggageMemo> = emptyList(), collected: Set<kr.taeuk.pleos.rentalcheck.model.StorageSpace> = emptySet(), editable: Boolean = false) {
    // Point to the occupied locations without claiming an object was detected or requiring a seated person to leave before continuing the return.
    AlertDialog(
        onDismissRequest = { onEvent(InspectionEvent.DismissReturnSeatNotice) },
        title = { Text("내리기 전, 소지품을 확인해 주세요") },
        text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
            if (memos.isNotEmpty()) {
                StateBadge("내가 저장한 짐 메모")
                Spacer(Modifier.height(12.dp))
                Text("${memos.count { it.space in collected }}/${memos.size}개 챙김 확인", style = MaterialTheme.typography.titleMedium)
                for (memo in memos) {
                    androidx.compose.material3.Checkbox(checked = memo.space in collected,
                        onCheckedChange = if (editable) { checked -> onEvent(InspectionEvent.CollectLuggage(memo.space, checked)) } else null)
                    Text(memo.space.label + " · " + memo.item + if (memo.space in collected) " · 챙겼어요" else " · 직접 살펴봐 주세요")
                }
                if (!editable) Text("남은 항목은 휴대폰 사진·확인 화면에서 이어서 확인해 주세요.")
                Spacer(Modifier.height(12.dp))
            }
            if (seats.isNotEmpty()) StateBadge("점유 신호가 있는 좌석", attention = true)
            Spacer(Modifier.height(12.dp))
            Text(seats.joinToString(), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            Text("해당 좌석과 발밑에 두고 내리는 물건이 없는지 살펴봐 주세요.")
            Spacer(Modifier.height(12.dp))
            Text("사람과 짐은 구분하지 못해요. 비점유 좌석·트렁크 등 다른 공간도 직접 확인해 주세요.")
            val playing = speech.status in listOf(SeatSpeechStatus.PREPARING, SeatSpeechStatus.SPEAKING)
            Spacer(Modifier.height(12.dp))
            Text(when (speech.status) {
                SeatSpeechStatus.PREPARING -> "음성 안내를 준비하고 있어요."
                SeatSpeechStatus.SPEAKING -> "소지품 안내를 읽고 있어요."
                SeatSpeechStatus.DONE -> "음성 안내를 마쳤어요."
                SeatSpeechStatus.STOPPED -> "음성 안내를 중지했어요."
                SeatSpeechStatus.FAILED -> "음성 안내를 재생하지 못했어요. 화면을 확인해 주세요."
                SeatSpeechStatus.IDLE -> "필요하면 음성 안내를 들어보세요."
            })
            FilledTonalButton(onClick = { onEvent(if (playing) InspectionEvent.StopReturnSeatSpeech else InspectionEvent.ReplayReturnSeatSpeech) }) {
                Text(if (playing) "음성 중지" else "음성 다시 듣기")
            }
        } },
        confirmButton = { TextButton(onClick = { onEvent(InspectionEvent.DismissReturnSeatNotice) }) { Text("안내 확인") } },
    )
}
