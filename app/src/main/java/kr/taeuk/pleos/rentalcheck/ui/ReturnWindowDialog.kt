package kr.taeuk.pleos.rentalcheck.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kr.taeuk.pleos.rentalcheck.InspectionEvent
import kr.taeuk.pleos.rentalcheck.model.ReturnWindowCheck

@Composable
internal fun ReturnWindowDialog(check: ReturnWindowCheck, onEvent: (InspectionEvent) -> Unit) {
    val hasOpen = check.openWindows.isNotEmpty()
    AlertDialog(
        onDismissRequest = { onEvent(InspectionEvent.DismissReturnWindowCheck) },
        title = { Text(if (hasOpen) "반납 전에 창문을 닫아 주세요" else "창문을 직접 확인해 주세요") },
        text = {
            Column {
                if (hasOpen) {
                    Text("열린 창문: ${check.openWindows.joinToString()}")
                    Spacer(Modifier.height(12.dp))
                    Text("창문을 모두 닫은 뒤 다시 확인해 주세요.")
                }
                if (check.unknownWindows.isNotEmpty()) {
                    if (hasOpen) Spacer(Modifier.height(12.dp))
                    Text("확인하지 못한 창문: ${check.unknownWindows.joinToString()}")
                    Text("모든 창문이 닫혔는지 직접 확인해 주세요.")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onEvent(if (hasOpen) InspectionEvent.RetryReturnWindowCheck
                else InspectionEvent.ConfirmReturnWindowsManually) }) {
                Text(if (hasOpen) "닫은 뒤 다시 확인" else "직접 닫힘을 확인했어요")
            }
        },
        dismissButton = {
            TextButton(onClick = { onEvent(if (hasOpen) InspectionEvent.DismissReturnWindowCheck
                else InspectionEvent.RetryReturnWindowCheck) }) {
                Text(if (hasOpen) "돌아가기" else "다시 조회")
            }
        },
    )
}
