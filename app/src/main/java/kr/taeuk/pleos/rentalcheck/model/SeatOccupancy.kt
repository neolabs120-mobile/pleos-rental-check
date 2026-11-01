package kr.taeuk.pleos.rentalcheck.model

/** Raw seat occupancy is a limited vehicle signal, never a person/object classifier or a luggage-clearance decision. */
object SeatOccupancy {
    // Retain the SDK's original Boolean separately because Vehicle 2.3.2 collapses VACANT(1) to true and UNKNOWN/OCCUPIED to false.
    fun reading(key: String, label: String, occupied: Boolean?, atMillis: Long): VehicleReading =
        if (occupied == null) VehicleReading.missing(key, label, ReadStatus.UNAVAILABLE, "좌석 점유 값을 수신하지 못했습니다.")
        else VehicleReading(key, label, ReadStatus.AVAILABLE, occupied.toString(),
            "SDK 원본 $occupied", detail = "Vehicle SDK 2.3.2의 원본 Boolean이며 점유 판정에는 사용하지 않습니다.", observedAtMillis = atMillis)

    // Decode the standard lossless enum: 0=UNKNOWN, 1=VACANT, 2=OCCUPIED; malformed and missing values must never become false.
    fun fromRaw(key: String, label: String, raw: String?, atMillis: Long): List<VehicleReading> {
        val number = raw?.toIntOrNull()
        val occupied = when (number) { 1 -> false; 2 -> true; else -> null }
        val original = VehicleReading(key.removeSuffix(".occupancy") + ".rawOccupancy", "$label 원시값",
            if (raw == null) ReadStatus.UNAVAILABLE else ReadStatus.AVAILABLE, raw, raw ?: "확인 불가", observedAtMillis = atMillis)
        val normalized = if (occupied == null) VehicleReading.missing(key, label, ReadStatus.UNAVAILABLE, "알 수 없는 좌석 상태: ${raw ?: "null"}")
            .copy(observedAtMillis = atMillis)
        else VehicleReading(key, label, ReadStatus.AVAILABLE, occupied.toString(), if (occupied) "점유" else "비점유",
            detail = "Pleos Vehicle Service 원시 정수 $number · 0=알 수 없음, 1=비점유, 2=점유", observedAtMillis = atMillis)
        return listOf(original, normalized)
    }

    // Select both individual seats and a domain-level failure without manufacturing unsupported seat areas.
    fun readings(snapshot: VehicleSnapshot?): List<VehicleReading> = snapshot?.readings.orEmpty().filter { isSeat(it.key) }

    // Remind only for a fresh paired raw enum and normalized reading; legacy SDK Booleans, failures and mismatched observations cannot imply occupancy.
    fun occupiedLabels(snapshot: VehicleSnapshot): List<String> = readings(snapshot).filter { seat ->
        val raw = snapshot.find(seat.key.removeSuffix(".occupancy") + ".rawOccupancy")
        seat.available && seat.value == "true" && seat.observedAtMillis > 0 && raw?.available == true &&
            raw.value?.toIntOrNull() == 2 && raw.observedAtMillis == seat.observedAtMillis
    }.map { it.label.removeSuffix(" 좌석 점유") }.distinct()

    // Keep only occupancy properties; seat controls and unrelated vehicle observations must not enter this presentation state.
    fun isSeat(key: String): Boolean = key.startsWith("seat.") && key.endsWith(".occupancy")

    // A domain failure invalidates earlier good values, while an individual failure replaces only that seat's previous reading.
    fun update(current: List<VehicleReading>, next: VehicleReading): List<VehicleReading> {
        if (!isSeat(next.key)) return current
        if (next.key == "seat.all.occupancy") return listOf(next)
        val previous = current.firstOrNull { it.key == next.key }
        if (next.observedAtMillis > 0 && previous != null && previous.observedAtMillis > next.observedAtMillis) return current
        return (current.filter { it.key != next.key && it.key != "seat.all.occupancy" } + next).sortedBy { it.key }
    }
}
