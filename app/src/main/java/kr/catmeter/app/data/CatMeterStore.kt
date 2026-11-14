package kr.catmeter.app.data

import android.content.Context
import kr.catmeter.app.trip.Accessory
import kr.catmeter.app.trip.CatProfile
import kr.catmeter.app.trip.CommuteCheck
import kr.catmeter.app.trip.CommuteWindow
import kr.catmeter.app.trip.EnergyKind
import kr.catmeter.app.trip.EnergyProfile
import kr.catmeter.app.trip.GeoPoint
import kr.catmeter.app.trip.JoinPoint
import kr.catmeter.app.trip.Fur
import kr.catmeter.app.trip.TollEvent
import kr.catmeter.app.trip.TripRecord
import kr.catmeter.app.trip.TripTracker
import org.json.JSONArray
import org.json.JSONObject

/** 고양이 설정·동승자·이동 기록을 기기 안(SharedPreferences)에만 저장한다. 서버 전송 없음. */
class CatMeterStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("cat_meter", Context.MODE_PRIVATE)

    fun loadCat(): CatProfile = prefs.getString(KEY_CAT, null)?.let { runCatching { catFrom(JSONObject(it)) }.getOrNull() }
        ?: CatProfile()

    fun saveCat(cat: CatProfile) = prefs.edit().putString(KEY_CAT, catJson(cat).toString()).apply()

    fun loadPassengers(): List<String> = prefs.getString(KEY_PASSENGERS, null)
        ?.let { runCatching { JSONArray(it).strings() }.getOrNull() } ?: emptyList()

    fun savePassengers(names: List<String>) =
        prefs.edit().putString(KEY_PASSENGERS, JSONArray(names).toString()).apply()

    /** 최신순 */
    fun loadTrips(): List<TripRecord> = prefs.getString(KEY_TRIPS, null)?.let { raw ->
        runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i -> runCatching { tripFrom(arr.getJSONObject(i)) }.getOrNull() }
        }.getOrNull()
    } ?: emptyList()

    fun addTrip(trip: TripRecord) {
        val trips = (listOf(trip) + loadTrips().filter { it.id != trip.id }).take(MAX_TRIPS)
        prefs.edit().putString(KEY_TRIPS, JSONArray(trips.map(::tripJson)).toString()).apply()
    }

    /** 같은 id 의 기록을 바꾼다 (정산 완료 표시) */
    fun updateTrip(trip: TripRecord) {
        val trips = loadTrips().map { if (it.id == trip.id) trip else it }
        prefs.edit().putString(KEY_TRIPS, JSONArray(trips.map(::tripJson)).toString()).apply()
    }

    private fun catJson(c: CatProfile) = JSONObject()
        .put("name", c.name)
        .put("fur", c.fur.name)
        .put("accessories", JSONArray(c.accessories.map { it.name }))

    private fun catFrom(o: JSONObject) = CatProfile(
        name = o.optString("name", "나비"),
        fur = runCatching { Fur.valueOf(o.getString("fur")) }.getOrDefault(Fur.CHEESE),
        accessories = o.optJSONArray("accessories")?.strings()
            ?.mapNotNull { runCatching { Accessory.valueOf(it) }.getOrNull() }?.toSet() ?: emptySet(),
    )

    private fun tripJson(t: TripRecord) = JSONObject()
        .put("id", t.id)
        .put("startedAt", t.startedAtMs)
        .put("endedAt", t.endedAtMs)
        .put("distanceKm", t.distanceKm.toDouble())
        .put("movingMs", t.movingMs)
        .put("stoppedMs", t.stoppedMs)
        .put("stopCount", t.stopCount)
        .put("distanceSource", t.distanceSource.name)
        .put("cat", catJson(t.cat))
        .put("passengers", JSONArray(t.passengers))
        .put("rideNumber", t.rideNumber)
        .put("energy", JSONObject()
            .put("kind", t.energy.kind.name)
            .put("kmPerUnit", t.energy.kmPerUnit.toDouble())
            .put("wonPerUnit", t.energy.wonPerUnit)
            .put("fromVehicle", t.energy.fromVehicle))
        .put("destinationName", t.destinationName)
        .put("from", t.from?.let(::pointJson))
        .put("to", t.to?.let(::pointJson))
        .put("commute", JSONObject()
            .put("eligible", t.commute.eligible)
            .put("window", t.commute.window?.name)
            .put("reasons", JSONArray(t.commute.reasons)))
        .put("settledAt", t.settledAtMs)
        .put("joins", pointsJson(t.joins))
        .put("leaves", pointsJson(t.leaves))
        .put("notSharing", JSONArray(t.notSharing.toList()))
        .put("tolls", JSONArray().apply {
            t.tolls.forEach { put(JSONObject().put("km", it.point.distanceKm.toDouble()).put("ms", it.point.elapsedMs).put("won", it.won)) }
        })

    private fun tripFrom(o: JSONObject) = TripRecord(
        id = o.getLong("id"),
        startedAtMs = o.getLong("startedAt"),
        endedAtMs = o.getLong("endedAt"),
        distanceKm = o.getDouble("distanceKm").toFloat(),
        movingMs = o.optLong("movingMs"),
        stoppedMs = o.optLong("stoppedMs"),
        stopCount = o.optInt("stopCount"),
        distanceSource = runCatching { TripTracker.DistanceSource.valueOf(o.getString("distanceSource")) }
            .getOrDefault(TripTracker.DistanceSource.SPEED_ESTIMATE),
        cat = catFrom(o.getJSONObject("cat")),
        passengers = o.optJSONArray("passengers")?.strings() ?: emptyList(),
        rideNumber = o.optInt("rideNumber", 1),
        energy = o.optJSONObject("energy")?.let { e ->
            runCatching {
                EnergyProfile(
                    kind = EnergyKind.valueOf(e.getString("kind")),
                    kmPerUnit = e.getDouble("kmPerUnit").toFloat(),
                    wonPerUnit = e.getInt("wonPerUnit"),
                    fromVehicle = e.optBoolean("fromVehicle"),
                )
            }.getOrNull()
        } ?: EnergyProfile.DEFAULT_FUEL,
        destinationName = o.optString("destinationName").ifEmpty { null },
        from = o.optJSONObject("from")?.let(::pointFrom),
        to = o.optJSONObject("to")?.let(::pointFrom),
        commute = o.optJSONObject("commute")?.let { c ->
            CommuteCheck(
                eligible = c.optBoolean("eligible"),
                window = c.optString("window").ifEmpty { null }?.let { runCatching { CommuteWindow.valueOf(it) }.getOrNull() },
                reasons = c.optJSONArray("reasons")?.strings() ?: emptyList(),
            )
        } ?: CommuteCheck(false, null, emptyList()),
        settledAtMs = if (o.has("settledAt") && !o.isNull("settledAt")) o.getLong("settledAt") else null,
        joins = pointsFrom(o.optJSONObject("joins")),
        leaves = pointsFrom(o.optJSONObject("leaves")),
        notSharing = o.optJSONArray("notSharing")?.strings()?.toSet() ?: emptySet(),
        tolls = o.optJSONArray("tolls")?.let { a ->
            List(a.length()) { i ->
                val e = a.getJSONObject(i)
                TollEvent(JoinPoint(e.getDouble("km").toFloat(), e.getLong("ms")), e.getInt("won"))
            }
        } ?: emptyList(),
    )

    private fun pointsJson(points: Map<String, JoinPoint>) = JSONObject().apply {
        points.forEach { (name, p) -> put(name, JSONObject().put("km", p.distanceKm.toDouble()).put("ms", p.elapsedMs)) }
    }

    private fun pointsFrom(o: JSONObject?): Map<String, JoinPoint> = o?.let { j ->
        j.keys().asSequence().mapNotNull { name ->
            j.optJSONObject(name)?.let { p -> name to JoinPoint(p.optDouble("km").toFloat(), p.optLong("ms")) }
        }.toMap()
    } ?: emptyMap()

    private fun pointJson(p: GeoPoint) = JSONObject().put("lat", p.lat).put("lon", p.lon)
    private fun pointFrom(o: JSONObject) = GeoPoint(o.getDouble("lat"), o.getDouble("lon"))

    private fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }

    companion object {
        private const val KEY_CAT = "cat"
        private const val KEY_PASSENGERS = "passengers"
        private const val KEY_TRIPS = "trips"
        private const val MAX_TRIPS = 50
    }
}
