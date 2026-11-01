package kr.taeuk.pleos.rentalcheck.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import kr.taeuk.pleos.rentalcheck.*
import kr.taeuk.pleos.rentalcheck.cloud.PhotoLink
import kotlinx.coroutines.delay

@Composable
internal fun CloudStatusCard(state: InspectionUiState, onEvent: (InspectionEvent) -> Unit, onSettings: () -> Unit) {
    Panel {
        PanelTitle("플랫폼 저장 상태")
        Muted(state.cloudMessage)
        state.cloudError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        if (state.cloudBusy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = onSettings, enabled = !state.busy && !state.cloudBusy) {
                Text(if (state.serverConfigured) "연결 설정" else "플랫폼 연결 설정")
            }
            if (state.record != null) TextButton(onClick = { onEvent(InspectionEvent.SyncServer) },
                enabled = !state.busy && !state.cloudBusy) { Text("전송 · 상태 확인") }
        }
    }
}

@Composable
internal fun EvidenceContent(state: InspectionUiState, wide: Boolean, onEvent: (InspectionEvent) -> Unit) {
    TwoColumns(wide, left = {
        Panel {
            PanelTitle("반납 사진 · 직접 확인")
            val summary = state.cloudSummary
            listOf("windows" to "창문 닫힘", "belongings" to "실내 · 소지품", "exterior" to "외관 상태").forEach { (key, label) ->
                val confirmed = when (key) {
                    "windows" -> summary?.checks?.windows
                    "belongings" -> summary?.checks?.belongings
                    else -> summary?.checks?.exterior
                } == true
                SmallComparison(label, if (summary == null) "서버 상태 확인 필요" else
                    "사진 ${summary.count(key)}장 · ${if (confirmed) "직접 확인 완료" else "직접 확인 미기록"}")
            }
            Muted("휴대전화에서 항목별 사진을 1장 이상 전송하고 직접 확인 내역도 보내 주세요.")
            summary?.checks?.note?.takeIf { it.isNotBlank() }?.let { Muted("남긴 내용: $it") }
            TextButton(onClick = { onEvent(InspectionEvent.SyncServer) }, enabled = !state.cloudBusy) {
                Text("사진 전송 상태 확인")
            }
        }
    }, right = {
        PhotoLinkCard(state.photoLink?.takeIf { it.phase == "return" }, state.cloudBusy,
            title = "휴대전화로 사진 보내기", onCreate = { onEvent(InspectionEvent.CreatePhotoLink("return")) })
    })
}

@Composable
internal fun PhotoLinkCard(link: PhotoLink?, busy: Boolean, title: String, onCreate: () -> Unit) {
    val context = LocalContext.current
    var expired by remember(link) { mutableStateOf(link != null && link.expiresAtMillis <= System.currentTimeMillis()) }
    LaunchedEffect(link) {
        if (link != null) {
            delay((link.expiresAtMillis - System.currentTimeMillis()).coerceAtLeast(0))
            expired = true
        }
    }
    Panel {
        PanelTitle(title)
        if (link != null && !expired) {
            val bitmap = remember(link.uploadUrl) {
                runCatching {
                    val matrix = MultiFormatWriter().encode(link.uploadUrl, BarcodeFormat.QR_CODE, 300, 300)
                    Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888).apply {
                        setPixels(IntArray(90_000) { i -> if (matrix[i % 300, i / 300]) android.graphics.Color.BLACK
                            else android.graphics.Color.WHITE }, 0, 300, 0, 0, 300, 300)
                    }
                }.getOrNull()
            }
            bitmap?.let { Image(it.asImageBitmap(), contentDescription = "이 이용 건의 사진 전송 링크 QR",
                modifier = Modifier.size(200.dp).background(Color.White)) }
            Muted("휴대전화 카메라로 QR을 스캔해 주세요.")
            Muted("유효 시간 ${inspectionTime(link.expiresAtMillis)}까지 · 이 이용 건의 사진만 전송 가능")
            TextButton(onClick = {
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("사진 전송 링크", link.uploadUrl))
            }) { Text("사진 전송 링크 복사") }
        } else Muted(if (expired) "링크가 만료됐어요. 새 링크를 발급해 주세요."
            else "차량 화면에서 링크를 발급한 뒤 휴대전화로 사진을 첨부할 수 있어요.")
        OutlinedButton(onClick = onCreate, enabled = !busy) {
            Text(if (link == null) "사진 전송 링크 만들기" else "새 링크 발급")
        }
    }
}

@Composable
internal fun ServerSettingsDialog(initialUrl: String, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var url by rememberSaveable { mutableStateOf(initialUrl) }
    // Do not save an entered credential into instance state or screenshots.
    var token by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("플랫폼 연결 설정") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("기록을 보관할 플랫폼의 서버 주소와 차량 앱 접근 키를 입력해 주세요.")
            OutlinedTextField(url, onValueChange = { url = it }, singleLine = true,
                label = { Text("서버 주소") }, placeholder = { Text("https://rental.example.com") })
            OutlinedTextField(token, onValueChange = { token = it }, singleLine = true,
                label = { Text("차량 앱 접근 키") }, visualTransformation = PasswordVisualTransformation())
            Text("휴대전화 사진 전송 링크에는 차량 앱 접근 키를 포함하지 않아요.", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = { onSave(url, token) }, enabled = url.isNotBlank() && token.isNotBlank()) { Text("저장") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } })
}
