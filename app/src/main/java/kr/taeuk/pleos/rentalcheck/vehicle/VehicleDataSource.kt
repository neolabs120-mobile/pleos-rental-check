package kr.taeuk.pleos.rentalcheck.vehicle

import kr.taeuk.pleos.rentalcheck.model.VehicleSnapshot

interface VehicleDataSource {
    /** initialize() returning does not prove the service is connected. */
    fun initialize()
    suspend fun readSnapshot(): VehicleSnapshot
    // A small fresh identity/gear probe supports stationary interactions without polling every diagnostic property.
    suspend fun readStationarySnapshot(): VehicleSnapshot = readSnapshot()
    fun close()
}
