package kr.taeuk.pleos.rentalcheck.model

import org.junit.Assert.*
import org.junit.Test

class SwitchOffCheckTest {
    private fun on(key: String, label: String, state: String = "켜짐") =
        VehicleReading("switchOff.$key", label, ReadStatus.AVAILABLE, "true", state)
    private fun off(key: String, label: String, state: String = "꺼짐") =
        VehicleReading("switchOff.$key", label, ReadStatus.AVAILABLE, "false", state)
    private fun missing(key: String, label: String, status: ReadStatus) = VehicleReading.missing("switchOff.$key", label, status, "")
    private fun snapshot(vararg readings: VehicleReading) = VehicleSnapshot(10, 20, readings.toList())

    // Only an explicit "true" reading is reported as left on; everything off produces no prompt.
    @Test fun allOffNeedsNoPrompt() {
        val check = SwitchOffCheck.from(snapshot(off("light.lowBeam", "전조등"), off("turnSignal", "방향지시등"),
            off("light.lowBeam.drl", "미등", "주간주행등")))
        assertTrue(check.leftOn.isEmpty())
        assertEquals(3, check.offCount)
        assertFalse(check.needsPrompt(null))
    }

    @Test fun leftOnItemsAreNamedWithTheirState() {
        val check = SwitchOffCheck.from(snapshot(on("wiper.FRONT_WINDSHIELD", "앞 유리 와이퍼", "작동 중"),
            on("light.cabin", "실내등"), off("light.lowBeam", "전조등")))
        assertEquals(listOf("실내등", "앞 유리 와이퍼"), check.leftOn.map { it.label })
        assertEquals("작동 중", check.leftOn.last().state)
        assertTrue(check.needsPrompt(null))
    }

    // Failures stay visible as unknown, but a feature the vehicle does not have is not reported to the renter.
    @Test fun failedReadsAreUnknownAndUnsupportedFeaturesAreQuiet() {
        val check = SwitchOffCheck.from(snapshot(missing("light.frontFog", "앞 안개등", ReadStatus.UNSUPPORTED),
            missing("light.cabin", "실내등", ReadStatus.PERMISSION_DENIED), missing("turnSignal", "방향지시등", ReadStatus.TIMED_OUT),
            VehicleReading("switchOff.wiper.X", "와이퍼", ReadStatus.AVAILABLE, "maybe", "?")))
        assertTrue(check.leftOn.isEmpty())
        assertEquals(listOf("실내등", "방향지시등", "와이퍼"), check.unknown)
        assertFalse(check.needsPrompt(null))
    }

    // Acknowledging one set suppresses only that exact set; another item switched on later prompts again.
    @Test fun acknowledgementCoversOnlyTheSameSet() {
        val first = SwitchOffCheck.from(snapshot(on("light.cabin", "실내등")))
        assertFalse(first.needsPrompt(first.signature))
        val second = SwitchOffCheck.from(snapshot(on("light.cabin", "실내등"), on("steeringWheelHeat", "핸들 열선", "2단계")))
        assertTrue(second.needsPrompt(first.signature))
    }

    @Test fun unrelatedReadingsAreIgnored() {
        val check = SwitchOffCheck.from(snapshot(VehicleReading("window.FL.closed", "창문", ReadStatus.AVAILABLE, "true"),
            VehicleReading("seat.ROW_1_LEFT.occupancy", "좌석", ReadStatus.AVAILABLE, "true")))
        assertTrue(check.leftOn.isEmpty() && check.unknown.isEmpty())
        assertEquals(0, check.offCount)
        assertTrue(SwitchOffCheck.from(null).leftOn.isEmpty())
    }

    @Test fun spokenReminderListsItemsWithoutGuessingAParticle() {
        val text = SwitchOffCheck.spokenReminder(listOf(SwitchOffItem("a", "전조등", "켜짐"), SwitchOffItem("b", "핸들 열선", "2단계")))
        assertTrue(text.contains("전조등, 핸들 열선"))
        assertTrue(text.contains("꺼 주세요"))
    }
}
