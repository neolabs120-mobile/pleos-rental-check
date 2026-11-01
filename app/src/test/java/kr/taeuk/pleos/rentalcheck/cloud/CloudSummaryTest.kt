package kr.taeuk.pleos.rentalcheck.cloud

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CloudSummaryTest {
    private val photos = listOf("windows", "belongings", "exterior").map { EvidencePhoto(it, "return", it) }
    private fun summary() = CloudSummary("rental", 2, photos, EvidenceChecks(true, true, true))

    @Test fun allThreePhotosChecksAndCurrentRevisionAreRequired() {
        assertTrue(summary().readyToSubmit(2))
        assertFalse(summary().readyToSubmit(3))
        assertFalse(summary().copy(photos = photos.dropLast(1)).readyToSubmit(2))
        assertFalse(summary().copy(checks = EvidenceChecks(true, false, true)).readyToSubmit(2))
    }

    @Test fun pickupPhotosCannotSatisfyReturnRequirements() {
        assertFalse(summary().copy(photos = photos.map { it.copy(phase = "pickup") }).readyToSubmit(2))
    }

    @Test fun serverResponseUsesReturnChecklistAndKeepsReceipt() {
        val json = JSONObject("""{"id":"rental","revision":2,"photos":[{"id":"p1","phase":"return","category":"windows"}],"checks":{"pickup":{"windows":true},"return":{"windows":false,"belongings":true,"exterior":true,"note":"확인"}},"submittedAtMillis":123,"receiptId":"receipt"}""")
        val result = RentalServer.parseSummary(json, "rental")
        assertFalse(result.checks.windows)
        assertEquals(1, result.count("windows"))
        assertEquals(123L, result.submittedAtMillis)
        assertEquals("receipt", result.receiptId)
    }

    @Test(expected = IllegalArgumentException::class)
    fun wrongRentalResponseIsRejected() {
        RentalServer.parseSummary(JSONObject("""{"id":"other"}"""), "rental")
    }
}
