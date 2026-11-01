package kr.taeuk.pleos.rentalcheck.vehicle

import java.io.Closeable
import kr.taeuk.pleos.rentalcheck.model.ReadStatus
import kr.taeuk.pleos.rentalcheck.model.VehicleReading

/** A callback observation is independent of saved pickup/return snapshots. Null and failure remain unknown. */
data class VehicleInteraction(val key: String, val value: Int?, val minimum: Int = 0, val maximum: Int = 0,
    val status: ReadStatus = ReadStatus.AVAILABLE, val detail: String = "", val reading: VehicleReading? = null)

interface InteractionObserver {
    // Register only read listeners and return an idempotent cleanup handle owned by the foreground vehicle session.
    suspend fun observeInteractions(onEvent: (VehicleInteraction) -> Unit): Closeable
}
