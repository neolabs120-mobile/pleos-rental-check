package kr.taeuk.pleos.rentalcheck.ui

import kr.taeuk.pleos.rentalcheck.model.*
import org.junit.Assert.*
import org.junit.Test

class InspectionPresentationTest {
    private fun snapshot(vararg readings: VehicleReading) = VehicleSnapshot(100, 200, readings.toList())
    private fun reading(key: String, value: String) = VehicleReading(key, key, ReadStatus.AVAILABLE, value)

    @Test fun missingDoorsNeverBecomeClosed() {
        assertEquals("문 상태 확인 불가", doorSummary(null))
        assertEquals("확인 필요 1", doorSummary(snapshot(VehicleReading.missing("door", "문", ReadStatus.ERROR, ""))))
    }

    @Test fun lockValuesAndNonBinaryPositionsAreNotClosedDoors() {
        val result = doorSummary(snapshot(reading("door.ROW_1_LEFT.position", "0"),
            reading("door.ROW_1_RIGHT.position", "1"), reading("door.REAR.position", "5"),
            reading("door.ROW_1_LEFT.lock", "false")))
        assertEquals("닫힘 1 · 열림 1 · 확인 필요 1", result)
    }

    @Test fun absentWarningsDifferFromObservedEmptyWarningSet() {
        assertNull(warningCodes(snapshot()))
        assertEquals(emptySet<String>(), warningCodes(snapshot(reading("safety.activeWarnings", ""))))
    }

    @Test fun unsupportedFuelDoesNotHideAnEvBatteryOrInventFuelFailure() {
        val battery = reading("battery.percent", "65")
        assertEquals(listOf(battery), energyReadings(snapshot(battery,
            VehicleReading.missing("fuel.percent", "연료", ReadStatus.UNSUPPORTED, ""))))
    }

    @Test fun windowGroupRetainsRawPositionsAndDomainFailures() {
        val raw = reading("window.ROW_1_LEFT.position", "20")
        val missing = VehicleReading.missing("window", "창문", ReadStatus.TIMED_OUT, "")
        assertEquals(listOf(raw, missing), DetailGroup.WINDOWS.readings(snapshot(raw, missing,
            reading("door.ROW_1_LEFT.position", "0"))))
        assertFalse(valueText(raw).contains("닫힘"))
    }
}
