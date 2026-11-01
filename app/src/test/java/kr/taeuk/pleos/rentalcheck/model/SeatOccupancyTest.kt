package kr.taeuk.pleos.rentalcheck.model

import org.junit.Assert.*
import org.junit.Test

class SeatOccupancyTest {
    // Verify that the raw Boolean remains a limited occupancy signal and a null response never becomes an empty seat.
    @Test fun preservesOccupiedEmptyAndUnknown() {
        val key = "seat.ROW_1_RIGHT.occupancy"
        assertEquals("true", SeatOccupancy.reading(key, "동승석", true, 10).value)
        assertEquals("SDK 원본 false", SeatOccupancy.reading(key, "동승석", false, 10).displayValue)
        val missing = SeatOccupancy.reading(key, "동승석", null, 10)
        assertFalse(missing.available); assertNull(missing.value); assertEquals(ReadStatus.UNAVAILABLE, missing.status)
    }

    // The original enum is authoritative: VACANT(1) is false and OCCUPIED(2) is true, independently of the broken SDK Boolean.
    @Test fun standardEnumsHaveCorrectOccupancyMeaning() {
        val vacant = SeatOccupancy.fromRaw("seat.ROW_1_RIGHT.occupancy", "동승석", "1", 10)
        val occupied = SeatOccupancy.fromRaw("seat.ROW_1_RIGHT.occupancy", "동승석", "2", 20)
        assertEquals("1", vacant.first().value); assertEquals("false", vacant.last().value)
        assertEquals("2", occupied.first().value); assertEquals("true", occupied.last().value)
        assertEquals("비점유", vacant.last().displayValue); assertEquals("점유", occupied.last().displayValue)
    }

    // UNKNOWN, null and corrupt payloads must remain unknown rather than becoming occupied through Boolean negation.
    @Test fun unknownAndMalformedEnumsNeverBecomeOccupiedOrVacant() {
        for (raw in listOf(null, "0", "-1", "3", "", "true", "false", "invalid", "2147483648")) {
            val result = SeatOccupancy.fromRaw("seat.ROW_2_LEFT.occupancy", "뒷좌석", raw, 10)
            assertFalse(result.last().available); assertNull(result.last().value)
            assertEquals(ReadStatus.UNAVAILABLE, result.last().status)
            assertEquals(raw, result.first().value)
        }
    }

    // Keep SDK and raw evidence outside the normalized panel so historical Boolean records cannot be silently reinterpreted.
    @Test fun evidenceAndLegacyValuesRemainSeparate() {
        val sdk = SeatOccupancy.reading("seat.ROW_1_RIGHT.sdkOccupancy", "SDK", true, 10)
        val values = SeatOccupancy.fromRaw("seat.ROW_1_RIGHT.occupancy", "동승석", "1", 10)
        assertEquals(listOf(values.last()), SeatOccupancy.readings(VehicleSnapshot(1, 10, values + sdk)))
        val legacy = VehicleReading("seat.ROW_1_RIGHT.occupancy", "동승석", ReadStatus.AVAILABLE, "true", "수신값 true · 판정 보류")
        assertEquals(listOf(legacy), SeatOccupancy.readings(VehicleSnapshot(1, 10, listOf(legacy))))
    }

    // A failed area must replace its last healthy value without corrupting another seat's independently received signal.
    @Test fun individualFailureInvalidatesOnlyThatSeat() {
        val first = SeatOccupancy.reading("seat.ROW_1_LEFT.occupancy", "운전석", false, 10)
        val second = SeatOccupancy.reading("seat.ROW_1_RIGHT.occupancy", "동승석", true, 10)
        val failed = VehicleReading.missing(first.key, first.label, ReadStatus.PERMISSION_DENIED, "permission")
        val updated = SeatOccupancy.update(listOf(first, second), failed)
        assertEquals(failed, updated.first { it.key == first.key }); assertTrue(updated.contains(second))
    }

    // Capability failure must remove stale per-seat values, and a later supported reading must replace the aggregate failure.
    @Test fun domainFailureAndRecoveryReplaceStaleState() {
        val first = SeatOccupancy.reading("seat.ROW_1_RIGHT.occupancy", "동승석", false, 10)
        val failed = VehicleReading.missing("seat.all.occupancy", "좌석 점유", ReadStatus.UNSUPPORTED, "unsupported")
        assertEquals(listOf(failed), SeatOccupancy.update(listOf(first), failed))
        assertEquals(listOf(first), SeatOccupancy.update(listOf(failed), first))
    }

    // An older asynchronous read cannot overwrite a more recently received occupied signal.
    @Test fun olderReadingCannotReplaceNewerSignal() {
        val recent = SeatOccupancy.reading("seat.ROW_2_RIGHT.occupancy", "뒷좌석", true, 20)
        assertEquals(listOf(recent), SeatOccupancy.update(listOf(recent), recent.copy(value = "false", observedAtMillis = 10)))
    }

    // Restrict the occupancy panel to occupancy properties rather than turning doors, manual luggage marks or seat controls into occupancy.
    @Test fun unrelatedPropertiesCannotCreateOccupancy() {
        val door = VehicleReading("door.REAR.position", "트렁크", ReadStatus.AVAILABLE, "1")
        assertTrue(SeatOccupancy.update(emptyList(), door).isEmpty())
        assertTrue(SeatOccupancy.readings(VehicleSnapshot(1, 2, listOf(door))).isEmpty())
        assertFalse(SeatOccupancy.isSeat("seat.ROW_1_LEFT.position"))
    }
}
