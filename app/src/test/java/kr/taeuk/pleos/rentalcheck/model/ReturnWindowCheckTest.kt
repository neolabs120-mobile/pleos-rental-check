package kr.taeuk.pleos.rentalcheck.model

import org.junit.Assert.*
import org.junit.Test

class ReturnWindowCheckTest {
    private fun window(area: String, closed: Boolean): List<VehicleReading> = listOf(
        VehicleReading("window.$area.position", "$area 창문", ReadStatus.AVAILABLE, if (closed) "0" else "1"),
        VehicleReading("window.$area.closed", "$area 창문 닫힘 여부", ReadStatus.AVAILABLE, closed.toString()),
    )
    private fun snapshot(vararg readings: VehicleReading) = VehicleSnapshot(10, 20, readings.toList())

    @Test fun ordinaryRangeDetectsFullyClosedAndSmallOpening() {
        assertEquals(true, WindowPositionContract.closed(0, 0, 100))
        assertEquals(false, WindowPositionContract.closed(1, 0, 100))
        assertEquals(false, WindowPositionContract.closed(100, 0, 100))
    }

    @Test fun missingInvalidAndNonstandardPositionsStayUnknown() {
        assertNull(WindowPositionContract.closed(null, 0, 100))
        assertNull(WindowPositionContract.closed(-1, 0, 100))
        assertNull(WindowPositionContract.closed(101, 0, 100))
        assertNull(WindowPositionContract.closed(10, 10, 100))
        assertNull(WindowPositionContract.closed(-10, -10, 100))
        assertNull(WindowPositionContract.closed(0, 0, 0))
        assertNull(WindowPositionContract.closed(0, 100, 0))
    }

    @Test fun fourClosedWindowsPermitReturnWithoutInventingAManualObservation() {
        val readings = listOf("FL", "FR", "RL", "RR").flatMap { window(it, true) }
        val check = ReturnWindowCheck.from(snapshot(*readings.toTypedArray()))
        assertTrue(check.allClosed)
        assertEquals(4, check.closedCount)
        assertTrue(check.canSave(false))
    }

    @Test fun oneOpenWindowIsNamedAndCannotBeOverriddenByManualCheck() {
        val check = ReturnWindowCheck.from(snapshot(*(window("FL", true) + window("RR", false)).toTypedArray()))
        assertEquals(listOf("RR 창문"), check.openWindows)
        assertFalse(check.canSave(false))
        assertFalse(check.canSave(true))
    }

    @Test fun retryUsesNewSnapshotAndDetectsAWindowReopenedAfterEarlierCheck() {
        val closed = ReturnWindowCheck.from(snapshot(*window("RR", true).toTypedArray()))
        val reopened = ReturnWindowCheck.from(snapshot(*window("RR", false).toTypedArray()))
        val reclosed = ReturnWindowCheck.from(snapshot(*window("RR", true).toTypedArray()))
        assertTrue(closed.canSave(false))
        assertFalse(reopened.canSave(true))
        assertTrue(reclosed.canSave(false))
    }

    @Test fun partialReadFailureRequiresExplicitManualConfirmation() {
        val failed = VehicleReading.missing("window.RR.position", "RR 창문", ReadStatus.TIMED_OUT, "timeout")
        val check = ReturnWindowCheck.from(snapshot(*(window("FL", true) + failed).toTypedArray()))
        assertFalse(check.allClosed)
        assertEquals(listOf("RR 창문"), check.unknownWindows)
        assertFalse(check.canSave(false))
        assertTrue(check.canSave(true))
    }

    @Test fun unsupportedDomainAndNoSnapshotNeverCountAsClosed() {
        listOf(null, snapshot(), snapshot(VehicleReading.missing("window", "창문 위치", ReadStatus.UNSUPPORTED, "unsupported")))
            .forEach { value ->
                val check = ReturnWindowCheck.from(value)
                assertFalse(check.allClosed)
                assertTrue(check.needsAttention)
                assertFalse(check.canSave(false))
            }
    }

    @Test fun oldRawPositionOnlySnapshotsCannotSupplyAnAutomaticClosureResult() {
        val check = ReturnWindowCheck.from(snapshot(VehicleReading("window.FL.position", "FL 창문", ReadStatus.AVAILABLE, "0")))
        assertFalse(check.allClosed)
        assertEquals(listOf("FL 창문"), check.unknownWindows)
    }

    @Test fun malformedOrFailedClosureDoesNotBecomeClosed() {
        listOf(
            VehicleReading("window.FL.closed", "FL 창문", ReadStatus.AVAILABLE, "yes"),
            VehicleReading("window.FL.closed", "FL 창문", ReadStatus.ERROR, "true"),
        ).forEach { closure ->
            val check = ReturnWindowCheck.from(snapshot(window("FL", true).first(), closure))
            assertFalse(check.allClosed)
            assertFalse(check.canSave(false))
        }
    }

    @Test fun unknownAndOpenTogetherStillRequireClosingTheKnownOpenWindow() {
        val check = ReturnWindowCheck.from(snapshot(*(window("FL", false) +
            VehicleReading.missing("window.RR.position", "RR 창문", ReadStatus.PERMISSION_DENIED, "denied")).toTypedArray()))
        assertEquals(listOf("FL 창문"), check.openWindows)
        assertEquals(listOf("RR 창문"), check.unknownWindows)
        assertFalse(check.canSave(true))
    }
}
