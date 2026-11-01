package kr.taeuk.pleos.rentalcheck.navigation

import android.content.Context
import android.content.pm.PackageManager
import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.NaviMode
import ai.pleos.playground.navi.data.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

const val PLEOS_LOCATION_PERMISSION = "pleos.car.permission.FUSED_LOCATION"

class PleosReturnGuideEngine(private val context: Context, private val scope: CoroutineScope,
    private val diagnostic: (String) -> Unit = {}) : ReturnGuideEngine {
    private var generation = 0
    private var location: FusedLocation? = null
    private var locationListener: FusedLocationListener? = null
    private var navigation: NaviHelper? = null
    private var navigationListener: NaviHelperEventListener? = null
    private var receiver: ((GuideEvent) -> Unit)? = null
    private var destination: ReturnLocation? = null
    private var progress: RouteProgress? = null
    private var routeTimeout: Job? = null
    private var connected = false

    // Marshal real Binder events to the owning scope and discard callbacks from retired SDK instances.
    private fun deliver(token: Int, event: () -> Unit) { scope.launch { if (generation == token) event() } }

    // Report SDK connection and route outcomes independently; issuing requestRoute is never treated as route-search success.
    private fun report(message: String, busy: Boolean = false, confirmed: Boolean = false) {
        receiver?.invoke(GuideEvent.Navigation(NavigationState(connected, busy, confirmed, message)))
    }

    // Read foreground vehicle positions from Pleos and connect to Navigation independently so a location failure does not fabricate a route result.
    override fun start(destination: ReturnLocation, receive: (GuideEvent) -> Unit) {
        stop(); val token = generation; this.destination = destination; receiver = receive
        try {
            val fused = FusedLocation(context); location = fused
            if (!fused.isSupported()) receive(GuideEvent.LocationError("이 환경은 Pleos 차량 위치를 지원하지 않아요."))
            else if (context.checkSelfPermission(PLEOS_LOCATION_PERMISSION) != PackageManager.PERMISSION_GRANTED)
                receive(GuideEvent.LocationError("차량 위치 사용 권한이 필요해요.", permissionRequired = true))
            else {
                fused.initialize()
                val listener = object : FusedLocationListener {
                    // Preserve the SDK's own timestamp, including null and stale last-known results.
                    override fun onChanged(data: LocationData?) = deliver(token) {
                        receiver?.invoke(GuideEvent.Position(data?.let { LocationFix(it.latitude, it.longitude, it.timestampMillis) }))
                    }
                    // Clear the estimate on permission or service failures instead of retaining an apparently current distance.
                    override fun onError(exception: Exception) = deliver(token) {
                        diagnostic("locationError: ${exception.javaClass.simpleName}: ${exception.message}")
                        receiver?.invoke(GuideEvent.LocationError("차량 위치를 확인하지 못했어요. 위치 권한과 서비스를 확인해 주세요."))
                    }
                }
                locationListener = listener; fused.registerFusedLocationCallback(listener); fused.getLastKnownLocation(listener)
            }
        } catch (error: Exception) { diagnostic("locationStart: ${error.javaClass.simpleName}: ${error.message}"); receive(GuideEvent.LocationError("차량 위치 연결을 사용할 수 없어요.")) }
        try {
            val navi = NaviHelper(context); navigation = navi
            val listener = object : NaviHelperEventListener {
                // Query actual destination and navigation state on the emulator's search-start response without assuming it proves completion.
                override fun onRouteStarted(info: RouteStartInfo) = deliver(token) {
                    diagnostic("routeStarted: $info")
                    if (progress != null) {
                        progress = progress?.copy(started = info.name == progress?.target?.name)
                        navi.getDestinationInfo(); navi.getRouteStateInfo()
                    }
                }
                // Some service versions emit an ended event separately from routing-completed; inspect state rather than translating it into success.
                override fun onRouteEnded() = deliver(token) {
                    diagnostic("routeEnded"); if (progress != null) { navi.getDestinationInfo(); navi.getRouteStateInfo() }
                }
                // Keep real service mode information available to emulator diagnostics without exposing route traces in the product UI.
                override fun onRouteStateInfo(info: RouteStateInfo) = deliver(token) {
                    diagnostic("routeState: mode=${info.naviMode}, routing=${info.isRouting}, terms=${info.termsAgreement}")
                    val wasConfirmed = progress?.confirmed == true
                    progress = progress?.copy(guidanceActive = info.naviMode == NaviMode.ROUTE_GUIDING && !info.isRouting)
                    if (wasConfirmed && progress?.confirmed != true) { progress = null; report("내비 안내 상태가 바뀌었어요. 반납 장소를 다시 확인해 주세요.") }
                    else routeState()
                }
                // Route completion still needs a matching destination response before the app reports a verified return route.
                override fun onRoutingCompleted() = deliver(token) {
                    diagnostic("routingCompleted")
                    progress = progress?.copy(completed = true); navi.getDestinationInfo(); routeState()
                }
                // Destination changes may originate in another app; inspect their coordinates before associating them with this booking.
                override fun onDestinationChanged() = deliver(token) { if (progress != null) navi.getDestinationInfo() }
                // Match only the requested return coordinates, leaving an unrelated destination unconfirmed.
                override fun onDestinationInfo(info: DestinationInfo) = deliver(token) {
                    diagnostic("destination: latitude=${info.latitude}, longitude=${info.longitude}, name=${info.name}")
                    val wasConfirmed = progress?.confirmed == true
                    progress = progress?.destination(info.latitude, info.longitude, info.name.isNotBlank() || info.address.isNotBlank())
                    if (wasConfirmed && progress?.confirmed != true) { progress = null; report("내비 목적지가 바뀌었어요. 반납 장소를 다시 확인해 주세요.") }
                    else routeState()
                }
                // Preserve explicit route errors and permit a deliberate retry without replacing failure with a map-app fallback.
                override fun onError(errorCode: NaviErrorCode) = deliver(token) {
                    progress = null; routeTimeout?.cancel(); report("길 안내를 시작하지 못했어요. (${errorCode.name})")
                }
                // An externally cancelled route must not leave the return card claiming that guidance remains confirmed.
                override fun onRouteCancelled() = deliver(token) {
                    // Replacing an old destination can cancel the previous route before our new route begins; keep the new request pending for its own verified result.
                    if (progress != null && progress?.confirmed != true) { diagnostic("cancelledWhileRequestPending"); navi.getDestinationInfo() }
                    else { progress = null; routeTimeout?.cancel(); report("내비게이션 안내가 취소됐어요.") }
                }
            }
            navigationListener = listener
            navi.initialize(onConnected = { deliver(token) {
                // Returning from Navigation creates a new foreground session: inspect its actual route without issuing or replacing any destination.
                connected = true; progress = RouteProgress(destination, existingRoute = true)
                navi.addListener(listener); report("반납 장소 길 안내를 시작할 수 있어요.")
                navi.getDestinationInfo(); navi.getRouteStateInfo()
            } }, onFailed = { deliver(token) { connected = false; report("내비게이션 서비스에 연결하지 못했어요.") } })
        } catch (error: Exception) { connected = false; report("Pleos 내비게이션을 사용할 수 없어요.") }
    }

    // Confirm a matching destination with search completion or the 3.0.4 service's actual named-route start plus ROUTE_GUIDING state; method return alone never suffices.
    private fun routeState() {
        if (progress?.confirmed == true) { routeTimeout?.cancel(); report("반납 장소 경로를 확인했어요. 내비 화면에서 안내를 확인해 주세요.", confirmed = true) }
    }

    // Send the operator-issued destination only after the app's explicit confirmation and fresh P/VIN checks.
    override fun navigate() {
        val place = checkNotNull(destination); val navi = checkNotNull(navigation); check(connected)
        val token = generation; progress = RouteProgress(place); report("반납 장소 경로를 확인하고 있어요.", busy = true)
        routeTimeout?.cancel(); routeTimeout = scope.launch {
            delay(25_000)
            if (token == generation && progress?.confirmed != true) { progress = null; report("길 안내 결과를 확인하지 못했어요. 내비 상태를 확인한 뒤 다시 시도해 주세요.") }
        }
        try {
            navi.requestRoute(RouteInfo(latitude = place.latitude, longitude = place.longitude, displayLatitude = null,
                displayLongitude = null, poiName = place.name, poiId = "", poiSubId = "0", address = place.address))
        } catch (error: Exception) { progress = null; routeTimeout?.cancel(); report("길 안내 요청을 보내지 못했어요.") }
    }

    // Ask Pleos Navigation to show its own screen; no external maps or unverified deep links replace a failed SDK request.
    override fun openNavigation() {
        try { check(connected); navigation?.requestNavigationScreen(NavigationScreenRequest.Foreground) }
        catch (error: Exception) { report("내비 화면을 열지 못했어요.") }
    }

    // Retire callbacks before releasing services; leave any existing Navigation route untouched when our foreground ends.
    override fun stop() {
        generation++; routeTimeout?.cancel(); routeTimeout = null; receiver = null; progress = null; destination = null; connected = false
        val fused = location; val positions = locationListener; location = null; locationListener = null
        if (fused != null) {
            if (positions != null) runCatching { fused.unregisterFusedLocationCallback(positions) }
            runCatching { fused.release() }
        }
        val navi = navigation; val events = navigationListener; navigation = null; navigationListener = null
        if (navi != null) { if (events != null) runCatching { navi.removeListener(events) }; runCatching { navi.release() } }
    }
}
