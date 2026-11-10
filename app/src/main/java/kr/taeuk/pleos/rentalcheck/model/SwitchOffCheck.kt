package kr.taeuk.pleos.rentalcheck.model

/** One fresh SDK reading that needs the renter's attention: something still switched on, or a door or trunk still open. */
data class SwitchOffItem(val key: String, val label: String, val state: String)

/**
 * Read-only "before you leave" review of the newest return snapshot.
 * Doors, the trunk and the bonnet all arrive as `door.*` readings.
 * Only `switchOff.*` readings whose value is exactly "true" count as left on, and only `door.*.closed`
 * readings whose value is exactly "false" count as left open; missing, failed or permission-denied reads
 * stay unknown, and features the vehicle does not support are not reported.
 * Unlocked doors are listed as a reminder only, because the renter locks the car after getting out.
 * The check never controls the vehicle and never blocks a return on its own.
 */
data class SwitchOffCheck(
    val leftOn: List<SwitchOffItem>,
    val unknown: List<String>,
    val offCount: Int,
    val leftOpen: List<SwitchOffItem> = emptyList(),
    val unlocked: List<String> = emptyList(),
    val closedCount: Int = 0,
) {
    // Identify the exact set of items the renter has already seen, so a newly switched-on or opened item prompts again.
    val signature: String get() = (leftOn + leftOpen).map { it.key }.sorted().joinToString("|")

    // Prompt only when something is known to be on or open and the renter has not acknowledged this same set.
    fun needsPrompt(acknowledged: String?): Boolean = (leftOn.isNotEmpty() || leftOpen.isNotEmpty()) && signature != acknowledged

    companion object {
        const val PREFIX = "switchOff."
        private const val DOOR = "door."

        fun from(snapshot: VehicleSnapshot?): SwitchOffCheck {
            val all = snapshot?.readings.orEmpty()
            val on = mutableListOf<SwitchOffItem>()
            val unknown = mutableListOf<String>()
            var off = 0
            all.filter { it.key.startsWith(PREFIX) }.forEach { reading ->
                when {
                    reading.status == ReadStatus.UNSUPPORTED -> Unit
                    !reading.available -> unknown.add(reading.label)
                    reading.value == "true" -> on.add(SwitchOffItem(reading.key, reading.label, reading.displayValue))
                    reading.value == "false" -> off++
                    else -> unknown.add(reading.label)
                }
            }
            val open = mutableListOf<SwitchOffItem>()
            var closed = 0
            all.filter { it.key.startsWith(DOOR) && it.key.endsWith(".closed") }.forEach { reading ->
                // The SDK names the trunk "REAR" and the bonnet "HOOD"; call them what the renter calls them.
                val label = when (reading.key) {
                    "door.REAR.closed" -> "트렁크"
                    "door.HOOD.closed" -> "보닛"
                    else -> reading.label.removeSuffix(" 닫힘 여부")
                }
                when {
                    reading.status == ReadStatus.UNSUPPORTED -> Unit
                    !reading.available -> unknown.add(label)
                    reading.value == "false" -> open.add(SwitchOffItem(reading.key, label, "열림"))
                    reading.value == "true" -> closed++
                    else -> unknown.add(label)
                }
            }
            // A failed whole-domain door read leaves no per-door row, so keep it visible instead of implying "all closed".
            all.firstOrNull { it.key == "door" && !it.available && it.status != ReadStatus.UNSUPPORTED }?.let { unknown.add(it.label) }
            // Some vehicles report all passenger doors as one lock group, so this is a reminder, not a per-door count.
            val unlocked = all.filter { it.key.startsWith(DOOR) && it.key.endsWith(".lock") && it.key != "door.HOOD.lock" && it.available && it.value == "false" }
                .sortedBy { it.key }.map { it.label.removeSuffix(" 잠금") }
            return SwitchOffCheck(on.sortedBy { it.key }, unknown.distinct(), off, open.sortedBy { it.key }, unlocked, closed)
        }

        // Join item names into short Korean sentences for the on-device speech prompt.
        fun spokenReminder(items: List<SwitchOffItem>, open: List<SwitchOffItem> = emptyList()): String = buildString {
            append("내리기 전에 확인해 주세요.")
            if (items.isNotEmpty()) append(" 지금 켜져 있는 것: ${items.joinToString(", ") { it.label }}. 다음 이용자를 위해 꺼 주세요.")
            if (open.isNotEmpty()) append(" 열려 있는 곳: ${open.joinToString(", ") { it.label }}. 닫아 주세요.")
        }
    }
}
