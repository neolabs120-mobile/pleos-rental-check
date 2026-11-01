package kr.taeuk.pleos.rentalcheck

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import kr.taeuk.pleos.rentalcheck.navigation.*
import kr.taeuk.pleos.rentalcheck.cloud.PairedBooking
import kr.taeuk.pleos.rentalcheck.model.SeatOccupancy
import kr.taeuk.pleos.rentalcheck.model.VehicleIdentity
import kr.taeuk.pleos.rentalcheck.model.InspectionRecord
import kr.taeuk.pleos.rentalcheck.model.VehicleSnapshot
import kr.taeuk.pleos.rentalcheck.model.VehicleReading
import kr.taeuk.pleos.rentalcheck.model.ReadStatus
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kr.taeuk.pleos.rentalcheck.speech.SeatSpeechState
import kr.taeuk.pleos.rentalcheck.speech.SeatSpeechStatus
import kr.taeuk.pleos.rentalcheck.ui.AccountConnectionScreen
import kr.taeuk.pleos.rentalcheck.ui.RentalCheckScreen
import kr.taeuk.pleos.rentalcheck.ui.RentalCheckTheme

/** Render production composables with explicitly labelled stage fixtures; never dispatch events or request speech for visual review. */
class NativeVisualReviewRunner : Instrumentation() {
    // Start this opt-in test runner separately from normal app launch, keeping the production APK free of a visual-test entry point.
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    // Capture real native layouts at wide, compact, large-font and night settings without replacing the persisted rental or synthesizing SDK success.
    override fun onStart() {
        val result = Bundle(); var code = Activity.RESULT_OK; var activity: MainActivity? = null
        try {
            val fixture = targetContext.filesDir.resolve("native-visual-fixture.json")
            val json = JSONObject(fixture.readText())
            val returned = snapshot(json.getJSONObject("returned"))
            val stored = InspectionRecord("visual-only", snapshot(json.getJSONObject("pickup")), returned)
            activity = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            val screen = activity
            runOnMainSync { ViewModelProvider(screen)[InspectionViewModel::class.java].stop() }
            val place = ReturnLocation("시험 반납 장소 · 42dot 인근", "서울시 강남구 남부순환로 2621", 37.48544722, 127.03636666,
                "공식 예제 좌표입니다. 실제 반납 거점이 아니며 지정 구역은 직접 확인하세요.", true)
            val now = System.currentTimeMillis()
            val guide = ReturnGuideState(destination = place, fix = LocationFix(37.497, 127.028, now), nowMillis = now,
                navigation = NavigationState(connected = true, message = "반납 장소 길 안내를 시작할 수 있어요."))
            val active = InspectionUiState(current = returned, record = stored.copy(returned = null, submittedAtMillis = null, receiptId = null),
                stage = InspectionStage.ACTIVE, serverConfigured = true,
                pairedBooking = PairedBooking("visual-only", "RC-UI-PREVIEW", "시연 이용자", VehicleIdentity.vin(stored.pickup).orEmpty(), place), returnGuide = guide)
            val states = listOf("active-light", "return-voice", "return-form", "pickup", "account", "active-compact-large-font", "active-night", "location-unavailable", "active-long-destination", "result")
            states.forEach { name ->
                val drawn = CountDownLatch(1)
                // A fresh factory AVD may open Navigation during provisioning; resume this isolated review Activity before waiting for its Compose frame.
                runOnMainSync { screen.startActivity(Intent(screen, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)) }
                waitForIdleSync()
                runOnMainSync {
                    ViewModelProvider(screen)[InspectionViewModel::class.java].stop()
                    screen.setContent {
                        LaunchedEffect(name) { withFrameNanos { }; withFrameNanos { }; drawn.countDown() }
                        val config = Configuration(LocalConfiguration.current).apply {
                            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                                if (name == "active-night") Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                        }
                        val density = LocalDensity.current
                        CompositionLocalProvider(LocalConfiguration provides config,
                            LocalDensity provides Density(density.density, if (name.contains("large-font")) 1.3f else 1f)) {
                            RentalCheckTheme {
                                Surface(Modifier.fillMaxSize()) {
                                    Box(contentAlignment = Alignment.TopCenter) {
                                        Column(if (name.contains("compact")) Modifier.width(620.dp).fillMaxHeight() else Modifier.fillMaxSize()) {
                                            Text("UI 표시 검증 · 예시 단계 · 실제 저장/재생 없음", Modifier.padding(8.dp), style = MaterialTheme.typography.labelMedium)
                                            Box(Modifier.weight(1f)) {
                                                when (name) {
                                                    "pickup" -> RentalCheckScreen(active.copy(stage = InspectionStage.PICKUP), {})
                                                    "return-form" -> RentalCheckScreen(active.copy(stage = InspectionStage.RETURN), {})
                                                    "result" -> RentalCheckScreen(active.copy(stage = InspectionStage.RESULT,
                                                        record = stored.copy(submittedAtMillis = now, receiptId = "UI-PREVIEW")), {})
                                                    "location-unavailable" -> RentalCheckScreen(active.copy(returnGuide = guide.copy(fix = null,
                                                        locationPermissionRequired = true, locationMessage = "차량 위치 사용 권한이 필요해요.")), {})
                                                    "active-long-destination" -> {
                                                        val longPlace = place.copy(name = "시험 장소 · 지하주차장 동쪽 출입구 인근 지정 반납 구역", address = "시험 주소 · 지하 3층 동쪽 구역 21번 기둥 옆 지정 주차면을 직접 확인해 주세요.",
                                                            instructions = "장소 안내가 길어져도 거리·버튼과 겹치지 않아야 합니다. ".repeat(6))
                                                        RentalCheckScreen(active.copy(pairedBooking = active.pairedBooking?.copy(returnLocation = longPlace),
                                                            returnGuide = guide.copy(destination = longPlace)), {})
                                                    }
                                                    "account" -> AccountConnectionScreen(InspectionUiState(), {})
                                                    "return-voice" -> RentalCheckScreen(active.copy(stage = InspectionStage.RETURN,
                                                        returnOccupiedSeats = SeatOccupancy.occupiedLabels(returned),
                                                        returnSeatSpeech = SeatSpeechState(status = SeatSpeechStatus.SPEAKING)), {})
                                                    else -> RentalCheckScreen(active, {})
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                check(drawn.await(20, TimeUnit.SECONDS)) { "Compose frame missing: $name" }
                waitForIdleSync(); Thread.sleep(400); uiAutomation.waitForIdle(200, 5_000)
                val bitmap = checkNotNull(uiAutomation.takeScreenshot())
                val folder = targetContext.filesDir.resolve("native-visual-review").apply { mkdirs() }
                folder.resolve("$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            result.putString("visualReview", "captured ten labelled native layouts; no functional playback or navigation claim")
        } catch (error: Exception) { code = Activity.RESULT_CANCELED; result.putString("visualReviewError", error.stackTraceToString()) }
        finally { activity?.let { runOnMainSync { it.finish() } } }
        finish(code, result)
    }

    // Read the host-supplied archived snapshot as a display fixture without opening or writing the user's current inspection preferences.
    private fun snapshot(json: JSONObject): VehicleSnapshot {
        val rows = json.getJSONArray("readings")
        return VehicleSnapshot(json.getLong("startedAtMillis"), json.getLong("completedAtMillis"),
            (0 until rows.length()).map { index ->
                val row = rows.getJSONObject(index)
                VehicleReading(row.getString("key"), row.getString("label"), ReadStatus.valueOf(row.getString("status")),
                    if (row.isNull("value")) null else row.getString("value"), row.getString("displayValue"),
                    if (row.isNull("unit")) null else row.getString("unit"), row.getString("detail"), row.getLong("observedAtMillis"))
            }, json.getString("source"))
    }
}
