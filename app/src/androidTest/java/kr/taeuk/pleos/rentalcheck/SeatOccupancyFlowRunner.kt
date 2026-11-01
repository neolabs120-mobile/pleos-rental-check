package kr.taeuk.pleos.rentalcheck

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Bundle
import kr.taeuk.pleos.rentalcheck.assistance.TripAssistanceController
import kr.taeuk.pleos.rentalcheck.assistance.TripAssistanceState
import kr.taeuk.pleos.rentalcheck.model.*
import kr.taeuk.pleos.rentalcheck.vehicle.PleosVehicleDataSource
import kr.taeuk.pleos.rentalcheck.vehicle.VehicleDataSource
import kr.taeuk.pleos.rentalcheck.vehicle.InteractionObserver
import kr.taeuk.pleos.rentalcheck.vehicle.VehicleInteraction
import java.io.Closeable
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

/** A read-only SDK integration runner using the production adapter and foreground assistance manager in private preferences. */
class SeatOccupancyFlowRunner : Instrumentation() {
    @Volatile private var latest = TripAssistanceState()
    private lateinit var manager: TripAssistanceController
    private lateinit var source: PleosVehicleDataSource
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val seatCallbacks = CopyOnWriteArrayList<VehicleReading>()
    private var prefix = "seat_sdk_test"

    // Admit only explicit test namespaces; no server credentials, pairing or user rental data is required for a sensor test.
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        prefix = arguments?.getString("prefix") ?: prefix
        require(prefix.matches(Regex("seat_[a-z0-9_]{1,40}")))
        start()
    }

    // Confirm real snapshot reads, live state updates, area isolation and listener stop/resume behavior without injecting readings.
    override fun onStart() {
        val result = Bundle()
        val samples = JSONArray()
        var code = Activity.RESULT_OK
        val isolated = object : ContextWrapper(targetContext) {
            // Preserve the test namespace even when a production store asks for an application context.
            override fun getApplicationContext(): Context = this
            // Keep optional assistance recovery records separate from every existing app preference file.
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                targetContext.getSharedPreferences(prefix + "_" + name, mode)
        }
        try {
            runOnMainSync {
                source = PleosVehicleDataSource(isolated); source.initialize()
                val observed = object : VehicleDataSource by source, InteractionObserver {
                    // Record only real SDK callbacks and forward each event unchanged to the production assistance manager.
                    override suspend fun observeInteractions(onEvent: (VehicleInteraction) -> Unit): Closeable =
                        source.observeInteractions { event -> event.reading?.let { seatCallbacks.add(it) }; onEvent(event) }
                }
                manager = TripAssistanceController(isolated, observed, scope, {}, { latest = it })
                // This private assistance identity is only a test scope; no VIN substitution, rental or reservation is created.
                manager.attach(prefix, "seat-signal-diagnostic", null); manager.start()
            }
            until("initial real seat readings") { latest.listening && latest.seatReadings.isNotEmpty() && latest.seatReadings.all { it.available } }
            val seats = listOf("ROW_1_LEFT", "ROW_1_RIGHT", "ROW_2_LEFT", "ROW_2_RIGHT").map { "seat.$it.occupancy" }
            check(latest.seatReadings.map { it.key }.containsAll(seats))
            samples.put(snapshot("baseline"))
            for (key in seats) for (occupied in listOf(false, true, false)) {
                val before = seatCallbacks.count { it.key == key }
                stage("SET", key, occupied)
                until("normalized seat $key=$occupied") { latest.seatReadings.any { it.key == key && it.available && it.value == occupied.toString() } }
                val current = snapshot("snapshot after callback")
                check(latest.memory.openedSpaces.isEmpty() && latest.memory.markedSpaces.isEmpty())
                samples.put(current.put("target", key).put("consoleOccupied", occupied).put("callbacks", seatCallbacks.count { it.key == key } - before)
                    .put("liveValue", latest.seatReadings.first { it.key == key }.value))
            }
            runOnMainSync { manager.stop() }
            val stoppedCallbacks = latest.receivedCallbacks
            stage("STOPPED", "seat.ROW_1_RIGHT.occupancy", true)
            Thread.sleep(1_500)
            check(latest.seatReadings.isEmpty() && latest.receivedCallbacks == stoppedCallbacks)
            runOnMainSync { manager.start() }
            until("fresh seat signal after resuming") { latest.listening && latest.seatReadings.any { it.key == "seat.ROW_1_RIGHT.occupancy" && it.available } }
            val resumedCallbacks = latest.receivedCallbacks
            stage("RESUMED", "seat.ROW_1_RIGHT.occupancy", false)
            until("resumed normalized vacancy") { latest.seatReadings.any { it.key == "seat.ROW_1_RIGHT.occupancy" && it.available && it.value == "false" } }
            result.putString("seatResult", JSONObject().put("passed", true).put("samples", samples)
                .put("fourControlledAreasRead", true).put("supportedAreaCount", latest.seatReadings.size)
                .put("stopDropsCallbacks", true).put("resumeReceivesFreshState", true)
                .put("callbacksAfterResume", latest.receivedCallbacks - resumedCallbacks).put("noLuggageInference", true).toString())
        } catch (error: Throwable) {
            code = Activity.RESULT_CANCELED
            result.putString("seatResult", JSONObject().put("passed", false).put("error", error.toString()).put("samples", samples).toString())
        } finally {
            runOnMainSync { if (::manager.isInitialized) manager.stop(); scope.cancel(); if (::source.isInitialized) source.close() }
        }
        finish(code, result)
    }

    // Read through the production snapshot adapter, recording only seat values rather than unrelated VIN/account information.
    private fun snapshot(label: String): JSONObject {
        val snapshot = runBlocking { source.readSnapshot() }
        val readings = SeatOccupancy.readings(snapshot)
        return JSONObject().put("label", label).put("readings", JSONArray(readings.map { JSONObject()
            .put("key", it.key).put("status", it.status.name).put("value", it.value ?: JSONObject.NULL).put("atMillis", it.observedAtMillis)
            .put("rawValue", snapshot.find(it.key.removeSuffix(".occupancy") + ".rawOccupancy")?.value ?: JSONObject.NULL)
            .put("sdkValue", snapshot.find(it.key.removeSuffix(".occupancy") + ".sdkOccupancy")?.value ?: JSONObject.NULL) }))
    }

    // Bound every SDK expectation and retain diagnostic status if the service or permission does not support an area.
    private fun until(label: String, probe: () -> Boolean) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 45_000
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            if (probe()) return
            Thread.sleep(100)
        }
        error("Timed out: $label; readings=${latest.seatReadings}")
    }

    // Let the host change only the published Console seat signal after the native listener has reached the expected test phase.
    private fun stage(name: String, key: String, occupied: Boolean) {
        val bundle = Bundle()
        bundle.putString("seatStage", JSONObject().put("name", name).put("key", key).put("occupied", occupied).toString())
        sendStatus(0, bundle)
    }
}
