package kr.taeuk.pleos.rentalcheck.model

enum class ReadStatus {
    AVAILABLE, UNSUPPORTED, UNAVAILABLE, PERMISSION_DENIED, TIMED_OUT, ERROR
}

/** Missing or failed readings must never become zero, false, or "normal". */
data class VehicleReading(
    val key: String,
    val label: String,
    val status: ReadStatus,
    val value: String? = null,
    val displayValue: String = "확인할 수 없음",
    val unit: String? = null,
    val detail: String = "",
    val observedAtMillis: Long = 0,
) {
    val available: Boolean get() = status == ReadStatus.AVAILABLE && value != null
    val number: Double? get() = if (available) value?.toDoubleOrNull()?.takeIf { it.isFinite() } else null

    companion object {
        fun missing(key: String, label: String, status: ReadStatus, detail: String) =
            VehicleReading(key, label, status, detail = detail)
    }
}

/** Independent IPC reads span an interval; this is not an atomic vehicle snapshot. */
data class VehicleSnapshot(
    val startedAtMillis: Long,
    val completedAtMillis: Long,
    val readings: List<VehicleReading>,
    val source: String = "Pleos Vehicle SDK 2.3.2",
) {
    val availableCount: Int get() = readings.count { it.available }
    fun find(key: String): VehicleReading? = readings.firstOrNull { it.key == key }
}

data class InspectionRecord(
    val id: String,
    val pickup: VehicleSnapshot,
    val returned: VehicleSnapshot? = null,
    // User observations are independent from the SDK's available/unknown status.
    val pickupWindowManuallyChecked: Boolean = false,
    val returnWindowManuallyChecked: Boolean = false,
    val revision: Int = 1,
    val syncedRevision: Int = 0,
    val submittedAtMillis: Long? = null,
    val receiptId: String? = null,
    val bookingId: String? = null,
    val usageMemory: UsageMemory = UsageMemory(),
)
