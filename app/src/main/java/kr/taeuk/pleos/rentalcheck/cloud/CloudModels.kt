package kr.taeuk.pleos.rentalcheck.cloud

data class ServerConfig(val baseUrl: String = "", val deviceToken: String = "") {
    val configured: Boolean get() = baseUrl.isNotBlank() && deviceToken.isNotBlank()
}

data class PhotoLink(val uploadUrl: String, val expiresAtMillis: Long, val phase: String)
data class VehiclePairing(val id: String, val pairingUrl: String, val expiresAtMillis: Long)
data class PairedBooking(val id: String, val number: String, val nickname: String, val vin: String)
data class PairingStatus(val status: String, val booking: PairedBooking? = null)
data class EvidencePhoto(val id: String, val phase: String, val category: String)
data class EvidenceChecks(val windows: Boolean = false, val belongings: Boolean = false, val exterior: Boolean = false,
    val note: String = "")

data class CloudSummary(
    val id: String,
    val revision: Int,
    val photos: List<EvidencePhoto> = emptyList(),
    val checks: EvidenceChecks = EvidenceChecks(),
    val submittedAtMillis: Long? = null,
    val receiptId: String? = null,
) {
    fun count(category: String, phase: String = "return") = photos.count { it.phase == phase && it.category == category }
    fun readyToSubmit(revision: Int) = this.revision == revision &&
        checks.windows && checks.belongings && checks.exterior &&
        listOf("windows", "belongings", "exterior").all { count(it) > 0 }
}
