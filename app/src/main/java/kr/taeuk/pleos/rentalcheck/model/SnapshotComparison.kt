package kr.taeuk.pleos.rentalcheck.model

import kotlin.math.abs

enum class ChangeKind { CHANGED, UNCHANGED, NOT_COMPARABLE }

data class ReadingChange(
    val key: String,
    val label: String,
    val before: VehicleReading?,
    val after: VehicleReading?,
    val kind: ChangeKind,
)

data class ComparisonReport(
    val distanceKm: Double?,
    val distanceProblem: String?,
    val fuelPercentagePointChange: Double?,
    val batteryPercentagePointChange: Double?,
    val newWarningCodes: Set<String>?,
    val changes: List<ReadingChange>,
)

object SnapshotComparison {
    fun compare(before: VehicleSnapshot, after: VehicleSnapshot): ComparisonReport {
        val old = before.readings.associateBy { it.key }
        val current = after.readings.associateBy { it.key }
        val identityProblem = VehicleIdentity.comparisonProblem(before, after)
        val changes = (old.keys + current.keys).sorted().map { key ->
            val a = old[key]
            val b = current[key]
            ReadingChange(
                key, b?.label ?: a?.label ?: key, a, b,
                when {
                    identityProblem != null -> ChangeKind.NOT_COMPARABLE
                    a?.available != true || b?.available != true -> ChangeKind.NOT_COMPARABLE
                    a.unit != b.unit -> ChangeKind.NOT_COMPARABLE
                    a.number != null && b.number != null ->
                        if (abs(a.number!! - b.number!!) < 0.0001) ChangeKind.UNCHANGED else ChangeKind.CHANGED
                    a.value == b.value -> ChangeKind.UNCHANGED
                    else -> ChangeKind.CHANGED
                },
            )
        }
        val a = old["odometer.km"]?.takeIf { it.unit == "km" }?.number
        val b = current["odometer.km"]?.takeIf { it.unit == "km" }?.number
        val backwardsTime = after.startedAtMillis < before.completedAtMillis
        val distanceProblem = when {
            identityProblem != null -> identityProblem
            backwardsTime -> "측정 시간 순서를 확인해 주세요."
            a == null || b == null -> "양쪽 누적 주행거리 데이터가 필요합니다."
            b < a -> "누적 주행거리가 감소했습니다. 차량 변경 또는 수집값을 확인해 주세요."
            else -> null
        }
        fun percentDelta(key: String): Double? {
            val x = old[key]?.takeIf { it.unit == "%" }?.number ?: return null
            val y = current[key]?.takeIf { it.unit == "%" }?.number ?: return null
            return if (backwardsTime || identityProblem != null) null else y - x
        }
        fun warningCodes(reading: VehicleReading?): Set<String>? =
            reading?.takeIf { it.available }?.value?.split('|')?.filter { it.isNotBlank() }?.toSet()

        val oldWarnings = warningCodes(old["safety.activeWarnings"])
        val newWarnings = warningCodes(current["safety.activeWarnings"])
        return ComparisonReport(
            distanceKm = if (distanceProblem == null) b!! - a!! else null,
            distanceProblem = distanceProblem,
            fuelPercentagePointChange = percentDelta("fuel.percent"),
            batteryPercentagePointChange = percentDelta("battery.percent"),
            newWarningCodes = if (identityProblem == null && !backwardsTime && oldWarnings != null && newWarnings != null)
                newWarnings - oldWarnings else null,
            changes = changes,
        )
    }
}
