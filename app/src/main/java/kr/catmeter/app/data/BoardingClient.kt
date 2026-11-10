package kr.catmeter.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kr.catmeter.app.trip.SeatPosition
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 탑승 등록 서버(server/) 와 주고받는다.
 * - 탑승 QR 을 띄울 때 이동을 열고, 승객 휴대폰이 열 주소와 차량 전용 토큰을 받는다.
 * - QR 화면이 떠 있는 동안 탑승자 목록(출퇴근 여부, 결제 수단)을 받아 온다.
 * - 도착하면 사람별 금액을 자동결제한다. 지금은 서버가 테스트 결제만 한다 (실제 출금 없음).
 * 보내는 정보는 탑승 코드, 고양이 이름, 탑승자 이름과 금액뿐이다.
 */
class BoardingClient(private val baseUrl: String) {

    class ServerError(message: String) : IOException(message)

    data class Opened(val joinUrl: String, val ownerToken: String)

    data class Rider(
        val name: String,
        /** 승객이 휴대폰에서 '출퇴근 길이에요'를 골랐는지 */
        val commuting: Boolean,
        /** 등록한 결제 수단 이름. 없으면 null */
        val paymentLabel: String?,
        /** 좌석 QR 로 탔으면 그 자리 */
        val seat: SeatPosition? = null,
    )

    data class Charge(val name: String, val amount: Int, val status: Status, val methodLabel: String?) {
        enum class Status { PAID, NO_METHOD, NOT_COMMUTING, NOT_BOARDED }
    }

    /** 이동을 연다. 이미 연 이동을 다시 열 때는 처음 받은 토큰을 보낸다. */
    suspend fun openRide(code: String, catName: String, ownerToken: String?): Opened = withContext(Dispatchers.IO) {
        val body = JSONObject().put("catName", catName).toString()
        val json = request("PUT", "/api/rides/$code", body, ownerToken)
        Opened(json.getString("joinUrl"), json.getString("ownerToken"))
    }

    /** 휴대폰으로 탑승 등록한 사람 (등록한 순서) */
    suspend fun riders(code: String): List<Rider> = withContext(Dispatchers.IO) {
        val list = request("GET", "/api/rides/$code", null, null).getJSONArray("passengers")
        List(list.length()) { i ->
            val p = list.getJSONObject(i)
            Rider(
                name = p.getString("name"),
                commuting = p.optBoolean("commuting", false),
                paymentLabel = p.optString("paymentLabel").takeUnless { p.isNull("paymentLabel") || it.isBlank() },
                seat = SeatPosition.ofName(p.optString("seat").takeUnless { p.isNull("seat") }),
            )
        }
    }

    /** 도착 후 사람별 금액을 결제한다. 같은 tripId 로 다시 부르면 서버가 지난 결과를 돌려준다 (두 번 결제하지 않는다). */
    suspend fun charge(code: String, ownerToken: String, tripId: String, amounts: Map<String, Int>): List<Charge> =
        withContext(Dispatchers.IO) {
            val items = JSONArray()
            amounts.forEach { (name, amount) -> items.put(JSONObject().put("name", name).put("amount", amount)) }
            val body = JSONObject().put("tripId", tripId).put("items", items).toString()
            val list = request("POST", "/api/rides/$code/charges", body, ownerToken).getJSONArray("charges")
            List(list.length()) { i ->
                val c = list.getJSONObject(i)
                Charge(
                    name = c.getString("name"),
                    amount = c.getInt("amount"),
                    status = when (c.getString("status")) {
                        "paid" -> Charge.Status.PAID
                        "no_method" -> Charge.Status.NO_METHOD
                        "not_commuting" -> Charge.Status.NOT_COMMUTING
                        else -> Charge.Status.NOT_BOARDED
                    },
                    methodLabel = c.optString("methodLabel").takeUnless { c.isNull("methodLabel") || it.isBlank() },
                )
            }
        }

    private fun request(method: String, path: String, body: String?, ownerToken: String?): JSONObject {
        val conn = URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.setRequestProperty("Accept", "application/json")
            if (ownerToken != null) conn.setRequestProperty("Authorization", "Bearer $ownerToken")
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val ok = conn.responseCode in 200..299
            val text = (if (ok) conn.inputStream else conn.errorStream)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val json = runCatching { JSONObject(text) }.getOrNull()
            if (!ok) throw ServerError(json?.optString("message").takeUnless { it.isNullOrBlank() } ?: "서버 응답 ${conn.responseCode}")
            return json ?: throw ServerError("서버 응답을 읽을 수 없어요")
        } finally {
            conn.disconnect()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 3_000
    }
}
