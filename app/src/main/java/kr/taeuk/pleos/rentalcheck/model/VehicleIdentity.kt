package kr.taeuk.pleos.rentalcheck.model

import java.util.Locale

object VehicleIdentity {
    private val pattern = Regex("[A-HJ-NPR-Z0-9]{17}")

    fun normalize(value: String?): String? = value?.trim()?.uppercase(Locale.ROOT)?.takeIf { pattern.matches(it) }
    fun vin(snapshot: VehicleSnapshot?): String? = snapshot?.find("vehicle.vin")?.takeIf { it.available }?.value?.let(::normalize)
    fun masked(snapshot: VehicleSnapshot?): String = vin(snapshot)?.let { "VIN ···${it.takeLast(6)}" } ?: "VIN 확인 불가"
    fun comparisonProblem(before: VehicleSnapshot, after: VehicleSnapshot): String? = when {
        vin(before) == null || vin(after) == null -> "양쪽 차량 VIN을 확인해야 비교할 수 있어요."
        vin(before) != vin(after) -> "인수·반납 차량 VIN이 다릅니다. 이 기록은 비교·제출할 수 없어요."
        else -> null
    }
}
