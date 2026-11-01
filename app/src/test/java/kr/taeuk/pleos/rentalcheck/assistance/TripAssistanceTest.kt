package kr.taeuk.pleos.rentalcheck.assistance

import kr.taeuk.pleos.rentalcheck.model.*
import kr.taeuk.pleos.rentalcheck.data.UsageMemoryCodec
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TripAssistanceTest {
    private val key = "window.ROW_1_LEFT.position"

    // Supply explicit success/failure fixtures so a missing value can never accidentally satisfy a guide action.
    private fun snapshot(closed: String?, gear: String? = "GEAR_PARK", available: Boolean = true, selected: String = key): VehicleSnapshot =
        VehicleSnapshot(100, 200, listOf(VehicleReading("gear.selection", "기어", if (gear == null) ReadStatus.UNAVAILABLE else ReadStatus.AVAILABLE, gear),
            VehicleReading(selected.removeSuffix(".position") + ".closed", "창문", if (available) ReadStatus.AVAILABLE else ReadStatus.ERROR, closed)))

    @Test fun closedOpenClosedCompletesOnlyInOrder() {
        // A complete practice must contain three separately received states in the requested order.
        var p = WindowPractice(key, "앞 왼쪽", 0).observe(snapshot("true"), 100)
        assertEquals(PracticeStep.OPEN, p.step)
        p = p.observe(snapshot("false"), 200); assertEquals(PracticeStep.CLOSE, p.step)
        p = p.observe(snapshot("true"), 300); assertEquals(PracticeStep.COMPLETE, p.step)
    }

    @Test fun initiallyOpenMustCloseBeforeNewOpening() {
        // Starting with an open window cannot count that pre-existing state as the user's opening action.
        val p = WindowPractice(key, "창문", 0).observe(snapshot("false"), 100)
        assertEquals(PracticeStep.CLOSE_FIRST, p.step)
        assertEquals(PracticeStep.OPEN, p.observe(snapshot("true"), 200).step)
    }

    @Test fun repeatedClosedReadingsDoNotComplete() {
        // Receiving the same cached-looking closed state repeatedly is not evidence that the window was opened.
        var p = WindowPractice(key, "창문", 0)
        repeat(8) { p = p.observe(snapshot("true"), 100L + it) }
        assertEquals(PracticeStep.OPEN, p.step)
    }

    @Test fun failuresAndOtherWindowsCannotAdvance() {
        // Unknown/error readings and movement of a different window must leave the selected exercise waiting.
        val p = WindowPractice(key, "창문", 0).observe(snapshot("true"), 100)
        assertEquals(PracticeStep.OPEN, p.observe(snapshot("false", available = false), 200).step)
        assertEquals(PracticeStep.OPEN, p.observe(snapshot(null), 300).step)
        assertEquals(PracticeStep.OPEN, p.observe(snapshot("false", selected = "window.ROW_2_RIGHT.position"), 400).step)
    }

    @Test fun nonParkAndUnknownGearInterrupt() {
        // Neither a known driving gear nor missing gear data can authorise an exercise meant only for a parked car.
        for (gear in listOf("GEAR_DRIVE", "GEAR_REVERSE", "GEAR_UNKNOWN", null))
            assertEquals(PracticeStep.INTERRUPTED, WindowPractice(key, "창문", 0).observe(snapshot("true", gear), 100).step)
    }

    @Test fun deadlineCannotBeBypassedByLastClosedReading() {
        // A closing signal at or after the elapsed-time deadline is a timeout, not successful completion.
        val p = WindowPractice(key, "창문", 10_000, PracticeStep.CLOSE)
        assertEquals(1, p.remainingSeconds(99_999))
        assertEquals(PracticeStep.TIMED_OUT, p.observe(snapshot("true"), 100_000).step)
        assertEquals(90, p.remainingSeconds(5_000))
    }

    @Test fun interruptionDoesNotAutoResume() {
        // Returning to P after an interruption requires an explicit new start instead of silently resuming.
        val p = WindowPractice(key, "창문", 0).observe(snapshot("true", "GEAR_DRIVE"), 100)
        assertEquals(PracticeStep.INTERRUPTED, p.observe(snapshot("true"), 200).step)
    }

    @Test fun validOpeningsRememberOnlyTheirKnownSpace() {
        // A rear door and a partially open zero-based trunk are distinct observed locations, not detected objects.
        val memory = UsageMemory().observe(StorageSpace.REAR_RIGHT.doorKey, 1, 0, 1, true)
            .observe(StorageSpace.TRUNK.doorKey, 30, 0, 100, true)
        assertEquals(setOf(StorageSpace.REAR_RIGHT, StorageSpace.TRUNK), memory.openedSpaces)
        assertTrue(memory.markedSpaces.isEmpty())
    }

    @Test fun invalidUnsupportedAndCombinedAreasAreIgnored() {
        // Nonstandard ranges, failures, null/out-of-range values, the hood and ambiguous combined areas must never suggest a luggage location.
        var memory = UsageMemory()
        for (args in listOf(listOf(null, 0, 1), listOf(0, 0, 1), listOf(-1, 0, 1), listOf(2, 0, 1), listOf(1, -1, 1)))
            memory = memory.observe(StorageSpace.REAR_RIGHT.doorKey, args[0], args[1]!!, args[2]!!, true)
        memory = memory.observe(StorageSpace.TRUNK.doorKey, 1, 0, 100, false)
            .observe("door.HOOD.position", 100, 0, 100, true)
            .observe("door.ROW_2_LEFT+ROW_2_RIGHT.position", 1, 0, 1, true)
        assertTrue(memory.openedSpaces.isEmpty())
    }

    @Test fun closingAndUnmarkingDoNotEraseObservedOpening() {
        // Removing an explicit luggage mark or closing a door cannot erase the historical observation used by the reminder.
        val memory = UsageMemory().observe(StorageSpace.TRUNK.doorKey, 20, 0, 100, true)
            .mark(StorageSpace.TRUNK, true).mark(StorageSpace.TRUNK, false)
            .observe(StorageSpace.TRUNK.doorKey, 0, 0, 100, true)
        assertEquals(setOf(StorageSpace.TRUNK), memory.openedSpaces)
        assertTrue(memory.markedSpaces.isEmpty())
    }

    @Test fun memoryRoundTripAndOldRecordsRemainExplicit() {
        // Persist observed and manually marked locations separately and default old records to incomplete coverage.
        val memory = UsageMemory(setOf(StorageSpace.REAR_LEFT), setOf(StorageSpace.TRUNK), true)
        assertEquals(memory, UsageMemoryCodec.decode(UsageMemoryCodec.encode(memory)))
        assertTrue(UsageMemoryCodec.decode(null).monitoringGaps)
        assertTrue(UsageMemoryCodec.decode(JSONObject("{\"openedSpaces\":[\"UNKNOWN\"]}")).openedSpaces.isEmpty())
    }
}
