package kr.taeuk.pleos.rentalcheck.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kr.taeuk.pleos.rentalcheck.*
import kr.taeuk.pleos.rentalcheck.model.StorageSpace
import kr.taeuk.pleos.rentalcheck.speech.SeatSpeechStatus

// Let the driver review both location and object text before an explicit save; opening the editor never starts recording.
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LuggageVoiceDialog(state: InspectionUiState, onEvent: (InspectionEvent) -> Unit) {
    val voice = state.voiceMemo; val draft = voice.draft
    val editing = !voice.capturing && !state.busy
    AlertDialog(onDismissRequest = { onEvent(InspectionEvent.CloseVoiceMemo) }, title = { Text("짐을 어디에 두셨나요?") }, text = {
        Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("P 상태에서 말하기를 누르고 “트렁크에 캐리어를 뒀어”처럼 말해 주세요.")
            FilledTonalButton(onClick = { onEvent(if (voice.capturing) InspectionEvent.StopVoiceRecognition else InspectionEvent.StartVoiceRecognition) },
                enabled = !state.busy, modifier = Modifier.heightIn(min = 52.dp)) { Text(when {
                    voice.phase == kr.taeuk.pleos.rentalcheck.speech.VoiceMemoPhase.FINALIZING -> "결과 기다리는 중 · 중지"
                    voice.capturing -> "듣기 중 · 중지"
                    else -> "말하기 시작"
                }) }
            if (draft.transcript.isNotBlank()) Text("인식한 내용 · ${draft.transcript}", style = MaterialTheme.typography.bodySmall)
            voice.problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Text("장소와 물건을 확인해 주세요", style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StorageSpace.entries.forEach { space -> FilterChip(selected = draft.space == space, enabled = editing,
                    onClick = { onEvent(InspectionEvent.EditVoiceMemo(space, draft.item)) }, label = { Text(space.label) }) }
            }
            OutlinedTextField(value = draft.item, onValueChange = { onEvent(InspectionEvent.EditVoiceMemo(draft.space, it)) },
                label = { Text("물건 이름 · 직접 수정 가능") }, supportingText = { Text("${draft.item.length}/40") },
                enabled = editing, modifier = Modifier.fillMaxWidth(), maxLines = 2)
            Muted("같은 장소의 메모는 새 내용으로 바뀌어요. 물건 감지 결과가 아닌 내 메모이며, 음성 원본은 저장하지 않아요.")
        }
    }, confirmButton = { TextButton(onClick = { onEvent(InspectionEvent.ConfirmVoiceMemo) },
        enabled = editing && draft.confirmed() != null) { Text("확인하고 저장") } },
        dismissButton = { TextButton(onClick = { onEvent(InspectionEvent.CloseVoiceMemo) }) { Text("취소") } })
}

// Offer a nearby-location suggestion with explicit acceptance; GPS proximity and P do not prove the operator's designated parking space.
@Composable
internal fun ReturnArrivalDialog(state: InspectionUiState, onEvent: (InspectionEvent) -> Unit) {
    val playing = state.arrivalSpeech.status in listOf(SeatSpeechStatus.PREPARING, SeatSpeechStatus.SPEAKING)
    AlertDialog(onDismissRequest = { onEvent(InspectionEvent.DismissArrivalPrompt) }, title = { Text("반납 장소 근처예요") }, text = {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(state.returnGuide.destination?.name.orEmpty(), style = MaterialTheme.typography.titleMedium)
            Text("지정 주차구역에 주차하셨다면 반납 준비를 시작해 주세요. 현재 위치와 P 상태를 확인했어요.")
            Text("창문 → 저장한 짐 메모 · 점유 좌석 → 사진과 직접 확인 순서로 안내해요.")
            Muted("근처에 있다는 안내예요. 주차구역이나 반납 완료를 자동으로 판단하지 않아요.")
            if (state.arrivalSpeech.status == SeatSpeechStatus.FAILED) Muted("음성 안내를 재생하지 못했어요. 화면을 확인해 주세요.")
            TextButton(onClick = { onEvent(if (playing) InspectionEvent.StopArrivalSpeech else InspectionEvent.ReplayArrivalSpeech) }) {
                Text(if (playing) "음성 중지" else "음성 다시 듣기")
            }
        }
    }, confirmButton = { TextButton(onClick = { onEvent(InspectionEvent.BeginArrivalReturn) }, enabled = !state.busy) { Text("반납 준비 시작") } },
        dismissButton = { TextButton(onClick = { onEvent(InspectionEvent.DismissArrivalPrompt) }) { Text("나중에") } })
}
