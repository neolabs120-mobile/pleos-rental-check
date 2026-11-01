package kr.taeuk.pleos.rentalcheck.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import java.time.OffsetDateTime
import kr.taeuk.pleos.rentalcheck.InspectionStage
import kr.taeuk.pleos.rentalcheck.InspectionUiState
import kr.taeuk.pleos.rentalcheck.model.*
import kr.taeuk.pleos.rentalcheck.cloud.*

/** Design-time fixtures only. The real data source never falls back to these values. */
private fun sampleSnapshot(returned: Boolean): VehicleSnapshot {
    val timestamp = OffsetDateTime.parse(if (returned) "2026-10-23T18:30:00+09:00" else "2026-10-22T10:00:00+09:00")
        .toInstant().toEpochMilli()
    fun reading(key: String, label: String, value: String, display: String, unit: String? = null) =
        VehicleReading(key, label, ReadStatus.AVAILABLE, value, display, unit, observedAtMillis = timestamp)
    val doors = listOf("ROW_1_LEFT" to "앞쪽 왼쪽", "ROW_1_RIGHT" to "앞쪽 오른쪽",
        "ROW_2_LEFT" to "뒤쪽 왼쪽", "ROW_2_RIGHT" to "뒤쪽 오른쪽")
    return VehicleSnapshot(timestamp - 1500, timestamp, buildList {
        add(reading("vehicle.vin", "차량 식별 번호(VIN)", "KMHTEST00TEST0001", "VIN ···ST0001"))
        add(reading("fuel.percent", "연료 잔량", if (returned) "58" else "80", if (returned) "58.0 %" else "80.0 %", "%"))
        add(VehicleReading.missing("battery.percent", "배터리 충전율", ReadStatus.UNSUPPORTED, "이 예시 차량은 연료 잔량만 지원합니다."))
        add(reading("odometer.km", "누적 주행거리", if (returned) "10186.5" else "10000",
            if (returned) "10,186.5 km" else "10,000.0 km", "km"))
        doors.forEach { (area, label) ->
            add(reading("door.$area.position", "$label 문", "0", "닫힘"))
            add(reading("door.$area.lock", "$label 잠금", "false", "잠금 해제"))
        }
        add(reading("window.ROW_1_LEFT.position", "앞쪽 왼쪽 창문", "0", "위치 값 0"))
        add(VehicleReading.missing("window.ROW_2_LEFT.position", "뒤쪽 왼쪽 창문", ReadStatus.TIMED_OUT, "응답 시간 초과(4초)"))
        add(reading("safety.activeWarnings", "수신된 경고 표시", if (returned) "TPMS" else "",
            if (returned) "타이어 공기압 경고등" else "수신된 경고 없음"))
        add(reading("safety.tpms", "타이어 경고(TPMS)", if (returned) "ON" else "OFF", if (returned) "켜짐" else "꺼짐"))
    }, source = "화면 미리보기용 예시 데이터")
}

@Composable
private fun PreviewScreen(stage: InspectionStage, connected: Boolean = true) {
    val pickup = sampleSnapshot(false)
    val current = sampleSnapshot(true)
    val record = if (stage == InspectionStage.PICKUP || stage == InspectionStage.CONNECT) null else InspectionRecord("preview-session", pickup,
        returned = current.takeIf { stage == InspectionStage.RESULT || stage == InspectionStage.EVIDENCE },
        pickupWindowManuallyChecked = true, revision = 2,
        submittedAtMillis = (current.completedAtMillis + 1000).takeIf { stage == InspectionStage.RESULT },
        receiptId = "preview-receipt".takeIf { stage == InspectionStage.RESULT })
    RentalCheckTheme {
        Column(Modifier.fillMaxSize()) {
            Text("화면 미리보기 · 예시 데이터", modifier = Modifier.padding(8.dp),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            Box(Modifier.weight(1f)) {
                RentalCheckScreen(InspectionUiState(current = if (!connected) null
                    else if (stage == InspectionStage.PICKUP || stage == InspectionStage.CONNECT) pickup else current,
                    record = record, stage = stage, message = "화면 미리보기용 예시 데이터",
                    serverConfigured = true, serverUrl = "https://example.invalid",
                    cloudMessage = "화면 미리보기용 서버 상태 · 실제 전송값 아님",
                    pairedBooking = PairedBooking("preview-booking", "RC-DEMO", "이용자", "KMHTEST00TEST0001"),
                    photoLink = if (stage == InspectionStage.EVIDENCE) PhotoLink(
                        "https://example.invalid/upload#token=preview-not-a-real-upload-token",
                        System.currentTimeMillis() + 900_000, "return") else null,
                    cloudSummary = record?.let { CloudSummary(it.id, it.revision,
                        listOf("windows", "belongings", "exterior").map { category -> EvidencePhoto(category, "return", category) },
                        EvidenceChecks(true, true, true)) }), {})
            }
        }
    }
}

@Preview(name = "인수 점검 · 가로", widthDp = 1000, heightDp = 740, showBackground = true)
@Composable
fun PickupScreenPreview() = PreviewScreen(InspectionStage.PICKUP)

@Preview(name = "이용 중 · 가로", widthDp = 1000, heightDp = 740, showBackground = true)
@Composable
fun ActiveScreenPreview() = PreviewScreen(InspectionStage.ACTIVE)

@Preview(name = "반납 점검 · 가로", widthDp = 1000, heightDp = 800, showBackground = true)
@Composable
fun ReturnScreenPreview() = PreviewScreen(InspectionStage.RETURN)

@Preview(name = "점검 결과 · 가로", widthDp = 1000, heightDp = 740, showBackground = true)
@Composable
fun ResultScreenPreview() = PreviewScreen(InspectionStage.RESULT)

@Preview(name = "인수 점검 · 휴대전화", widthDp = 360, heightDp = 800, showBackground = true)
@Composable
fun PhoneScreenPreview() = PreviewScreen(InspectionStage.PICKUP)

@Preview(name = "연결 대기 · 휴대전화", widthDp = 360, heightDp = 800, showBackground = true)
@Composable
fun DisconnectedScreenPreview() = PreviewScreen(InspectionStage.CONNECT, connected = false)

@Preview(name = "연결 확인 · 가로", widthDp = 1000, heightDp = 740, showBackground = true)
@Composable
fun ConnectionScreenPreview() = PreviewScreen(InspectionStage.CONNECT)

@Preview(name = "사진 · 직접 확인", widthDp = 1000, heightDp = 800, showBackground = true)
@Composable
fun EvidenceScreenPreview() = PreviewScreen(InspectionStage.EVIDENCE)

@Preview(name = "사진 · 직접 확인 · 휴대전화", widthDp = 360, heightDp = 900, showBackground = true)
@Composable
fun EvidencePhoneScreenPreview() = PreviewScreen(InspectionStage.EVIDENCE)

@Preview(name = "계정 QR 연결", widthDp = 1000, heightDp = 800, showBackground = true)
@Composable
fun AccountConnectionPreview() = RentalCheckTheme {
    Column(Modifier.fillMaxSize()) {
        Text("화면 미리보기 · 예시 QR · 실제 연결 불가", Modifier.padding(8.dp))
        Box(Modifier.weight(1f)) { AccountConnectionScreen(InspectionUiState(
            current = sampleSnapshot(false), serverConfigured = true,
            pairing = VehiclePairing("preview-pairing", "https://example.invalid/account#pair=preview-only-not-valid",
                System.currentTimeMillis() + 300_000)), {}) }
    }
}

@Preview(name = "야간 · 가로", widthDp = 1000, heightDp = 740, showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun DarkScreenPreview() = PreviewScreen(InspectionStage.RESULT)
