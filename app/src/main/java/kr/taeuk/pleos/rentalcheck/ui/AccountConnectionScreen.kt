package kr.taeuk.pleos.rentalcheck.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import kr.taeuk.pleos.rentalcheck.InspectionEvent
import kr.taeuk.pleos.rentalcheck.InspectionUiState
import kr.taeuk.pleos.rentalcheck.model.VehicleIdentity
import kotlinx.coroutines.delay

/** Explain verified-email reservation discovery and explicit QR approval while keeping account credentials on the renter's phone. */
@Composable
fun AccountConnectionScreen(state: InspectionUiState, onEvent: (InspectionEvent) -> Unit) {
    var settingsVisible by remember { mutableStateOf(false) }
    val busy = state.busy || state.accountBusy || state.cloudBusy
    val vin = VehicleIdentity.vin(state.current)
    val pairing = state.pairing
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(pairing) { while (true) { now = System.currentTimeMillis(); delay(1_000) } }
    Scaffold(topBar = {
        Surface { Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            RentalBrand()
            TextButton(onClick = { settingsVisible = true }, enabled = !busy) { Text("설정") }
        } }
    }) { insets ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(insets).padding(20.dp)) {
            val wide = maxWidth >= 840.dp
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("휴대전화 계정을 차량에 연결하세요", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("예약에 사용한 이메일로 로그인하고, 이 차량의 QR을 승인해 주세요.", style = MaterialTheme.typography.bodyLarge)
                if (state.emulatorTestAvailable) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text("에뮬레이터 전용 테스트 VIN", fontWeight = FontWeight.Bold)
                        Muted("VIN만 테스트 값으로 대체하고, 창문 등 나머지 항목은 실제 SDK에서 읽어요.")
                    }
                    Switch(checked = state.emulatorTestEnabled,
                        onCheckedChange = { onEvent(InspectionEvent.SetEmulatorTestMode(it)) },
                        enabled = !busy && state.record == null && state.pairing == null)
                }
                TwoColumns(wide, left = {
                    Panel {
                        PanelTitle("1. 차량 정보 확인")
                        Text(if (vin == null) "차량 VIN 수신 대기" else "VIN ···${vin.takeLast(6)}", style = MaterialTheme.typography.titleLarge)
                        Muted(if (vin == null) "Pleos 전용 환경에서 차량 정보를 받은 뒤 연결 QR을 만들 수 있어요."
                            else "이 차량과 대여 건의 VIN이 일치해야 연결할 수 있어요.")
                        OutlinedButton(onClick = { onEvent(InspectionEvent.Refresh) }, enabled = !busy,
                            modifier = Modifier.padding(top = 12.dp).heightIn(min = 52.dp)) { Text("차량 정보 다시 읽기") }
                    }
                    Panel {
                        PanelTitle("2. 휴대전화에서 로그인")
                        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            Text("예약에 사용한 이메일로 로그인하면 내 예약이 자동으로 보여요.", style = MaterialTheme.typography.bodyLarge)
                            Text("QR을 스캔하고 ‘이 차량 연결 승인’을 눌러 주세요.", style = MaterialTheme.typography.bodyLarge)
                            Muted("처음 이용한다면 계정을 만들고 이메일을 확인해 주세요. 비밀번호는 차량에 입력하거나 보관하지 않아요.")
                        }
                    }
                }, right = {
                    Panel {
                        PanelTitle("3. 계정 연결 QR")
                        Column(Modifier.fillMaxWidth().heightIn(min = if (wide) 420.dp else 0.dp),
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        if (pairing != null && pairing.expiresAtMillis > now) {
                            val bitmap = remember(pairing.pairingUrl) {
                                runCatching {
                                    val matrix = MultiFormatWriter().encode(pairing.pairingUrl, BarcodeFormat.QR_CODE, 300, 300)
                                    Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888).apply {
                                        setPixels(IntArray(90_000) { index -> if (matrix[index % 300, index / 300])
                                            android.graphics.Color.BLACK else android.graphics.Color.WHITE }, 0, 300, 0, 0, 300, 300)
                                    }
                                }.getOrNull()
                            }
                            bitmap?.let { Image(it.asImageBitmap(), "휴대전화 계정 연결 QR", Modifier.size(280.dp).background(Color.White)) }
                            Muted("남은 시간 ${(pairing.expiresAtMillis - now) / 1_000}초 · 휴대전화 카메라로 스캔")
                        } else {
                            PhoneConnectionMark()
                            Text("휴대전화에서 연결을 승인해요", style = MaterialTheme.typography.titleLarge)
                            Muted(if (vin == null) "차량 정보를 확인한 뒤 이곳에 연결 QR이 표시돼요."
                                else "아래 버튼을 눌러 이 차량의 연결 QR을 만들어 주세요.")
                        }
                        Text(state.accountMessage)
                        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        Button(onClick = { onEvent(InspectionEvent.CreateAccountQr) }, enabled = !busy && state.serverConfigured && vin != null,
                            modifier = Modifier.widthIn(min = 260.dp).heightIn(min = 56.dp)) {
                            Text(if (pairing == null) "계정 연결 QR 만들기" else "새 연결 QR 만들기")
                        }
                        TextButton(onClick = { onEvent(InspectionEvent.CheckAccountConnection) }, enabled = !busy) { Text("연결 승인 상태 확인") }
                        if (!state.serverConfigured) Muted("상단 설정에서 플랫폼 연결을 먼저 완료해 주세요.")
                        }
                    }
                })
                ConnectionJourney(wide)
            }
        }
    }
    if (settingsVisible) ServerSettingsDialog(state.serverUrl, onDismiss = { settingsVisible = false }, onSave = { url, token ->
        settingsVisible = false; onEvent(InspectionEvent.ConfigureServer(url, token))
    })
}

// Explain the upcoming rental steps while the renter waits for pairing, giving the lower landscape area useful onboarding content.
@Composable
private fun ConnectionJourney(wide: Boolean) {
    val steps = listOf("인수" to "출발 전 상태 저장", "이용 중" to "짐을 둔 공간 기억", "반납" to "소지품 안내 · 사진 확인")
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("연결 후에는 이렇게 이용해요", style = MaterialTheme.typography.titleMedium)
        if (wide) Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            steps.forEachIndexed { index, (title, detail) ->
                Box(Modifier.weight(1f)) { ConnectionStep(index, title, detail) }
            }
        } else Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            steps.forEachIndexed { index, (title, detail) -> ConnectionStep(index, title, detail) }
        }
    }
}

// Reuse the same numbered onboarding card in horizontal and stacked layouts, keeping its copy and reading order consistent.
@Composable
private fun ConnectionStep(index: Int, title: String, detail: String) {
    Panel {
        StateBadge("0${index + 1}")
        Text(title, Modifier.padding(top = 10.dp, bottom = 4.dp), style = MaterialTheme.typography.titleLarge)
        Text(detail, style = MaterialTheme.typography.bodyLarge)
    }
}
