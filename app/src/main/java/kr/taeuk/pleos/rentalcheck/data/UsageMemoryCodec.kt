package kr.taeuk.pleos.rentalcheck.data

import kr.taeuk.pleos.rentalcheck.model.StorageSpace
import kr.taeuk.pleos.rentalcheck.model.UsageMemory
import org.json.JSONArray
import org.json.JSONObject

object UsageMemoryCodec {
    // Keep the same small, explicit schema in local storage and server uploads without storing arbitrary object descriptions.
    fun encode(memory: UsageMemory): JSONObject = JSONObject()
        .put("openedSpaces", JSONArray(memory.openedSpaces.map { it.name }.sorted()))
        .put("markedSpaces", JSONArray(memory.markedSpaces.map { it.name }.sorted()))
        .put("monitoringGaps", memory.monitoringGaps)

    // Older records have no memory; unknown location names are discarded rather than attributed to a different compartment.
    fun decode(json: JSONObject?): UsageMemory = UsageMemory(spaces(json?.optJSONArray("openedSpaces")),
        spaces(json?.optJSONArray("markedSpaces")), json?.optBoolean("monitoringGaps", true) ?: true)

    // Accept only the fixed location vocabulary shared by the vehicle, server and Flutter clients.
    private fun spaces(array: JSONArray?): Set<StorageSpace> = (0 until (array?.length() ?: 0)).mapNotNull { index ->
        StorageSpace.entries.firstOrNull { it.name == array?.optString(index) }
    }.toSet()
}
