package kr.taeuk.pleos.rentalcheck.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kr.taeuk.pleos.rentalcheck.model.ReadStatus
import kr.taeuk.pleos.rentalcheck.model.VehicleReading
import kr.taeuk.pleos.rentalcheck.model.VehicleSnapshot

internal enum class DetailGroup(val title: String) {
    ENERGY("연료 · 배터리"), DOORS("문 개폐 · 잠금"), WINDOWS("창문 상태"), WARNINGS("수집된 경고등"),
    SWITCH_OFF("조명 · 와이퍼 · 열선"), ALL("전체 수집 내역");

    fun readings(snapshot: VehicleSnapshot?): List<VehicleReading> = snapshot?.readings.orEmpty().filter {
        when (this) {
            ENERGY -> it.key == "fuel.percent" || it.key == "battery.percent" || it.key == "odometer.km"
            DOORS -> it.key.startsWith("door.") || it.key == "door" || it.key == "doorLock"
            WINDOWS -> it.key.startsWith("window.") || it.key == "window"
            WARNINGS -> it.key.startsWith("safety.") || it.key == "safety"
            SWITCH_OFF -> it.key.startsWith(kr.taeuk.pleos.rentalcheck.model.SwitchOffCheck.PREFIX)
            ALL -> true
        }
    }
}

internal fun energyReadings(snapshot: VehicleSnapshot?): List<VehicleReading> {
    val readings = snapshot?.readings.orEmpty().filter { it.key == "fuel.percent" || it.key == "battery.percent" }
    return readings.filter { it.status != ReadStatus.UNSUPPORTED }.ifEmpty { readings }
}

internal fun doorSummary(snapshot: VehicleSnapshot?): String {
    val doors = snapshot?.readings.orEmpty().filter { it.key == "door" || (it.key.startsWith("door.") && it.key.endsWith(".position")) }
    if (doors.isEmpty()) return "문 상태 확인 불가"
    val closed = doors.count { it.number == 0.0 }
    val open = doors.count { it.number == 1.0 }
    val unknown = doors.size - closed - open
    return listOfNotNull(
        closed.takeIf { it > 0 }?.let { "닫힘 $it" },
        open.takeIf { it > 0 }?.let { "열림 $it" },
        unknown.takeIf { it > 0 }?.let { "확인 필요 $it" },
    ).joinToString(" · ")
}

internal fun warningCodes(snapshot: VehicleSnapshot?): Set<String>? =
    snapshot?.find("safety.activeWarnings")?.takeIf { it.available }?.value
        ?.split('|')?.filter { it.isNotBlank() }?.toSet()

internal fun warningName(code: String): String = when (code) {
    "TPMS" -> "타이어 공기압"
    "ABS" -> "ABS"
    "MDPS" -> "조향 장치"
    "AIRBAG" -> "에어백"
    "ENGINE" -> "엔진"
    "ENGINE_OIL_PRESSURE" -> "엔진 오일 압력"
    "ENGINE_TEMPERATURE" -> "엔진 온도"
    "LOW_FUEL" -> "연료 부족"
    "LOW_EV_BATTERY" -> "배터리 잔량 부족"
    "LOW_VOLT_BATTERY" -> "저전압 배터리"
    "EV_SYSTEM" -> "전기차 시스템"
    "MASTER_WARNING" -> "통합 경고"
    else -> code.replace('_', ' ')
}

internal fun readingStatus(status: ReadStatus): String = when (status) {
    ReadStatus.AVAILABLE -> "수집됨"
    ReadStatus.UNSUPPORTED -> "지원하지 않는 항목"
    ReadStatus.UNAVAILABLE -> "확인 불가"
    ReadStatus.PERMISSION_DENIED -> "조회 권한 없음"
    ReadStatus.TIMED_OUT -> "응답 시간 초과"
    ReadStatus.ERROR -> "조회 실패"
}

internal fun valueText(reading: VehicleReading?): String =
    if (reading?.available == true) reading.displayValue else reading?.let { readingStatus(it.status) } ?: "확인 불가"

internal fun inspectionTime(millis: Long): String = if (millis <= 0) "수집 시각 없음"
    else SimpleDateFormat("MM.dd HH:mm", Locale.KOREA).format(Date(millis))

internal fun displayNumber(number: Double): String = String.format(Locale.KOREA, "%,.1f", number)
internal fun signedNumber(number: Double): String = String.format(Locale.KOREA, "%+,.1f", number)
