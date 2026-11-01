package kr.taeuk.pleos.rentalcheck

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class InspectionViewModel(application: Application) : AndroidViewModel(application) {
    private val controller = InspectionController(application)
    private val mutableState = MutableStateFlow(controller.state)
    val state: StateFlow<InspectionUiState> = mutableState.asStateFlow()

    fun start() {
        controller.onChanged = { mutableState.value = it }
        controller.start()
    }
    fun stop() = controller.stop()
    fun refresh() = controller.refresh()
    fun capturePickup() = controller.capturePickup()
    fun captureReturn() = controller.captureReturn()
    fun dispatch(event: InspectionEvent) = controller.dispatch(event)

    override fun onCleared() {
        controller.dispose()
        super.onCleared()
    }
}
