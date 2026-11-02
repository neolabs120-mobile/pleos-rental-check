package kr.taeuk.pleos.rentalcheck.data

import kr.taeuk.pleos.rentalcheck.model.StorageSpace
import kr.taeuk.pleos.rentalcheck.model.UsageMemory
import kr.taeuk.pleos.rentalcheck.model.LuggageMemo
import org.json.JSONArray
import org.json.JSONObject

object UsageMemoryCodec {
    // Serialize only reviewed, bounded item notes alongside the existing observation history; audio and raw transcripts are never persisted.
    fun encode(memory: UsageMemory): JSONObject = JSONObject()
        .put("openedSpaces", JSONArray(memory.openedSpaces.map { it.name }.sorted()))
        .put("markedSpaces", JSONArray(memory.markedSpaces.map { it.name }.sorted()))
        .put("monitoringGaps", memory.monitoringGaps)
        .put("luggageMemos", JSONArray(memory.luggageMemos.map { JSONObject().put("space", it.space.name).put("item", it.item) }))

    // Older records have no memory; unknown location names are discarded rather than attributed to a different compartment.
    fun decode(json: JSONObject?): UsageMemory = UsageMemory(spaces(json?.optJSONArray("openedSpaces")),
        spaces(json?.optJSONArray("markedSpaces")), json?.optBoolean("monitoringGaps", true) ?: true, memos(json?.optJSONArray("luggageMemos")))

    // Discard malformed or duplicate historical notes without assigning them to another place or breaking older records.
    private fun memos(array: JSONArray?): List<LuggageMemo> = (0 until minOf(array?.length() ?: 0, 5)).mapNotNull { index ->
        runCatching { val note = array!!.getJSONObject(index); LuggageMemo(StorageSpace.valueOf(note.getString("space")), note.getString("item")) }.getOrNull()
    }.distinctBy { it.space }

    // Accept only the fixed location vocabulary shared by the vehicle, server and Flutter clients.
    private fun spaces(array: JSONArray?): Set<StorageSpace> = (0 until (array?.length() ?: 0)).mapNotNull { index ->
        StorageSpace.entries.firstOrNull { it.name == array?.optString(index) }
    }.toSet()
}
