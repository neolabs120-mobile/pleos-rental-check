package kr.taeuk.pleos.rentalcheck

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import kotlinx.coroutines.runBlocking
import kr.taeuk.pleos.rentalcheck.model.SwitchOffCheck
import kr.taeuk.pleos.rentalcheck.vehicle.PleosVehicleDataSource
import org.json.JSONArray
import org.json.JSONObject

/** Read one real snapshot through the production data source and report only the switch-off and door readings; never touches stored rentals. */
class SwitchOffProbeRunner : Instrumentation() {
    private var label = "probe"

    // Start the opt-in probe separately from the app so the production APK has no test entry point.
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments); label = arguments?.getString("label") ?: label; start()
    }

    // Write the raw switch-off readings and the derived check so the host can compare them with the Console values it set.
    override fun onStart() {
        val result = Bundle(); var code = Activity.RESULT_OK
        val source = PleosVehicleDataSource(targetContext)
        try {
            source.initialize()
            val snapshot = runBlocking { source.readSnapshot() }
            val check = SwitchOffCheck.from(snapshot)
            val readings = JSONArray()
            snapshot.readings.filter { it.key.startsWith(SwitchOffCheck.PREFIX) || it.key.startsWith("door") }.forEach {
                readings.put(JSONObject().put("key", it.key).put("label", it.label).put("status", it.status.name)
                    .put("value", it.value ?: JSONObject.NULL).put("display", it.displayValue).put("detail", it.detail))
            }
            val report = JSONObject().put("label", label).put("readings", readings)
                .put("leftOn", JSONArray(check.leftOn.map { it.key })).put("leftOpen", JSONArray(check.leftOpen.map { it.key }))
                .put("unlocked", JSONArray(check.unlocked)).put("closedCount", check.closedCount).put("unknown", JSONArray(check.unknown)).put("offCount", check.offCount)
            targetContext.filesDir.resolve("switchoff-probe-$label.json").writeText(report.toString(1))
            result.putString("switchOffProbe", "leftOn=${check.leftOn.size} off=${check.offCount} open=${check.leftOpen.size} closed=${check.closedCount} unlocked=${check.unlocked.size} unknown=${check.unknown.size}")
        } catch (error: Exception) { code = Activity.RESULT_CANCELED; result.putString("switchOffProbeError", error.stackTraceToString()) }
        finally { runCatching { source.close() } }
        finish(code, result)
    }
}
