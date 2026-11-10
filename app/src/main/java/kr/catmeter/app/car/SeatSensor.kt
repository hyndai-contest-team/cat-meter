package kr.catmeter.app.car

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.Seat
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.listener.SeatBeltBuckledStateListener
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kr.catmeter.app.trip.SeatPosition
import kr.catmeter.app.trip.SeatSignal

/**
 * 동승석 좌석 점유·안전벨트 착용 (Pleos Vehicle SDK Seat, 권한 CAR_SEATS).
 * 운전석은 빼고, 차량이 지원한다고 알려 준 좌석만 구독한다.
 *
 * @param invertOccupancy 좌석 점유 값을 뒤집는다. Pleos Connect 에뮬레이터 v2.1.0 에서
 *   Virtual Console 의 Seat Occupancy 와 SDK 값이 반대로 들어오는 것을 확인했다 (README '좌석 점유·안전벨트 API 실험').
 *   실차 값 방향은 확인 전이라 debug 빌드만 뒤집는다 (build.gradle.kts SEAT_OCCUPANCY_INVERTED).
 */
class SeatSensor(context: Context, private val scope: CoroutineScope, private val invertOccupancy: Boolean) {

    private val vehicle = Vehicle(context.applicationContext)
    private var seat: Seat? = null

    private val _signals = MutableStateFlow<Map<SeatPosition, SeatSignal>>(emptyMap())
    /** 지원하는 동승석별 현재 신호. 지원 좌석이 없거나 연결에 실패하면 비어 있다. */
    val signals: StateFlow<Map<SeatPosition, SeatSignal>> = _signals.asStateFlow()

    private val listeners = mutableListOf<() -> Unit>()

    fun start() {
        scope.launch {
            try {
                vehicle.initialize()
                seat = vehicle.getSeat()
            } catch (t: Throwable) {
                Log.w(TAG, "seat service unavailable: ${t.message}")
                return@launch
            }
            delay(CONNECT_WAIT_MS)   // 차량 서비스 연결을 기다린다 (PleosVehicleSource 와 같은 이유)
            val s = seat ?: return@launch
            s.checkSeatOccupancyCapabilities({ areas: List<SeatArea> -> subscribeOccupancy(s, areas.positions()) }, { e -> Log.w(TAG, "occupancy capabilities: ${e.message}") })
            s.checkSeatBeltBuckledCapabilities({ areas: List<SeatArea> -> subscribeBelt(s, areas.positions()) }, { e -> Log.w(TAG, "belt capabilities: ${e.message}") })
        }
    }

    private fun subscribeOccupancy(s: Seat, positions: List<SeatPosition>) {
        Log.i(TAG, "occupancy seats: $positions (inverted=$invertOccupancy)")
        positions.forEach { p ->
            val l = object : SeatOccupancyListener {
                override fun onSeatOccupancyUpdated(value: Boolean?) = update(p) { it.copy(occupied = value?.let { v -> v != invertOccupancy }) }
                override fun onFailed(e: Exception) = Log.w(TAG, "occupancy $p: ${e.message}").let { }
            }
            runCatching { s.registerSeatOccupancy(SeatArea(p.value), l) }
                .onSuccess { listeners += { runCatching { s.unregisterSeatOccupancy(SeatArea(p.value), l) } } }
                .onFailure { Log.w(TAG, "register occupancy $p: ${it.message}") }
        }
    }

    private fun subscribeBelt(s: Seat, positions: List<SeatPosition>) {
        Log.i(TAG, "belt seats: $positions")
        positions.forEach { p ->
            val l = object : SeatBeltBuckledStateListener {
                override fun onSeatBeltBuckledStateUpdated(value: Boolean?) = update(p) { it.copy(buckled = value) }
                override fun onFailed(e: Exception) = Log.w(TAG, "belt $p: ${e.message}").let { }
            }
            runCatching { s.registerSeatBeltBuckledState(SeatArea(p.value), l) }
                .onSuccess { listeners += { runCatching { s.unregisterSeatBeltBuckledState(SeatArea(p.value), l) } } }
                .onFailure { Log.w(TAG, "register belt $p: ${it.message}") }
        }
    }

    private fun update(p: SeatPosition, f: (SeatSignal) -> SeatSignal) {
        _signals.update { it + (p to f(it[p] ?: SeatSignal())) }
        Log.d(TAG, "signal $p = ${_signals.value[p]}")
    }

    private fun List<SeatArea>.positions(): List<SeatPosition> =
        flatMap { it.values.toList() }.mapNotNull { SeatPosition.ofName(it.name) }.filterNot { it.isDriver }.distinct()

    fun release() {
        listeners.forEach { it() }
        runCatching { vehicle.release() }
    }

    private companion object {
        const val TAG = "CatMeterSeat"
        const val CONNECT_WAIT_MS = 1_500L
    }
}

/** 앱의 좌석 이름 → SDK 좌석 값 (이름이 같다) */
private val SeatPosition.value: SeatValue get() = SeatValue.valueOf(name)
