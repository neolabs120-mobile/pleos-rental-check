package kr.taeuk.pleos.rentalcheck.cloud

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kr.taeuk.pleos.rentalcheck.data.InspectionStore
import kr.taeuk.pleos.rentalcheck.model.InspectionRecord
import kr.taeuk.pleos.rentalcheck.BuildConfig
import org.json.JSONObject

/** Synchronous transport; controller calls these methods on Dispatchers.IO. */
class RentalServer(private val settings: () -> ServerConfig, private val store: InspectionStore) {
    fun sync(record: InspectionRecord): CloudSummary {
        val body = JSONObject().put("id", record.id).put("revision", record.revision)
            .put("pickup", store.encode(record.pickup)).put("returned", record.returned?.let(store::encode) ?: JSONObject.NULL)
            .put("pickupWindowManuallyChecked", record.pickupWindowManuallyChecked)
            .put("returnWindowManuallyChecked", record.returnWindowManuallyChecked)
            .put("bookingId", record.bookingId ?: JSONObject.NULL)
            .put("usageMemory", kr.taeuk.pleos.rentalcheck.data.UsageMemoryCodec.encode(record.usageMemory))
        return parseSummary(call("PUT", "/api/rentals/${record.id}", body), record.id)
    }

    fun summary(id: String) = parseSummary(call("GET", "/api/rentals/$id"), id)
    fun submit(record: InspectionRecord) = parseSummary(call("POST", "/api/rentals/${record.id}/submit",
        JSONObject().put("revision", record.revision)), record.id)

    fun photoLink(id: String, phase: String): PhotoLink {
        require(phase == "pickup" || phase == "return")
        val response = call("POST", "/api/rentals/$id/photo-links", JSONObject().put("phase", phase))
        val url = response.getString("uploadUrl")
        require(URL(url).protocol == "https" || (BuildConfig.DEBUG && URL(url).protocol == "http")) {
            "HTTPS 사진 전송 링크가 필요해요. 서버 공개 주소 설정을 확인해 주세요."
        }
        return PhotoLink(url, response.getLong("expiresAtMillis"), response.getString("phase"))
    }

    fun createPairing(vin: String, replaceId: String?): VehiclePairing {
        val response = call("POST", "/api/device/pairings", JSONObject().put("vin", vin)
            .put("replaceId", replaceId ?: JSONObject.NULL))
        val url = response.getString("pairingUrl")
        require(URL(url).protocol == "https" || (BuildConfig.DEBUG && URL(url).protocol == "http")) {
            "HTTPS 차량 연결 링크가 필요해요."
        }
        return VehiclePairing(response.getString("id"), url, response.getLong("expiresAtMillis"))
    }

    fun pairingStatus(id: String): PairingStatus {
        val json = call("GET", "/api/device/pairings/$id")
        return parsePairingStatus(json)
    }

    fun disconnectPairing(id: String) { call("DELETE", "/api/device/pairings/$id") }

    private fun call(method: String, path: String, body: JSONObject? = null): JSONObject {
        val config = settings()
        check(config.configured) { "먼저 서버 설정을 완료해 주세요." }
        val connection = URL(config.baseUrl + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 10_000
            connection.readTimeout = 20_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Authorization", "Bearer ${config.deviceToken}")
            connection.setRequestProperty("Accept", "application/json")
            body?.let {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { stream -> stream.write(it.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val output = ByteArrayOutputStream()
            stream?.use {
                val buffer = ByteArray(8192)
                while (true) {
                    val count = it.read(buffer)
                    if (count < 0) break
                    check(output.size() + count <= 1_000_000) { "서버 응답 크기가 너무 큽니다." }
                    output.write(buffer, 0, count)
                }
            }
            val response = runCatching { JSONObject(output.toString("UTF-8")) }.getOrNull()
            check(code in 200..299) { response?.optString("detail")?.take(240)?.takeIf { it.isNotBlank() }
                ?: "서버 요청을 완료하지 못했어요. (HTTP $code)" }
            return checkNotNull(response) { "서버 응답 형식을 확인해 주세요." }
        } finally { connection.disconnect() }
    }

    companion object {
        fun parsePairingStatus(json: JSONObject): PairingStatus {
            val status = json.getString("status")
            require(status in listOf("connected", "waiting", "expired", "disconnected")) { "알 수 없는 차량 연결 상태입니다." }
            return PairingStatus(status, if (status == "connected") PairedBooking(json.getString("bookingId"),
                json.getString("bookingNumber"), json.getString("nickname"), json.getString("vin").also {
                    require(kr.taeuk.pleos.rentalcheck.model.VehicleIdentity.normalize(it) == it) { "연결 차량 VIN 형식이 올바르지 않습니다." }
                }) else null)
        }
        fun parseSummary(json: JSONObject, expectedId: String): CloudSummary {
            require(json.getString("id") == expectedId) { "다른 이용 기록의 서버 응답입니다." }
            val photos = json.getJSONArray("photos")
            val checks = json.optJSONObject("checks")?.optJSONObject("return")
            return CloudSummary(expectedId, json.getInt("revision"), (0 until photos.length()).map { index ->
                val photo = photos.getJSONObject(index)
                EvidencePhoto(photo.getString("id"), photo.getString("phase"), photo.getString("category"))
            }, EvidenceChecks(checks?.optBoolean("windows") == true, checks?.optBoolean("belongings") == true,
                checks?.optBoolean("exterior") == true, checks?.optString("note").orEmpty()),
                json.optLong("submittedAtMillis", 0).takeIf { it > 0 },
                if (json.isNull("receiptId")) null else json.getString("receiptId"))
        }
    }
}
