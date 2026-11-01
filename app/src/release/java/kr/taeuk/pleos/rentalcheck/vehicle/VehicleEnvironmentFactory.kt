package kr.taeuk.pleos.rentalcheck.vehicle

import android.content.Context

// Release always uses the real SDK. No test VIN constant, substitute reader or preference activation is compiled into this variant.
fun createVehicleEnvironment(context: Context): VehicleEnvironment = VehicleEnvironment(PleosVehicleDataSource(context), object : EmulatorTestMode {
    override val available = false
    override val enabled = false
    override fun setEnabled(enabled: Boolean) {
        // Reject activation even if an unexpected caller attempts to enable the Debug-only feature in a Release build.
        require(!enabled) { "배포 빌드에서는 테스트 VIN을 사용할 수 없습니다." }
    }
})
