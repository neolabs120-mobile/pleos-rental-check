package kr.taeuk.pleos.rentalcheck.navigation

import kotlin.math.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class ReturnLocation(val name: String, val address: String, val latitude: Double, val longitude: Double,
    val instructions: String = "", val isTest: Boolean = false) {
    init {
        require(name.isNotBlank() && name.length <= 80 && address.isNotBlank() && address.length <= 200)
        require(validCoordinates(latitude, longitude) && instructions.length <= 400)
    }
}

data class LocationFix(val latitude: Double, val longitude: Double, val timestampMillis: Long) {
    // A last-known fix is useful only while its SDK timestamp is recent; receipt time must not make old coordinates appear current.
    fun fresh(now: Long) = validCoordinates(latitude, longitude) && timestampMillis > 0 && now - timestampMillis in -5_000..60_000
}

// Reject non-finite and out-of-range coordinates instead of substituting the equator or another plausible location.
fun validCoordinates(latitude: Double, longitude: Double) = latitude.isFinite() && longitude.isFinite() &&
    latitude in -90.0..90.0 && longitude in -180.0..180.0

// Compute an explicitly labelled straight-line estimate; it never proves arrival at a parking space or completes a return.
fun straightDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val dLat = Math.toRadians(lat2 - lat1); val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
    return 6_371_000 * 2 * asin(sqrt(a.coerceIn(0.0, 1.0)))
}

data class NavigationState(val connected: Boolean = false, val busy: Boolean = false, val confirmed: Boolean = false,
    val message: String = "길 안내 연결을 기다리고 있어요.")
data class ReturnGuideState(val destination: ReturnLocation? = null, val fix: LocationFix? = null,
    val locationMessage: String = "차량 위치를 기다리고 있어요.", val navigation: NavigationState = NavigationState(),
    val nowMillis: Long = 0, val locationPermissionRequired: Boolean = false) {
    val distanceMeters: Double? get() = destination?.let { p -> fix?.takeIf { it.fresh(nowMillis) }
        ?.let { straightDistance(it.latitude, it.longitude, p.latitude, p.longitude) } }
}

data class RouteProgress(val target: ReturnLocation, val completed: Boolean = false, val matched: Boolean = false,
    val started: Boolean = false, val guidanceActive: Boolean = false, val existingRoute: Boolean = false) {
    val confirmed: Boolean get() = matched && (completed || ((started || existingRoute) && guidanceActive))
    // Ignore unrelated destination broadcasts; completion alone must not claim our requested return route was installed.
    fun destination(latitude: Double, longitude: Double, present: Boolean = true) = copy(matched = present && validCoordinates(latitude, longitude) &&
        straightDistance(latitude, longitude, target.latitude, target.longitude) <= 50)
}

sealed interface GuideEvent {
    data class Position(val fix: LocationFix?) : GuideEvent
    data class LocationError(val message: String, val permissionRequired: Boolean = false) : GuideEvent
    data class Navigation(val state: NavigationState) : GuideEvent
}
interface ReturnGuideEngine {
    fun start(destination: ReturnLocation, receive: (GuideEvent) -> Unit)
    fun navigate()
    fun openNavigation()
    fun stop()
}

class ReturnGuideController(private val engine: ReturnGuideEngine, private val scope: CoroutineScope,
    private val changed: (ReturnGuideState) -> Unit, private val clock: () -> Long = System::currentTimeMillis) {
    var state = ReturnGuideState(); private set
    private var identity: String? = null
    private var generation = 0
    private var ticking: Job? = null

    // Scope foreground subscriptions to one approved booking and destination; changing either clears all previous live coordinates and callbacks.
    fun attach(key: String?, place: ReturnLocation?) {
        if (identity == key && state.destination == place) return
        generation++; ticking?.cancel(); engine.stop(); identity = key
        state = ReturnGuideState(destination = place, nowMillis = clock()); changed(state)
        if (key == null || place == null) return
        val token = generation
        engine.start(place) { event ->
            if (token == generation) {
                state = when (event) {
                    is GuideEvent.Position -> state.copy(fix = event.fix, locationMessage = if (event.fix == null)
                        "현재 위치를 받지 못했어요." else "차량 위치를 수신했어요.", locationPermissionRequired = false)
                    is GuideEvent.LocationError -> state.copy(fix = null, locationMessage = event.message, locationPermissionRequired = event.permissionRequired)
                    is GuideEvent.Navigation -> state.copy(navigation = event.state)
                }.copy(nowMillis = clock())
                changed(state)
            }
        }
        ticking = scope.launch { while (isActive) { delay(5_000); state = state.copy(nowMillis = clock()); changed(state) } }
    }

    // Only a live, initialized booking session can forward an explicitly confirmed route request.
    fun navigate() {
        check(identity != null && state.destination != null && state.navigation.connected && !state.navigation.busy)
        engine.navigate()
    }

    // Opening the actual Navigation app is separate from claiming that a route or arrival was verified.
    fun openNavigation() { if (identity != null && state.navigation.connected) engine.openNavigation() }

    // Recheck the existing session after a system permission result, retiring every old listener before reconnecting.
    fun retry() { val key = identity; val place = state.destination; stop(); attach(key, place) }

    // Release foreground-only location access without cancelling a route the driver may still be using in Navigation.
    fun stop() = attach(null, null)
}
