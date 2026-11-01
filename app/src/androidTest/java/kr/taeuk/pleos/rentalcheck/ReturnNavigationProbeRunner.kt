package kr.taeuk.pleos.rentalcheck

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import kr.taeuk.pleos.rentalcheck.navigation.*
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList
import org.json.JSONArray

/** Diagnose real Pleos services using an explicitly labelled SDK-sample destination, without touching account or rental preferences. */
class ReturnNavigationProbeRunner : Instrumentation() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    @Volatile private var latest = ReturnGuideState()
    private lateinit var guide: ReturnGuideController
    private val diagnostics = CopyOnWriteArrayList<String>()

    // Restrict the probe to an explicit emulator invocation; the runner is absent from production APKs.
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments); require(arguments?.getString("allowTestDestination") == "true"); start()
    }

    // Retain real location, destination verification and timeout outcomes, rather than treating SDK initialization as route success.
    override fun onStart() {
        val output = Bundle(); var code = Activity.RESULT_OK
        try {
            runOnMainSync {
                guide = ReturnGuideController(PleosReturnGuideEngine(targetContext, scope, { diagnostics.add(it) }), scope, { latest = it })
                guide.attach("emulator-navigation-probe", ReturnLocation("시험 반납 장소 · 42dot 인근", "서울시 강남구 남부순환로 2621",
                    37.48544722, 127.03636666, "SDK 공식 예제 좌표 · 실제 반납 거점이 아니에요.", true))
            }
            waitFor("navigation service") { latest.navigation.connected }
            Thread.sleep(3_000); output.putString("locationBefore", position())
            runOnMainSync { guide.navigate() }
            waitFor("route result") { latest.navigation.confirmed || (!latest.navigation.busy && latest.navigation.message != "반납 장소 길 안내를 시작할 수 있어요.") }
            output.putString("navigationResult", JSONObject().put("connected", latest.navigation.connected)
                .put("confirmed", latest.navigation.confirmed).put("message", latest.navigation.message).toString())
            output.putString("locationAfter", position())
            output.putString("sdkEvents", JSONArray(diagnostics).toString())
        } catch (error: Throwable) { code = Activity.RESULT_CANCELED; output.putString("probeError", error.stackTraceToString()); output.putString("locationAfter", position()) }
        finally { runOnMainSync { if (::guide.isInitialized) guide.stop(); scope.cancel() } }
        finish(code, output)
    }

    // Preserve timestamped SDK coordinates only in host-private test evidence, never as a user-facing claim of actual vehicle location.
    private fun position() = JSONObject().put("latitude", latest.fix?.latitude ?: JSONObject.NULL)
        .put("longitude", latest.fix?.longitude ?: JSONObject.NULL).put("atMillis", latest.fix?.timestampMillis ?: JSONObject.NULL)
        .put("nowMillis", latest.nowMillis).put("message", latest.locationMessage).toString()

    // Bound service waits and include the visible state when the official emulator cannot provide a required response.
    private fun waitFor(label: String, ready: () -> Boolean) {
        val end = android.os.SystemClock.elapsedRealtime() + 45_000
        while (android.os.SystemClock.elapsedRealtime() < end) { if (ready()) return; Thread.sleep(100) }
        error("Timed out: $label; location=${latest.locationMessage}; navigation=${latest.navigation.message}")
    }
}
