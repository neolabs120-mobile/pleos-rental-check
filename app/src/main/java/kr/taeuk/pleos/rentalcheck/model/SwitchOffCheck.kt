package kr.taeuk.pleos.rentalcheck.model

/** One fresh SDK reading that reports something still switched on (lights, signals, wipers, heaters). */
data class SwitchOffItem(val key: String, val label: String, val state: String)

/**
 * Read-only "switch off before leaving" review of the newest return snapshot.
 * Only `switchOff.*` readings whose value is exactly "true" count as left on; missing, failed or
 * permission-denied reads stay unknown, and features the vehicle does not support are not reported.
 * The check never controls the vehicle and never blocks a return on its own.
 */
data class SwitchOffCheck(
    val leftOn: List<SwitchOffItem>,
    val unknown: List<String>,
    val offCount: Int,
) {
    // Identify the exact set of items the renter has already seen, so a newly switched-on item prompts again.
    val signature: String get() = leftOn.map { it.key }.sorted().joinToString("|")

    // Prompt only when something is known to be on and the renter has not acknowledged this same set.
    fun needsPrompt(acknowledged: String?): Boolean = leftOn.isNotEmpty() && signature != acknowledged

    companion object {
        const val PREFIX = "switchOff."

        fun from(snapshot: VehicleSnapshot?): SwitchOffCheck {
            val readings = snapshot?.readings.orEmpty().filter { it.key.startsWith(PREFIX) }
            val on = mutableListOf<SwitchOffItem>()
            val unknown = mutableListOf<String>()
            var off = 0
            readings.forEach { reading ->
                when {
                    reading.status == ReadStatus.UNSUPPORTED -> Unit
                    !reading.available -> unknown.add(reading.label)
                    reading.value == "true" -> on.add(SwitchOffItem(reading.key, reading.label, reading.displayValue))
                    reading.value == "false" -> off++
                    else -> unknown.add(reading.label)
                }
            }
            return SwitchOffCheck(on.sortedBy { it.key }, unknown.distinct(), off)
        }

        // Join item names into one short Korean sentence for the on-device speech prompt.
        fun spokenReminder(items: List<SwitchOffItem>): String =
            "내리기 전에 확인해 주세요. 지금 켜져 있는 것: ${items.joinToString(", ") { it.label }}. 다음 이용자를 위해 꺼 주세요."
    }
}
