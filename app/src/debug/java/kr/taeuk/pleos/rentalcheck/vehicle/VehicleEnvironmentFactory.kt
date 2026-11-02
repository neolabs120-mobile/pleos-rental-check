package kr.taeuk.pleos.rentalcheck.vehicle

import android.content.Context
import android.os.Build
import kr.taeuk.pleos.rentalcheck.BuildConfig
import kr.taeuk.pleos.rentalcheck.model.ReadStatus
import kr.taeuk.pleos.rentalcheck.model.VehicleReading
import kr.taeuk.pleos.rentalcheck.model.VehicleSnapshot

const val EMULATOR_TEST_VIN = "TEST0000000000001"

// Keep all VIN substitution code in the Debug source set; Release compiles a different factory without this implementation.
fun createVehicleEnvironment(context: Context): VehicleEnvironment {
    val preferences = context.getSharedPreferences("emulator_test_vin", Context.MODE_PRIVATE)
    val supported = EmulatorEligibility.allows(BuildConfig.DEBUG, Build.HARDWARE, Build.DEVICE, Build.PRODUCT, Build.FINGERPRINT)
    val mode = object : EmulatorTestMode {
        override val available = supported
        override val enabled: Boolean get() = available && preferences.getBoolean("enabled", false)
        override fun setEnabled(enabled: Boolean) {
            // Check eligibility on every change so stale preferences or test automation cannot enable an unsupported device.
            require(available) { "Pleos 전용 에뮬레이터의 Debug 빌드에서만 사용할 수 있어요." }
            check(preferences.edit().putBoolean("enabled", enabled).commit()) { "테스트 모드를 저장하지 못했어요." }
        }
    }
    return VehicleEnvironment(EmulatorTestVehicleDataSource(PleosVehicleDataSource(context)) { mode.enabled }, mode)
}

internal class EmulatorTestVehicleDataSource(private val real: VehicleDataSource, private val enabled: () -> Boolean) : VehicleDataSource by real, InteractionObserver {
    // Delegate real SDK callbacks unchanged; the emulator switch replaces only snapshot VIN identity.
    override suspend fun observeInteractions(onEvent: (VehicleInteraction) -> Unit): java.io.Closeable =
        (real as? InteractionObserver)?.observeInteractions(onEvent) ?: java.io.Closeable {}

    override suspend fun readSnapshot(): VehicleSnapshot {
        // Replace only the opted-in emulator identity, retaining the original SDK VIN status and every other real SDK reading.
        return withTestIdentity(real.readSnapshot())
    }

    // Preserve the exact same explicit Debug-only VIN policy for small stationary probes; gear always comes from the real SDK.
    override suspend fun readStationarySnapshot(): VehicleSnapshot = withTestIdentity(real.readStationarySnapshot())

    // Keep one identity substitution implementation so normal snapshots and stationary interactions cannot disagree about emulator eligibility.
    private fun withTestIdentity(snapshot: VehicleSnapshot): VehicleSnapshot {
        if (!enabled()) return snapshot
        val sdkVin = snapshot.find("vehicle.vin")?.copy(key = "vehicle.sdkVin", label = "SDK 원본 VIN (테스트 대체 전)")
        val testVin = VehicleReading("vehicle.vin", "에뮬레이터 테스트 VIN", ReadStatus.AVAILABLE, EMULATOR_TEST_VIN,
            "테스트 VIN ···${EMULATOR_TEST_VIN.takeLast(6)}", detail = "실제 차량 VIN이 아닙니다. 사용자가 켠 에뮬레이터 전용 테스트 식별자입니다.",
            observedAtMillis = snapshot.completedAtMillis)
        return snapshot.copy(source = EMULATOR_TEST_SOURCE,
            readings = (snapshot.readings.filterNot { it.key == "vehicle.vin" } + listOfNotNull(sdkVin) + testVin).sortedBy { it.key })
    }
}
