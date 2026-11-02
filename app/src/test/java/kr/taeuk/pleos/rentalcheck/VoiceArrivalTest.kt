package kr.taeuk.pleos.rentalcheck

import kr.taeuk.pleos.rentalcheck.data.UsageMemoryCodec
import kr.taeuk.pleos.rentalcheck.model.*
import kr.taeuk.pleos.rentalcheck.navigation.*
import kr.taeuk.pleos.rentalcheck.speech.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class VoiceArrivalTest {
    private val place = ReturnLocation("시험 반납", "시험 주소", 37.48544722, 127.03636666, isTest = true)
    private val now = 1_800_000_000_000L
    private val vin = "KMH00000000000001"

    // Use fresh timestamped independent readings so tests distinguish P from stale P, wrong identity and failed SDK results.
    private fun snapshot(gear: String = "GEAR_PARK", at: Long = now, identity: String = vin): VehicleSnapshot =
        VehicleSnapshot(at, at, listOf(VehicleReading("gear.selection", "기어", ReadStatus.AVAILABLE, gear, observedAtMillis = at),
            VehicleReading("vehicle.vin", "VIN", ReadStatus.AVAILABLE, identity, observedAtMillis = at)))

    // Produce distinct SDK fix timestamps at one test destination; repeated UI publication must not act like a second location observation.
    private fun guide(at: Long = now - 10, far: Boolean = false) = ReturnGuideState(place,
        LocationFix(place.latitude + if (far) .01 else 0.0, place.longitude, at), nowMillis = now)

    // Suggest common Korean location/item phrases, while ambiguous or absent locations require the user to choose.
    @Test fun speechDraftRequiresReviewAndHandlesKoreanLocations() {
        val cases = mapOf("트렁크에 캐리어를 뒀어" to (StorageSpace.TRUNK to "캐리어"),
            "트렁크의 캐리어를 두었어" to (StorageSpace.TRUNK to "캐리어"),
            "뒤 오른쪽에 가방을 뒀어요" to (StorageSpace.REAR_RIGHT to "가방"),
            "조수석에다가 우산을 놓았어요" to (StorageSpace.FRONT_RIGHT to "우산"))
        cases.forEach { (text, expected) -> val draft = LuggageDraft.fromSpeech(text)
            assertEquals(expected.first, draft.space); assertEquals(expected.second, draft.item); assertNotNull(draft.confirmed()) }
        assertNull(LuggageDraft.fromSpeech("가방을 뒀어").confirmed())
        assertNull(LuggageDraft.fromSpeech("뒤 오른쪽에 가방 트렁크에 캐리어").space)
        assertNull(LuggageDraft(space = StorageSpace.TRUNK, item = "\n").confirmed())
        assertNull(LuggageDraft(space = StorageSpace.TRUNK, item = "a".repeat(41)).confirmed())
    }

    // Editing and deleting a note must preserve other places, independent manual marks and observed door history across serialization.
    @Test fun notesRoundTripWithoutOverwritingObservationHistory() {
        val before = UsageMemory(setOf(StorageSpace.REAR_RIGHT), setOf(StorageSpace.TRUNK))
        val memory = before.remember(LuggageMemo(StorageSpace.TRUNK, "캐리어")).remember(LuggageMemo(StorageSpace.FRONT_RIGHT, "우산"))
            .remember(LuggageMemo(StorageSpace.TRUNK, "가방"))
        assertEquals(2, memory.luggageMemos.size)
        assertEquals(memory, UsageMemoryCodec.decode(UsageMemoryCodec.encode(memory)))
        val removed = memory.forget(StorageSpace.TRUNK)
        assertEquals(before.openedSpaces, removed.openedSpaces); assertEquals(before.markedSpaces, removed.markedSpaces)
        assertEquals(listOf("우산"), removed.luggageMemos.map { it.item })
        assertTrue(UsageMemoryCodec.decode(JSONObject()).luggageMemos.isEmpty())
    }

    // Partial text and readiness cannot populate a confirmed item; closing or switching renters rejects all late results.
    @Test fun recognitionCannotSaveOrCrossSessions() {
        val engine = RecognitionFake(); val voice = LuggageVoice(engine) {}
        voice.attach("rental-A"); voice.open(); voice.start(); val old = engine.receive!!
        old(RecognitionEvent(RecognitionKind.PARTIAL, "트렁크에 캐리어를 뒀어"))
        assertNull(voice.state.draft.confirmed())
        old(RecognitionEvent(RecognitionKind.FINAL, "트렁크에 캐리어를 뒀어"))
        assertEquals("캐리어", voice.state.draft.item); assertFalse(voice.state.capturing)
        voice.attach("rental-B")
        old(RecognitionEvent(RecognitionKind.FINAL, "운전석에 지갑을 뒀어"))
        assertFalse(voice.state.open); assertTrue(voice.state.draft.item.isEmpty())
    }

    // An interrupted/error session stays editable and permits manual entry; it cannot reactivate itself from a retired SDK callback.
    @Test fun failedRecognitionKeepsManualEntryAvailable() {
        val engine = RecognitionFake(); val voice = LuggageVoice(engine) {}
        voice.attach("rental"); voice.open(); voice.start(); val late = engine.receive!!
        voice.problem("P 상태 확인 불가"); late(RecognitionEvent(RecognitionKind.FINAL, "트렁크에 캐리어를 뒀어"))
        assertFalse(voice.state.capturing); assertTrue(voice.state.draft.item.isEmpty())
        voice.edit(StorageSpace.REAR_LEFT, "가방")
        assertEquals(LuggageMemo(StorageSpace.REAR_LEFT, "가방"), voice.state.draft.confirmed())
        voice.close(); assertTrue(engine.stops > 0)
    }

    // Fresh P and VIN must both agree; stale, future, unknown and driving states never authorize a stationary interaction.
    @Test fun stationaryChecksRejectStaleOrWrongIdentity() {
        assertTrue(stationaryFor(snapshot(), vin, now))
        for (gear in listOf("GEAR_DRIVE", "GEAR_REVERSE", "GEAR_UNKNOWN")) assertFalse(stationaryFor(snapshot(gear), vin, now))
        assertFalse(stationaryFor(snapshot(at = now - 8_001), vin, now))
        assertFalse(stationaryFor(snapshot(at = now + 2_001), vin, now))
        assertFalse(stationaryFor(snapshot(identity = "OTHER"), vin, now))
        assertFalse(stationaryFor(null, vin, now)); assertFalse(stationaryFor(snapshot(), null, now))
    }

    // One nearby fix or repeated publications of it must not prompt; a second independent fix and fresh P produce only a suggestion.
    @Test fun arrivalRequiresDistinctFixesAndNeverRepeatsOnPolling() {
        val arrival = ReturnArrival()
        assertFalse(arrival.update("A", guide(), snapshot(), vin, now, true))
        repeat(5) { assertFalse(arrival.update("A", guide(), snapshot(), vin, now, true)) }
        assertTrue(arrival.update("A", guide(now - 10 + 1), snapshot(), vin, now, true)); assertTrue(arrival.visible)
        repeat(5) { assertFalse(arrival.update("A", guide(now - 10 + 2 + it), snapshot(), vin, now, true)) }
        arrival.dismiss(); assertFalse(arrival.visible)
        assertFalse(arrival.update("A", guide(now - 10 + 9), snapshot(), vin, now, true))
    }

    // D gear, old/null/far location and background state all suppress the prompt even when some earlier near evidence exists.
    @Test fun arrivalRejectsDrivingMissingAndStaleState() {
        for (g in listOf(guide(now - 15_001), guide(far = true), guide().copy(fix = null))) {
            val arrival = ReturnArrival()
            repeat(3) { assertFalse(arrival.update("A", g, snapshot(), vin, now, true)) }
            assertFalse(arrival.visible)
        }
        val arrival = ReturnArrival()
        arrival.update("A", guide(), snapshot("GEAR_DRIVE"), vin, now, true)
        assertFalse(arrival.update("A", guide(now - 10 + 1), snapshot("GEAR_DRIVE"), vin, now, true))
        assertFalse(arrival.update("A", guide(now - 10 + 2), snapshot(), vin, now, false)); assertFalse(arrival.visible)
        arrival.update("A", guide(now - 10 + 3), snapshot(), vin, now, true)
        assertTrue(arrival.update("A", guide(now - 10 + 4), snapshot(), vin, now, true))
        assertFalse(arrival.update("A", guide(now - 10 + 5), snapshot("GEAR_DRIVE"), vin, now, true)); assertFalse(arrival.visible)
    }

    // Dismissal survives a foreground pause for the same destination, while a new renter starts with independent evidence.
    @Test fun arrivalDeduplicationIsScopedToRentalAndDestination() {
        val arrival = ReturnArrival()
        arrival.update("A", guide(), snapshot(), vin, now, true)
        arrival.update("A", guide(now - 10 + 1), snapshot(), vin, now, true)
        arrival.update("A", guide().copy(fix = null), null, vin, now, false)
        arrival.update("A", guide(now - 10 + 2), snapshot(), vin, now, true)
        assertFalse(arrival.update("A", guide(now - 10 + 3), snapshot(), vin, now, true))
        assertTrue(arrival.visible) // An interrupted suggestion can return without repeating audio until the user explicitly dismisses it.
        arrival.dismiss()
        arrival.update("A", guide().copy(fix = null), null, vin, now, false)
        arrival.update("A", guide(now - 10 + 2), snapshot(), vin, now, true)
        assertFalse(arrival.update("A", guide(now - 10 + 3), snapshot(), vin, now, true))
        assertFalse(arrival.update("B", guide(now - 10 + 4), snapshot(), vin, now, true))
        assertTrue(arrival.update("B", guide(now - 10 + 5), snapshot(), vin, now, true))
    }

    private class RecognitionFake : LuggageRecognitionEngine {
        var receive: ((RecognitionEvent) -> Unit)? = null; var stops = 0
        // Expose controlled SDK event ordering so cancellation and renter isolation can be exercised without Android services.
        override fun start(receive: (RecognitionEvent) -> Unit) { this.receive = receive }
        // Retain old callbacks deliberately to prove that the controller rejects them after stopping.
        override fun stop() { stops++ }
    }

    // The service may finish microphone capture before delivering its final text; ending must neither save a partial nor reject the subsequent final result.
    @Test fun captureEndWaitsForFinalInsteadOfDiscardingIt() {
        val engine = RecognitionFake(); val voice = LuggageVoice(engine) {}
        voice.attach("rental"); voice.open(); voice.start(); val receive = engine.receive!!
        receive(RecognitionEvent(RecognitionKind.PARTIAL, "트렁크에 캐리어"))
        receive(RecognitionEvent(RecognitionKind.ENDED))
        assertEquals(VoiceMemoPhase.FINALIZING, voice.state.phase); assertNull(voice.state.draft.confirmed())
        receive(RecognitionEvent(RecognitionKind.FINAL, "트렁크에 캐리어를 뒀어"))
        assertEquals(VoiceMemoPhase.EDITING, voice.state.phase); assertEquals("캐리어", voice.state.draft.item)
    }
}
