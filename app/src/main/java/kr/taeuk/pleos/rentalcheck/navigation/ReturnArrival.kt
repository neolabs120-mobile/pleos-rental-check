package kr.taeuk.pleos.rentalcheck.navigation

import kr.taeuk.pleos.rentalcheck.model.VehicleSnapshot

/** A foreground suggestion, never a parking-space determination or an automatic inspection/return action. */
class ReturnArrival {
    private var identity: String? = null
    private var place: ReturnLocation? = null
    private var lastFix = 0L
    private var nearCount = 0
    private var nearStartedAtMillis = 0L
    private var announced = false
    private var dismissed = false
    var visible = false; private set

    // Require two distinct recent fixes inside 150 m and a current P/VIN probe; stale data or leaving the foreground hides the suggestion.
    fun update(key: String?, guide: ReturnGuideState, snapshot: VehicleSnapshot?, expectedVin: String?, now: Long, eligible: Boolean): Boolean {
        if (identity != key || place != guide.destination) {
            identity = key; place = guide.destination; lastFix = 0; nearCount = 0; nearStartedAtMillis = 0; announced = false; dismissed = false; visible = false
        }
        val fix = guide.fix
        val distance = guide.copy(nowMillis = now).distanceMeters
        val near = distance != null && distance <= 150 && fix != null && now - fix.timestampMillis in -5_000..15_000
        if (!eligible || key == null || !near) { visible = false; nearCount = 0; nearStartedAtMillis = 0; lastFix = 0; return false }
        if (fix!!.timestampMillis > lastFix) {
            if (nearCount == 0) nearStartedAtMillis = fix.timestampMillis
            nearCount++; lastFix = fix.timestampMillis
        }
        val safe = stationaryFor(snapshot, expectedVin, now) &&
            (snapshot?.find("gear.selection")?.observedAtMillis ?: 0) >= nearStartedAtMillis
        if (!safe) visible = false
        if (!dismissed && nearCount >= 2 && safe) {
            val first = !announced; visible = true; announced = true; return first
        }
        return false
    }

    // Dismiss once per rental/destination so account polling, GPS jitter and reopening the screen cannot repeatedly interrupt the renter.
    fun dismiss() { visible = false; announced = true; dismissed = true }
}

// Match fresh identity and gear readings before any microphone session, memo write or arrival prompt; unknown and future readings fail closed.
fun stationaryFor(snapshot: VehicleSnapshot?, vin: String?, now: Long): Boolean {
    if (vin == null) return false
    val gear = snapshot?.find("gear.selection"); val identity = snapshot?.find("vehicle.vin")
    return listOf(gear, identity).all { it?.available == true && now - it.observedAtMillis in -2_000..8_000 } &&
        gear?.value == "GEAR_PARK" && identity?.value == vin
}
