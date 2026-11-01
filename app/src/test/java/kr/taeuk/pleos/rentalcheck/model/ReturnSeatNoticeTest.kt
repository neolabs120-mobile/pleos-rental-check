package kr.taeuk.pleos.rentalcheck.model

import org.junit.Assert.*
import org.junit.Test

class ReturnSeatNoticeTest {
    // Name every confirmed occupied location, including a seated driver, without interpreting unoccupied or unknown seats as belongings clearance.
    @Test fun namesOnlyConfirmedOccupiedSeats() {
        val values = SeatOccupancy.fromRaw("seat.ROW_1_LEFT.occupancy", "앞쪽 왼쪽 좌석 점유", "2", 10) +
            SeatOccupancy.fromRaw("seat.ROW_1_RIGHT.occupancy", "앞쪽 오른쪽 좌석 점유", "1", 11) +
            SeatOccupancy.fromRaw("seat.ROW_2_RIGHT.occupancy", "뒤쪽 오른쪽 좌석 점유", "2", 12) +
            SeatOccupancy.fromRaw("seat.ROW_2_CENTER.occupancy", "뒤쪽 가운데 좌석 점유", "0", 13)
        assertEquals(listOf("앞쪽 왼쪽", "뒤쪽 오른쪽"), SeatOccupancy.occupiedLabels(VehicleSnapshot(1, 20, values)))
    }

    // Old 0.2.2 true means the original SDK value, so a saved legacy Boolean without the raw enum must never trigger this reminder.
    @Test fun legacyAndSdkBooleansCannotTriggerReminder() {
        val legacy = VehicleReading("seat.ROW_1_RIGHT.occupancy", "동승석", ReadStatus.AVAILABLE, "true", "수신값 true · 판정 보류", observedAtMillis = 10)
        val sdk = SeatOccupancy.reading("seat.ROW_1_LEFT.sdkOccupancy", "운전석", true, 10)
        assertTrue(SeatOccupancy.occupiedLabels(VehicleSnapshot(1, 20, listOf(legacy, sdk))).isEmpty())
    }

    // Corrupted, unavailable or differently timed evidence cannot be combined into a new occupied seat conclusion.
    @Test fun rejectsContradictoryFailedOrUnpairedEvidence() {
        val pair = SeatOccupancy.fromRaw("seat.ROW_1_RIGHT.occupancy", "동승석", "2", 10)
        val raw = pair.first(); val normalized = pair.last()
        for (values in listOf(listOf(normalized), listOf(raw.copy(value = "1"), normalized),
            listOf(raw.copy(status = ReadStatus.UNAVAILABLE), normalized), listOf(raw, normalized.copy(status = ReadStatus.ERROR)),
            listOf(raw.copy(observedAtMillis = 9), normalized), listOf(raw.copy(observedAtMillis = 0), normalized.copy(observedAtMillis = 0)))) {
            assertTrue(SeatOccupancy.occupiedLabels(VehicleSnapshot(1, 20, values)).isEmpty())
        }
    }

    // A new vacant/unknown snapshot clears the current reminder instead of carrying an occupied result forward from an earlier return check.
    @Test fun recomputesFromEachSnapshotWithoutKeepingEarlierOccupancy() {
        for (raw in listOf("1", "0", null, "invalid")) {
            val snapshot = VehicleSnapshot(1, 20, SeatOccupancy.fromRaw("seat.ROW_1_RIGHT.occupancy", "동승석", raw, 10))
            assertTrue(SeatOccupancy.occupiedLabels(snapshot).isEmpty())
        }
    }
}
