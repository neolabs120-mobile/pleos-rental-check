package kr.taeuk.pleos.rentalcheck

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import kr.taeuk.pleos.rentalcheck.navigation.PLEOS_LOCATION_PERMISSION
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import kr.taeuk.pleos.rentalcheck.ui.RentalCheckScreen
import kr.taeuk.pleos.rentalcheck.ui.RentalCheckTheme
import kr.taeuk.pleos.rentalcheck.ui.AccountConnectionScreen
import kr.taeuk.pleos.rentalcheck.ui.EmulatorTestBanner
import kr.taeuk.pleos.rentalcheck.vehicle.EMULATOR_TEST_SOURCE

class MainActivity : ComponentActivity() {
    private lateinit var viewModel: InspectionViewModel
    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        // Re-evaluate the actual system grant after either answer; denial must leave location unavailable rather than fabricate a fix.
        viewModel.dispatch(InspectionEvent.RetryReturnLocation)
    }

    // Request location only from the driver's visible action; every inspection and navigation event continues through the normal controller.
    private fun handle(event: InspectionEvent) {
        if (event == InspectionEvent.RequestReturnLocationPermission) locationPermission.launch(PLEOS_LOCATION_PERMISSION)
        else viewModel.dispatch(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Render normal renter screens with a persistent test-identity notice whenever an explicitly labelled emulator session is shown.
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        viewModel = ViewModelProvider(this)[InspectionViewModel::class.java]
        setContent {
            RentalCheckTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                Column(Modifier.fillMaxSize()) {
                    if (state.emulatorTestEnabled || state.record?.pickup?.source == EMULATOR_TEST_SOURCE) EmulatorTestBanner()
                    Box(Modifier.weight(1f)) {
                        if (state.pairedBooking == null && !state.resultVisible) AccountConnectionScreen(state, ::handle)
                        else RentalCheckScreen(state, ::handle)
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.start()
    }

    override fun onStop() {
        viewModel.stop()
        super.onStop()
    }
}
