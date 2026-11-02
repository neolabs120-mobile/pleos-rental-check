package kr.taeuk.pleos.rentalcheck.model

data class BatteryReturn(val target: Double?, val current: Double?) {
    val shortfall: Double? get() = target?.let { reference -> current?.let { (reference - it).coerceAtLeast(0.0) } }

    companion object {
        // Preserve the immutable pickup percentage as the target; invalid or mismatched vehicle readings remain unknown rather than becoming zero.
        fun from(pickup: VehicleSnapshot?, current: VehicleSnapshot?): BatteryReturn {
            val identity = pickup?.let { VehicleIdentity.vin(it) }
            return BatteryReturn(percent(pickup), if (identity != null && current?.let { VehicleIdentity.vin(it) } == identity) percent(current) else null)
        }

        // Read a real successful percentage only; unsupported combustion vehicles and malformed numbers cannot define an EV target.
        private fun percent(snapshot: VehicleSnapshot?): Double? = snapshot?.find("battery.percent")?.number?.takeIf { it in 0.0..100.0 }
    }
}
