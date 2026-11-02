package kr.taeuk.pleos.rentalcheck

import android.os.Bundle
import androidx.activity.ComponentActivity

/** Non-exported Debug-only host for real SDK/controller tests; production screens are rendered by the instrumentation runner. */
class DebugInspectionActivity : ComponentActivity() {
    // Avoid constructing the normal screen's second ViewModel/SDK client while an isolated real controller is under test.
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState) }
}
