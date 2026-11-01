package kr.taeuk.pleos.rentalcheck.vehicle

import kr.taeuk.pleos.rentalcheck.model.VehicleSnapshot

interface VehicleDataSource {
    /** initialize() returning does not prove the service is connected. */
    fun initialize()
    suspend fun readSnapshot(): VehicleSnapshot
    fun close()
}
