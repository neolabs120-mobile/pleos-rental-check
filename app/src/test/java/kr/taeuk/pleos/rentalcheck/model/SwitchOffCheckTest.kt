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

    private fun door(area: String, label: String, closed: Boolean) =
        VehicleReading("door.$area.closed", "$label 문 닫힘 여부", ReadStatus.AVAILABLE, closed.toString(), if (closed) "닫힘" else "열림")
    private fun lock(area: String, label: String, locked: Boolean) =
        VehicleReading("door.$area.lock", "$label 잠금", ReadStatus.AVAILABLE, locked.toString(), if (locked) "잠김" else "잠금 해제")

    // An open door or trunk prompts on its own, and the trunk is named as a trunk rather than a "rear door".
    @Test fun openDoorAndTrunkPromptEvenWhenEverythingIsOff() {
        val check = SwitchOffCheck.from(snapshot(off("light.cabin", "실내등"), door("ROW_1_LEFT", "앞쪽 왼쪽", true),
            door("ROW_2_RIGHT", "뒤쪽 오른쪽", false), door("REAR", "트렁크", false), door("HOOD", "보닛", true)))
        assertTrue(check.leftOn.isEmpty())
        assertEquals(listOf("트렁크", "뒤쪽 오른쪽 문"), check.leftOpen.map { it.label })
        assertEquals(2, check.closedCount)
        assertTrue(check.needsPrompt(null))
        assertFalse(check.needsPrompt(check.signature))
        assertEquals("보닛", SwitchOffCheck.from(snapshot(door("HOOD", "보닛", false))).leftOpen.single().label)
    }

    // Unlocked doors are a reminder only: the renter locks the car after getting out, so they never prompt.
    @Test fun unlockedDoorsAreListedButNeverPrompt() {
        val check = SwitchOffCheck.from(snapshot(door("ROW_1_LEFT", "앞쪽 왼쪽", true), lock("ROW_1_LEFT", "앞쪽 왼쪽", false),
            lock("ROW_1_RIGHT", "앞쪽 오른쪽", true), lock("HOOD", "보닛", false)))
        assertEquals(listOf("앞쪽 왼쪽"), check.unlocked)
        assertTrue(check.leftOpen.isEmpty())
        assertFalse(check.needsPrompt(null))
    }

    // A door that could not be read is reported as unknown instead of being counted as closed.
    @Test fun unreadableDoorsStayUnknown() {
        val check = SwitchOffCheck.from(snapshot(
            VehicleReading.missing("door.ROW_2_LEFT.closed", "뒤쪽 왼쪽 문 닫힘 여부", ReadStatus.TIMED_OUT, ""),
            VehicleReading.missing("door", "문 개폐 상태", ReadStatus.PERMISSION_DENIED, ""),
            VehicleReading.missing("door.REAR.closed", "뒤쪽 문 닫힘 여부", ReadStatus.UNSUPPORTED, "")))
        assertEquals(listOf("뒤쪽 왼쪽 문", "문 개폐 상태"), check.unknown)
        assertEquals(0, check.closedCount)
        assertFalse(check.needsPrompt(null))
    }

    // Opening another door after acknowledging the first set prompts again, and both kinds are spoken separately.
    @Test fun newlyOpenedDoorPromptsAgainAndSpeechNamesBothKinds() {
        val first = SwitchOffCheck.from(snapshot(on("light.cabin", "실내등")))
        val second = SwitchOffCheck.from(snapshot(on("light.cabin", "실내등"), door("REAR", "뒤쪽", false)))
        assertTrue(second.needsPrompt(first.signature))
        val text = SwitchOffCheck.spokenReminder(second.leftOn, second.leftOpen)
        assertTrue(text.contains("켜져 있는 것: 실내등"))
        assertTrue(text.contains("열려 있는 곳: 트렁크"))
        assertTrue(text.contains("닫아 주세요"))
        assertFalse(SwitchOffCheck.spokenReminder(emptyList(), second.leftOpen).contains("꺼 주세요"))
    }
}
