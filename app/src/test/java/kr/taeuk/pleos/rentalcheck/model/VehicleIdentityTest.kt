package kr.taeuk.pleos.rentalcheck.model

import org.junit.Assert.*
import org.junit.Test

class VehicleIdentityTest {
    @Test fun normalizationRequiresASeventeenCharacterVinWithoutForbiddenLetters() {
        assertEquals("KMHTEST00TEST0001", VehicleIdentity.normalize(" kmhtest00test0001 "))
        assertNull(VehicleIdentity.normalize("KMHTEST00TEST00001"))
        assertNull(VehicleIdentity.normalize("KMHTEST00TEST000I"))
        assertNull(VehicleIdentity.normalize(null))
    }

    @Test fun failedReadingWithCachedValueCannotVerifyVehicleIdentity() {
        val snapshot = VehicleSnapshot(0, 1, listOf(VehicleReading("vehicle.vin", "VIN", ReadStatus.ERROR,
            value = "KMHTEST00TEST0001")))
        assertNull(VehicleIdentity.vin(snapshot))
    }
}
