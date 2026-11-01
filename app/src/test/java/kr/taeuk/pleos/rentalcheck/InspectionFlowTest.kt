package kr.taeuk.pleos.rentalcheck

import kr.taeuk.pleos.rentalcheck.model.InspectionRecord
import kr.taeuk.pleos.rentalcheck.model.VehicleSnapshot
import org.junit.Assert.*
import org.junit.Test

class InspectionFlowTest {
    private val pickup = VehicleSnapshot(100, 200, emptyList())

    @Test fun emptyStorageRequiresVehicleConnection() {
        assertEquals(InspectionStage.CONNECT, InspectionStage.restored(null))
    }

    @Test fun savedPickupRequiresReconnectOnLaunch() {
        assertEquals(InspectionStage.CONNECT, InspectionStage.restored(InspectionRecord("session", pickup)))
    }

    @Test fun savedReturnWithoutServerReceiptResumesEvidence() {
        assertEquals(InspectionStage.EVIDENCE, InspectionStage.restored(InspectionRecord("session", pickup,
            VehicleSnapshot(300, 400, emptyList()))))
    }

    @Test fun pickupSaveContinuesToActiveSession() {
        assertEquals(InspectionStage.ACTIVE, InspectionStage.afterSave(InspectionRecord("session", pickup)))
    }

    @Test fun returnSaveCannotSkipPhotosAndServerSubmission() {
        assertEquals(InspectionStage.EVIDENCE, InspectionStage.afterSave(InspectionRecord("session", pickup,
            VehicleSnapshot(300, 400, emptyList()))))
    }

    @Test fun serverAcknowledgedReturnOpensResult() {
        assertEquals(InspectionStage.RESULT, InspectionStage.restored(InspectionRecord("session", pickup,
            VehicleSnapshot(300, 400, emptyList()), submittedAtMillis = 500, receiptId = "receipt")))
    }
}
