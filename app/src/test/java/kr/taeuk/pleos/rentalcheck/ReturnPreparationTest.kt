package kr.taeuk.pleos.rentalcheck

import kr.taeuk.pleos.rentalcheck.model.*
import kr.taeuk.pleos.rentalcheck.navigation.*
import kr.taeuk.pleos.rentalcheck.data.UsageMemoryCodec
import org.junit.Assert.*
import org.junit.Test

class ReturnPreparationTest {
    // Model independently timestamped successful/unknown SDK readings, keeping the pickup identity fixed across the battery comparisons.
    private fun snapshot(value: String?, status: ReadStatus = ReadStatus.AVAILABLE, vin: String = "KMHTEST00TEST0001") = VehicleSnapshot(100, 101,
        listOf(VehicleReading("vehicle.vin", "VIN", ReadStatus.AVAILABLE, vin, observedAtMillis = 100),
            VehicleReading("battery.percent", "배터리", status, value, observedAtMillis = 100)))

    @Test fun batteryUsesPickupAndNeverRebasesOnReturn() {
        // A lower latest charge creates a deficit against the originally saved percentage; surplus has no negative charge requirement.
        assertEquals(7.5, BatteryReturn.from(snapshot("80"), snapshot("72.5")).shortfall!!, 0.001)
        assertEquals(80.0, BatteryReturn.from(snapshot("80"), snapshot("90")).target!!, 0.001)
        assertEquals(0.0, BatteryReturn.from(snapshot("80"), snapshot("90")).shortfall!!, 0.001)
    }

    @Test fun unknownInvalidOrOtherVehicleCannotBecomeChargeEstimate() {
        // Missing, unsupported, non-finite, out-of-range and mismatched identities must not suggest a valid current percentage.
        for (value in listOf(null, "NaN", "Infinity", "-1", "101")) assertNull(BatteryReturn.from(snapshot("80"), snapshot(value)).current)
        assertNull(BatteryReturn.from(snapshot(null), snapshot("80")).target)
        assertNull(BatteryReturn.from(snapshot("80"), snapshot("20", ReadStatus.UNSUPPORTED)).current)
        assertNull(BatteryReturn.from(snapshot("80"), snapshot("20", vin = "KMHTEST00TEST0002")).current)
        assertEquals(0.0, BatteryReturn.from(snapshot("0"), snapshot("0")).target!!, 0.0)
    }

    @Test fun editedOrDeletedMemoRetiresItsRecoveryConfirmation() {
        // A prior acknowledgement cannot be reused for a different object placed later in the same compartment.
        val memo = LuggageMemo(StorageSpace.TRUNK, "캐리어")
        val collected = UsageMemory().remember(memo).collect(StorageSpace.TRUNK, true)
        assertEquals(setOf(StorageSpace.TRUNK), collected.collectedSpaces)
        assertTrue(collected.luggageReviewRequired)
        assertTrue(collected.remember(memo.copy(item = "가방")).collectedSpaces.isEmpty())
        assertTrue(collected.forget(StorageSpace.TRUNK).collectedSpaces.isEmpty())
        assertEquals(collected, collected.collect(StorageSpace.FRONT_LEFT, true))
    }

    @Test fun acknowledgementSurvivesCodecWithoutChangingObservations() {
        // Persist explicit item recovery separately from door history and manual marks, including a safe default for pre-feature records.
        val memory = UsageMemory(openedSpaces = setOf(StorageSpace.REAR_RIGHT), markedSpaces = setOf(StorageSpace.TRUNK))
            .remember(LuggageMemo(StorageSpace.TRUNK, "캐리어")).collect(StorageSpace.TRUNK, true)
        assertEquals(memory, UsageMemoryCodec.decode(UsageMemoryCodec.encode(memory)))
        assertFalse(UsageMemoryCodec.decode(null).luggageReviewRequired)
        assertEquals(setOf(StorageSpace.REAR_RIGHT), memory.openedSpaces)
    }

    @Test fun chargingRouteNeedsBothDestinationAndWaypointEvidence() {
        // A successful direct route or unrelated waypoint cannot certify the explicitly requested charging detour.
        val destination = ReturnLocation("반납", "주소", 37.485, 127.03)
        val stop = ChargingStop("충전", "충전 주소", 37.4, 127.0)
        val pending = RouteProgress(destination, completed = true, matched = true, via = stop)
        assertFalse(pending.confirmed)
        assertTrue(pending.copy(waypointMatched = true).confirmed)
        assertFalse(pending.copy(waypointMatched = true, matched = false).confirmed)
        assertTrue(pending.copy(waypointReceived = true).waypointUnverified)
        assertFalse(pending.copy(waypointReceived = true).confirmed)
        assertFalse(pending.copy(waypointReceived = true, matched = false).waypointUnverified)
    }
}
