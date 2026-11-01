package kr.taeuk.pleos.rentalcheck.model

import org.junit.Assert.*
import org.junit.Test

class SnapshotComparisonTest {
    private fun field(key: String, value: String, unit: String? = null) =
        VehicleReading(key, key, ReadStatus.AVAILABLE, value, value, unit)
    private fun snapshot(time: Long, vararg fields: VehicleReading) = VehicleSnapshot(time, time,
        listOf(field("vehicle.vin", "KMHTEST00TEST0001")) + fields.toList())

    @Test fun `distance uses cumulative km and percent change uses percentage points`() {
        val a = snapshot(100, field("odometer.km", "10000", "km"), field("fuel.percent", "80", "%"))
        val b = snapshot(200, field("odometer.km", "10186.5", "km"), field("fuel.percent", "58", "%"))
        val report = SnapshotComparison.compare(a, b)
        assertEquals(186.5, report.distanceKm!!, 0.001)
        assertEquals(-22.0, report.fuelPercentagePointChange!!, 0.001)
    }

    @Test fun `missing fuel never becomes zero or a consumption claim`() {
        val failed = VehicleReading.missing("fuel.percent", "연료", ReadStatus.TIMED_OUT, "timeout")
        val report = SnapshotComparison.compare(snapshot(100, field("fuel.percent", "80", "%")), snapshot(200, failed))
        assertNull(report.fuelPercentagePointChange)
        assertEquals(ChangeKind.NOT_COMPARABLE, report.changes.first { it.key == "fuel.percent" }.kind)
    }

    @Test fun `decreasing odometer is rejected instead of reporting negative travel`() {
        val report = SnapshotComparison.compare(snapshot(100, field("odometer.km", "2000", "km")),
            snapshot(200, field("odometer.km", "1000", "km")))
        assertNull(report.distanceKm)
        assertNotNull(report.distanceProblem)
    }

    @Test fun `different distance units cannot silently become kilometers`() {
        val report = SnapshotComparison.compare(snapshot(100, field("odometer.km", "100", "km")),
            snapshot(200, field("odometer.km", "120", "mile")))
        assertNull(report.distanceKm)
        assertEquals(ChangeKind.NOT_COMPARABLE, report.changes.first { it.key == "odometer.km" }.kind)
    }

    @Test fun `a newly readable property is not classified as changed`() {
        val report = SnapshotComparison.compare(snapshot(100), snapshot(200, field("window.a.position", "0")))
        assertEquals(ChangeKind.NOT_COMPARABLE, report.changes.first { it.key == "window.a.position" }.kind)
    }

    @Test fun `new warnings are a set difference and require readable baseline`() {
        val a = snapshot(100, field("safety.activeWarnings", "LOW_FUEL"))
        val b = snapshot(200, field("safety.activeWarnings", "LOW_FUEL|TPMS"))
        assertEquals(setOf("TPMS"), SnapshotComparison.compare(a, b).newWarningCodes)
        assertNull(SnapshotComparison.compare(snapshot(100), b).newWarningCodes)
    }

    @Test fun `readable empty warning list differs from an unavailable warning list`() {
        val a = snapshot(100, field("safety.activeWarnings", ""))
        val b = snapshot(200, field("safety.activeWarnings", "TPMS"))
        assertEquals(setOf("TPMS"), SnapshotComparison.compare(a, b).newWarningCodes)
    }

    @Test fun `a snapshot before pickup does not produce rental statistics`() {
        val a = snapshot(200, field("odometer.km", "100", "km"), field("fuel.percent", "90", "%"))
        val b = snapshot(100, field("odometer.km", "120", "km"), field("fuel.percent", "80", "%"))
        val report = SnapshotComparison.compare(a, b)
        assertNull(report.distanceKm)
        assertNull(report.fuelPercentagePointChange)
    }

    @Test fun `different VINs never produce distance energy or warning comparisons`() {
        val before = snapshot(100, field("odometer.km", "100", "km"), field("fuel.percent", "80", "%"))
        val after = VehicleSnapshot(200, 200, listOf(field("vehicle.vin", "KMHTEST00TEST0002"),
            field("odometer.km", "150", "km"), field("fuel.percent", "70", "%")))
        val report = SnapshotComparison.compare(before, after)
        assertNull(report.distanceKm)
        assertNull(report.fuelPercentagePointChange)
        assertNull(report.newWarningCodes)
        assertTrue(report.distanceProblem!!.contains("다릅니다"))
        assertTrue(report.changes.all { it.kind == ChangeKind.NOT_COMPARABLE })
    }

    @Test fun `missing VIN cannot be replaced with a generated app identifier`() {
        val report = SnapshotComparison.compare(snapshot(100, field("odometer.km", "100", "km")),
            VehicleSnapshot(200, 200, listOf(field("odometer.km", "150", "km"))))
        assertNull(report.distanceKm)
        assertTrue(report.distanceProblem!!.contains("VIN"))
    }
}
