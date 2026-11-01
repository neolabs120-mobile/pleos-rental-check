package kr.taeuk.pleos.rentalcheck.model

/** Store locations, never inferred objects or a diagnosis that a seat is occupied. */
enum class StorageSpace(val label: String, val doorKey: String) {
    FRONT_LEFT("운전석 주변", "door.ROW_1_LEFT.position"),
    FRONT_RIGHT("앞쪽 오른쪽 좌석 주변", "door.ROW_1_RIGHT.position"),
    REAR_LEFT("뒤쪽 왼쪽 좌석 주변", "door.ROW_2_LEFT.position"),
    REAR_RIGHT("뒤쪽 오른쪽 좌석 주변", "door.ROW_2_RIGHT.position"),
    TRUNK("트렁크", "door.REAR.position");
}

data class UsageMemory(
    val openedSpaces: Set<StorageSpace> = emptySet(),
    val markedSpaces: Set<StorageSpace> = emptySet(),
    val monitoringGaps: Boolean = true,
) {
    val suggestedSpaces: Set<StorageSpace> get() = openedSpaces + markedSpaces

    // Only an available, in-range positive value with a known zero-closed range is evidence of an observed opening.
    fun observe(key: String, value: Int?, minimum: Int, maximum: Int, available: Boolean): UsageMemory {
        val space = StorageSpace.entries.firstOrNull { it.doorKey == key } ?: return this
        return if (available && minimum == 0 && maximum > 0 && value != null && value in 1..maximum)
            copy(openedSpaces = openedSpaces + space) else this
    }

    // Keep explicit luggage marks independent from SDK observations so removing a mark does not erase a door opening.
    fun mark(space: StorageSpace, marked: Boolean): UsageMemory =
        copy(markedSpaces = if (marked) markedSpaces + space else markedSpaces - space)
}

enum class PracticeStep { CLOSE_FIRST, OPEN, CLOSE, COMPLETE, TIMED_OUT, INTERRUPTED }

data class WindowPractice(val key: String, val label: String, val startedElapsedMillis: Long,
    val step: PracticeStep = PracticeStep.CLOSE_FIRST, val problem: String? = null) {
    val running: Boolean get() = step in setOf(PracticeStep.CLOSE_FIRST, PracticeStep.OPEN, PracticeStep.CLOSE)

    // Use elapsed time so wall-clock/timezone changes cannot extend or prematurely complete a 90-second exercise.
    fun remainingSeconds(now: Long): Int = ((90_000 - (now - startedElapsedMillis).coerceAtLeast(0) + 999) / 1_000).toInt().coerceIn(0, 90)

    // Advance only from fresh SDK snapshots; failed/unknown readings cannot satisfy an action, and leaving P interrupts the exercise.
    fun observe(snapshot: VehicleSnapshot, now: Long): WindowPractice {
        if (!running) return this
        if (remainingSeconds(now) == 0) return copy(step = PracticeStep.TIMED_OUT, problem = "90초가 지났어요. 창문을 직접 닫고 다시 시작하거나 건너뛰세요.")
        val gear = snapshot.find("gear.selection")
        if (gear?.available != true || gear.value != "GEAR_PARK") return copy(step = PracticeStep.INTERRUPTED,
            problem = "P단을 확인할 수 없어 연습을 중단했어요. 완전히 정차한 뒤 다시 시작하세요.")
        val closed = snapshot.find(key.removeSuffix(".position") + ".closed")?.takeIf { it.available }?.value
        val next = when {
            step == PracticeStep.CLOSE_FIRST && closed == "true" -> PracticeStep.OPEN
            step == PracticeStep.OPEN && closed == "false" -> PracticeStep.CLOSE
            step == PracticeStep.CLOSE && closed == "true" -> PracticeStep.COMPLETE
            else -> step
        }
        return copy(step = next, problem = if (closed !in setOf("true", "false")) "창문 값 수신을 기다리고 있어요. 확인되지 않은 동작은 완료 처리하지 않아요." else null)
    }
}
