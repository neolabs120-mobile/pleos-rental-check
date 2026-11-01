package kr.taeuk.pleos.rentalcheck.model

/**
 * Interpret only the ordinary, non-tilting WINDOW_POS contract: min=0 is closed,
 * max>0 is fully open. Never treat an arbitrary capability minimum as closed.
 * Nonstandard ranges remain unknown until verified against that vehicle/service.
 * See Android VehiclePropertyIds.WINDOW_POS and README's window return check.
 */
object WindowPositionContract {
    fun closed(position: Int?, minimum: Int, maximum: Int): Boolean? {
        if (position == null || minimum != 0 || maximum <= 0 || position !in minimum..maximum) return null
        return position == 0
    }
}

data class ReturnWindowCheck(
    val openWindows: List<String>,
    val unknownWindows: List<String>,
    val closedCount: Int,
) {
    val allClosed: Boolean get() = closedCount > 0 && openWindows.isEmpty() && unknownWindows.isEmpty()
    val needsAttention: Boolean get() = !allClosed

    // A user observation can resolve missing data, but cannot override a known open window.
    fun canSave(manuallyChecked: Boolean): Boolean =
        openWindows.isEmpty() && (allClosed || manuallyChecked)

    companion object {
        fun from(snapshot: VehicleSnapshot?): ReturnWindowCheck {
            val readings = snapshot?.readings.orEmpty()
            val positions = readings.filter { it.key.startsWith("window.") && it.key.endsWith(".position") }
            val closures = readings.filter { it.key.startsWith("window.") && it.key.endsWith(".closed") }
                .associateBy { it.key.removeSuffix(".closed") }
            val open = mutableListOf<String>()
            val unknown = mutableListOf<String>()
            var closed = 0
            positions.forEach { position ->
                val closure = closures[position.key.removeSuffix(".position")]
                when {
                    !position.available || closure?.available != true -> unknown.add(position.label)
                    closure.value == "true" -> closed++
                    closure.value == "false" -> open.add(position.label)
                    else -> unknown.add(position.label)
                }
            }
            if (positions.isEmpty() || readings.any { it.key == "window" }) unknown.add("차량 창문")
            return ReturnWindowCheck(open.distinct(), unknown.distinct(), closed)
        }
    }
}
