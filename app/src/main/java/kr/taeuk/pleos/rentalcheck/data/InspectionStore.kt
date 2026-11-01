package kr.taeuk.pleos.rentalcheck.data

import android.content.Context
import kr.taeuk.pleos.rentalcheck.model.InspectionRecord
import kr.taeuk.pleos.rentalcheck.model.ReadStatus
import kr.taeuk.pleos.rentalcheck.model.VehicleReading
import kr.taeuk.pleos.rentalcheck.model.VehicleSnapshot
import org.json.JSONArray
import org.json.JSONObject

/** Latest temporary record only. Account ownership and receipts live on the server. */
class InspectionStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("rental_inspection", Context.MODE_PRIVATE)

    fun load(): InspectionRecord? {
        val text = preferences.getString("record", null) ?: return null
        val root = JSONObject(text)
        require(root.getInt("schemaVersion") in 1..4) { "지원하지 않는 저장 형식입니다." }
        return InspectionRecord(
            root.getString("id"),
            decode(root.getJSONObject("pickup")),
            if (root.isNull("returned")) null else decode(root.getJSONObject("returned")),
            root.optBoolean("pickupWindowManuallyChecked", false),
            root.optBoolean("returnWindowManuallyChecked", false),
            root.optInt("revision", 1), root.optInt("syncedRevision", 0),
            root.optLong("submittedAtMillis", 0).takeIf { it > 0 },
            if (root.isNull("receiptId")) null else root.getString("receiptId"),
            if (root.isNull("bookingId")) null else root.getString("bookingId"),
            UsageMemoryCodec.decode(root.optJSONObject("usageMemory")),
        )
    }

    fun save(record: InspectionRecord) {
        val root = JSONObject().put("schemaVersion", 4).put("id", record.id)
            .put("pickup", encode(record.pickup))
            .put("returned", record.returned?.let(::encode) ?: JSONObject.NULL)
            .put("pickupWindowManuallyChecked", record.pickupWindowManuallyChecked)
            .put("returnWindowManuallyChecked", record.returnWindowManuallyChecked)
            .put("revision", record.revision).put("syncedRevision", record.syncedRevision)
            .put("submittedAtMillis", record.submittedAtMillis ?: JSONObject.NULL)
            .put("receiptId", record.receiptId ?: JSONObject.NULL)
            .put("bookingId", record.bookingId ?: JSONObject.NULL)
            .put("usageMemory", UsageMemoryCodec.encode(record.usageMemory))
        check(preferences.edit().putString("record", root.toString()).commit()) { "점검 기록을 저장하지 못했습니다." }
    }

    fun clear() { check(preferences.edit().remove("record").commit()) { "임시 기록을 정리하지 못했어요." } }

    fun encode(snapshot: VehicleSnapshot): JSONObject {
        val readings = JSONArray()
        snapshot.readings.forEach { field ->
            readings.put(JSONObject().put("key", field.key).put("label", field.label)
                .put("status", field.status.name).put("value", field.value ?: JSONObject.NULL)
                .put("displayValue", field.displayValue).put("unit", field.unit ?: JSONObject.NULL)
                .put("detail", field.detail).put("observedAtMillis", field.observedAtMillis))
        }
        return JSONObject().put("startedAtMillis", snapshot.startedAtMillis)
            .put("completedAtMillis", snapshot.completedAtMillis).put("source", snapshot.source)
            .put("readings", readings)
    }

    private fun decode(snapshot: JSONObject): VehicleSnapshot {
        val fields = snapshot.getJSONArray("readings")
        return VehicleSnapshot(
            snapshot.getLong("startedAtMillis"), snapshot.getLong("completedAtMillis"),
            (0 until fields.length()).map { index ->
                val field = fields.getJSONObject(index)
                VehicleReading(field.getString("key"), field.getString("label"),
                    ReadStatus.valueOf(field.getString("status")),
                    if (field.isNull("value")) null else field.getString("value"),
                    field.getString("displayValue"),
                    if (field.isNull("unit")) null else field.getString("unit"),
                    field.getString("detail"), field.getLong("observedAtMillis"))
            }, snapshot.getString("source"),
        )
    }
}
