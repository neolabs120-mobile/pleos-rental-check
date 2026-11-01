package kr.taeuk.pleos.rentalcheck.cloud

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PairingStatusTest {
    @Test fun waitingAndExpiredCannotBecomeAnAccount() {
        listOf("waiting", "expired", "disconnected").forEach { value ->
            assertNull(RentalServer.parsePairingStatus(JSONObject().put("status", value)).booking)
        }
    }

    @Test fun connectedRequiresAllIdentityFieldsAndAValidVin() {
        val json = JSONObject().put("status", "connected").put("bookingId", "booking")
            .put("bookingNumber", "RC-TEST").put("nickname", "이용자").put("vin", "KMHTEST00TEST0001")
        assertEquals("KMHTEST00TEST0001", RentalServer.parsePairingStatus(json).booking!!.vin)
        json.put("vin", "unknown")
        assertThrows(IllegalArgumentException::class.java) { RentalServer.parsePairingStatus(json) }
    }

    @Test fun unknownStatusIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { RentalServer.parsePairingStatus(JSONObject().put("status", "ok")) }
    }
}
