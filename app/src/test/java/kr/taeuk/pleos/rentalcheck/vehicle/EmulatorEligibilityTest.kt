package kr.taeuk.pleos.rentalcheck.vehicle

import org.junit.Assert.*
import org.junit.Test

class EmulatorEligibilityTest {
    // The exact known Pleos image can opt in only during Debug development.
    @Test fun knownDebugEmulatorCanOptIn() {
        assertTrue(EmulatorEligibility.allows(true, "ranchu", "crp_avd_arm64", "crp_avd_arm64", "Pleos/crp_avd_arm64/crp_avd_arm64:14/release"))
    }

    // Release must reject the same virtual hardware even if a saved Debug preference exists.
    @Test fun releaseCannotOptIn() {
        assertFalse(EmulatorEligibility.allows(false, "ranchu", "crp_avd_arm64", "crp_avd_arm64", "Pleos/crp_avd_arm64/crp_avd_arm64:14/release"))
    }

    // Physical-car hardware, an ordinary AVD and mismatched image identifiers must all keep real VIN validation.
    @Test fun physicalAndUnrecognizedDevicesAreRejected() {
        for ((hardware, device, product, fingerprint) in listOf(
            listOf("exynos", "CONNECT_S", "CONNECT_S", "Pleos/CONNECT_S/CONNECT_S:14/release"),
            listOf("exynos", "crp_avd_arm64", "crp_avd_arm64", "Pleos/crp_avd_arm64/crp_avd_arm64:14/release"),
            listOf("ranchu", "generic", "generic", "google/generic/generic:35/release"),
            listOf("ranchu", "crp_avd_arm64", "CONNECT_S", "Pleos/crp_avd_arm64/crp_avd_arm64:14/release"),
            listOf("ranchu", "crp_avd_arm64", "crp_avd_arm64", "other/device/device:14/release"))) {
            assertFalse(EmulatorEligibility.allows(true, hardware, device, product, fingerprint))
        }
    }
}
