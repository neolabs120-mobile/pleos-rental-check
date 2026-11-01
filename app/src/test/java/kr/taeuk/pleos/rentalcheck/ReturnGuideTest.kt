package kr.taeuk.pleos.rentalcheck

import kr.taeuk.pleos.rentalcheck.navigation.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ReturnGuideTest {
    private val place = ReturnLocation("시험 장소", "시험 주소", 37.48544722, 127.03636666, isTest = true)

    // Reject stale, future, invalid and absent SDK fixes instead of converting them into a zero-distance arrival.
    @Test fun estimatesUseOnlyFreshValidSdkPositions() {
        val now = 1_800_000_000_000L
        val fix = LocationFix(place.latitude, place.longitude, now)
        val state = ReturnGuideState(place, fix, nowMillis = now)
        assertEquals(0.0, state.distanceMeters!!, .01)
        assertNull(state.copy(nowMillis = now + 60_001).distanceMeters)
        assertNull(state.copy(fix = fix.copy(timestampMillis = now + 5_001)).distanceMeters)
        assertNull(state.copy(fix = fix.copy(timestampMillis = 0)).distanceMeters)
        assertNull(state.copy(fix = fix.copy(latitude = Double.NaN)).distanceMeters)
        assertNull(state.copy(fix = null).distanceMeters)
        assertNull(state.copy(destination = null).distanceMeters)
    }

    // A completion broadcast, cached guidance mode or different destination alone must not claim that this booking's route was installed.
    @Test fun searchCompletionAndDestinationMatchAreBothRequired() {
        val pending = RouteProgress(place)
        assertFalse(pending.copy(completed = true).confirmed)
        assertFalse(pending.destination(place.latitude, place.longitude).confirmed)
        assertFalse(pending.copy(completed = true).destination(37.6, 127.1).confirmed)
        assertFalse(pending.copy(completed = true).destination(Double.NaN, 127.0).confirmed)
        assertTrue(pending.copy(completed = true).destination(place.latitude, place.longitude).confirmed)
        assertTrue(pending.destination(place.latitude, place.longitude).copy(completed = true).confirmed)
        assertFalse(pending.copy(guidanceActive = true, matched = true).confirmed)
        assertFalse(pending.copy(started = true, guidanceActive = true).confirmed)
        assertTrue(pending.copy(started = true, guidanceActive = true).destination(place.latitude, place.longitude).confirmed)
        assertFalse(RouteProgress(place.copy(latitude = 0.0, longitude = 0.0), completed = true).destination(0.0, 0.0, present = false).confirmed)
        assertTrue(pending.copy(existingRoute = true, guidanceActive = true).destination(place.latitude, place.longitude).confirmed)
        assertFalse(pending.copy(existingRoute = true).destination(place.latitude, place.longitude).confirmed)
    }

    // Sanity-check distance units with a known one-degree equatorial arc rather than merely reproducing the distance formula.
    @Test fun distanceIsMetersAndCoordinatesAreStrict() {
        assertEquals(111_195.0, straightDistance(0.0, 0.0, 0.0, 1.0), 1.0)
        assertFalse(validCoordinates(91.0, 0.0)); assertFalse(validCoordinates(0.0, Double.POSITIVE_INFINITY))
        assertThrows(IllegalArgumentException::class.java) { place.copy(name = " ") }
        assertThrows(IllegalArgumentException::class.java) { place.copy(longitude = 181.0) }
    }

    // Changing bookings or stopping the foreground must retire old callbacks and leave no previous customer's coordinates visible.
    @Test fun sessionsRejectLateEventsAndReleaseForegroundSubscriptions() {
        val engine = FakeEngine(); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val controller = ReturnGuideController(engine, scope, {}, { 1_800_000_000_000L })
        try {
            controller.attach("booking-A", place); val old = engine.receive!!
            old(GuideEvent.Position(LocationFix(37.0, 127.0, 1_800_000_000_000L)))
            assertNotNull(controller.state.fix)
            controller.attach("booking-B", place)
            assertNull(controller.state.fix)
            old(GuideEvent.Position(LocationFix(38.0, 128.0, 1_800_000_000_000L)))
            assertNull(controller.state.fix)
            val current = engine.receive!!
            current(GuideEvent.Navigation(NavigationState(connected = true)))
            controller.navigate(); assertEquals(1, engine.requests)
            controller.stop(); current(GuideEvent.Position(LocationFix(38.0, 128.0, 1_800_000_000_000L)))
            assertNull(controller.state.fix); assertNull(controller.state.destination)
            assertThrows(IllegalStateException::class.java) { controller.navigate() }
            assertTrue(engine.stops >= 3)
        } finally { controller.stop(); scope.cancel() }
    }

    private class FakeEngine : ReturnGuideEngine {
        var receive: ((GuideEvent) -> Unit)? = null
        var requests = 0; var stops = 0
        // Expose callbacks for deliberate ordering tests without using an Android service or inventing a successful SDK response.
        override fun start(destination: ReturnLocation, receive: (GuideEvent) -> Unit) { this.receive = receive }
        // Count explicit requests so account/session guards can be checked independently from Navigation results.
        override fun navigate() { requests++ }
        // The lifecycle test does not launch another application.
        override fun openNavigation() = Unit
        // Track actual release requests while retaining saved callbacks to test late-event rejection.
        override fun stop() { stops++ }
    }
}
