package kr.taeuk.pleos.rentalcheck.vehicle

const val EMULATOR_TEST_SOURCE = "EMULATOR_TEST_VIN · Pleos Vehicle SDK 2.3.2"

interface EmulatorTestMode {
    val available: Boolean
    val enabled: Boolean
    // Mode changes are explicit and must be rejected by environments that cannot safely substitute a test identity.
    fun setEnabled(enabled: Boolean)
}

data class VehicleEnvironment(val source: VehicleDataSource, val testMode: EmulatorTestMode)

object EmulatorEligibility {
    // Require the known Pleos virtual hardware and image together; a Debug build alone must never enable a physical vehicle.
    fun allows(debug: Boolean, hardware: String, device: String, product: String, fingerprint: String): Boolean =
        debug && hardware == "ranchu" && device == "crp_avd_arm64" && product == device &&
            fingerprint.startsWith("Pleos/crp_avd_arm64/crp_avd_arm64:")
}
