package kr.taeuk.pleos.rentalcheck.vehicle

import kr.taeuk.pleos.rentalcheck.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class EmulatorTestVehicleDataSourceTest {
    private val vin = VehicleReading.missing("vehicle.vin", "VIN", ReadStatus.UNAVAILABLE, "invalid")
    private val window = VehicleReading("window.ROW_2_RIGHT.position", "뒤 오른쪽 창문", ReadStatus.AVAILABLE, "3")
    private val closed = VehicleReading("window.ROW_2_RIGHT.closed", "뒤 오른쪽 창문 닫힘", ReadStatus.AVAILABLE, "false")
    private val snapshot = VehicleSnapshot(10, 20, listOf(vin, window, closed))
    private val real = object : VehicleDataSource {
        // The fixture has no service lifecycle; production lifecycle delegation remains unchanged.
        override fun initialize() = Unit
        // Return one immutable SDK fixture so identity replacement cannot accidentally alter another observation.
        override suspend fun readSnapshot() = snapshot
        // The fixture owns no resources to release.
        override fun close() = Unit
    }

    // Default-off mode must preserve the exact SDK snapshot, including its missing VIN.
    @Test fun disabledLeavesMissingVinUntouched() = runBlocking {
        assertSame(snapshot, EmulatorTestVehicleDataSource(real) { false }.readSnapshot())
    }

    // Enabled mode labels only the synthetic identity while retaining the failed original VIN and unchanged window objects.
    @Test fun enabledLabelsOnlyVinAndRetainsSdkReadings() = runBlocking {
        val result = EmulatorTestVehicleDataSource(real) { true }.readSnapshot()
        assertEquals(EMULATOR_TEST_SOURCE, result.source)
        assertEquals(EMULATOR_TEST_VIN, VehicleIdentity.vin(result))
        assertEquals(17, EMULATOR_TEST_VIN.length)
        assertEquals(ReadStatus.UNAVAILABLE, result.find("vehicle.sdkVin")!!.status)
        assertSame(window, result.find(window.key))
        assertSame(closed, result.find(closed.key))
        assertFalse(ReturnWindowCheck.from(result).canSave(true))
    }
}
