package kr.catmeter.app.car

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.Door
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.capability.AreaBounds
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.listener.DoorPositionListener
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kr.catmeter.app.trip.DoorPosition
import kr.catmeter.app.trip.DoorSignal

/**
 * 동승자 쪽 문의 열림 (Pleos Vehicle SDK Door 위치, 권한 CAR_DOORS).
 * 운전석 문은 빼고, 차량이 지원한다고 알려 준 문만 구독한다. 문 위치 값이 닫힘(최솟값)보다 크면 열린 것으로 본다.
 * 문이 잠깐 열렸다 닫혀도 놓치지 않게, 닫힘에서 열림으로 바뀐 시각을 함께 기록한다.
 *
 * @param now 부팅 뒤 흐른 시간 (SystemClock.elapsedRealtime). 벽시계는 차량이 시간을 맞출 때 뛰므로 쓰지 않는다.
 */
class DoorSensor(context: Context, private val scope: CoroutineScope, private val now: () -> Long) {

    private val vehicle = Vehicle(context.applicationContext)

    private val _signals = MutableStateFlow<Map<DoorPosition, DoorSignal>>(emptyMap())
    /** 지원하는 문별 현재 신호. 지원하는 문이 없거나 연결에 실패하면 비어 있다. */
    val signals: StateFlow<Map<DoorPosition, DoorSignal>> = _signals.asStateFlow()

    private val listeners = mutableListOf<() -> Unit>()

    fun start() {
        scope.launch {
            val door = try {
                vehicle.initialize()
                vehicle.getDoor()
            } catch (t: Throwable) {
                Log.w(TAG, "door service unavailable: ${t.message}")
                return@launch
            }
            delay(CONNECT_WAIT_MS)   // 차량 서비스 연결을 기다린다 (SeatSensor 와 같은 이유)
            door.checkDoorPositionCapabilities(
                { _: CapabilityConfig, bounds: Map<DoorArea, AreaBounds<Int>> -> subscribe(door, bounds) },
                { e -> Log.w(TAG, "door capabilities: ${e.message}") },
            )
        }
    }

    private fun subscribe(door: Door, bounds: Map<DoorArea, AreaBounds<Int>>) {
        val closedAt = bounds.flatMap { (area, b) -> area.values.map { it to b.minValue } }.toMap()
        val doors = closedAt.keys.mapNotNull { v -> DoorPosition.ofName(v.name)?.let { it to v } }
        Log.i(TAG, "door positions: ${doors.map { it.first }}")
        // 지원한다고 알려 준 문은 첫 값이 오기 전에도 '닫힘'으로 넣어 둔다.
        // 비워 두면 그동안 '문 신호가 없는 차'로 보고 문을 열지 않아도 하차로 확정하게 된다.
        _signals.update { cur -> doors.associate { (p, _) -> p to (cur[p] ?: DoorSignal(open = false)) } + cur }
        doors.forEach { (p, value) ->
            val closed = closedAt[value] ?: 0
            val l = object : DoorPositionListener {
                override fun onPositionUpdated(pos: Int?) {
                    val open = pos != null && pos > closed
                    _signals.update { cur ->
                        val before = cur[p]
                        // 열려 있는 동안 위치 값만 바뀌는 것은 새로 연 것이 아니다
                        val opened = open && before?.open != true
                        cur + (p to DoorSignal(open, if (opened) now() else before?.lastOpenMs))
                    }
                    Log.d(TAG, "door $p = $pos (open=$open)")
                }
                override fun onFailed(e: Exception) = Log.w(TAG, "door $p: ${e.message}").let { }
            }
            runCatching { door.registerDoorPosition(DoorArea(value), l) }
                .onSuccess { listeners += { runCatching { door.unregisterDoorPosition(DoorArea(value), l) } } }
                .onFailure { Log.w(TAG, "register door $p: ${it.message}") }
        }
    }

    fun release() {
        listeners.forEach { it() }
        runCatching { vehicle.release() }
    }

    private companion object {
        const val TAG = "CatMeterDoor"
        const val CONNECT_WAIT_MS = 1_500L
    }
}
