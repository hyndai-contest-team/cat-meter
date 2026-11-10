package kr.catmeter.app

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kr.catmeter.app.car.AaosMonitor
import kr.catmeter.app.car.NaviSource
import kr.catmeter.app.car.PleosVehicleSource
import kr.catmeter.app.data.CatMeterStore
import kr.catmeter.app.trip.Accessory
import kr.catmeter.app.trip.Boarding
import kr.catmeter.app.trip.CatProfile
import kr.catmeter.app.trip.CommuteRule
import kr.catmeter.app.trip.EnergyProfile
import kr.catmeter.app.trip.GeoPoint
import kr.catmeter.app.trip.Fur
import kr.catmeter.app.trip.TripRecord
import kr.catmeter.app.trip.TripTracker
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 앱 프로세스 단위로 살아 있는 이동 세션.
 * 분할 화면에서 창을 바꾸거나 액티비티가 다시 만들어져도 기록이 이어지도록 Application 이 들고 있는다.
 * (앱 프로세스가 종료되면 진행 중 기록은 사라진다. 주행 중 백그라운드 기록 허용 여부는 확인 필요 항목.)
 */
class CatMeterSession(context: Context) {

    enum class Phase { SETUP, DRIVING, ARRIVED }

    data class State(
        val phase: Phase = Phase.SETUP,
        val cat: CatProfile = CatProfile(),
        val passengers: List<String> = emptyList(),
        val pastRides: Int = 0,
        val live: TripTracker.Snapshot? = null,
        val record: TripRecord? = null,
        /** 주행 후 P 단으로 바뀌면 도착 확인을 띄운다 */
        val arrivalSuggested: Boolean = false,
        /** 지금 쓰는 에너지 비용 기준 (차량 값이 없으면 기본값) */
        val energy: EnergyProfile = EnergyProfile.DEFAULT_FUEL,
        /** 출발할 때 잡힌 내비 목적지 이름 */
        val destinationName: String? = null,
        /** 출발 버튼 없이 내비 안내 + 주행으로 자동 시작했는지 */
        val autoStarted: Boolean = false,
        /** 이번 이동의 탑승 QR 코드. 이동이 끝나면 새로 만든다. */
        val boardingCode: String = Boarding.newCode(),
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val store = CatMeterStore(context)
    val aaos = AaosMonitor(context)
    val pleos = PleosVehicleSource(context, scope)
    val navi = NaviSource(context, scope)

    private val _state = MutableStateFlow(
        State(
            cat = store.loadCat(),
            passengers = store.loadPassengers(),
            pastRides = store.loadTrips().size,
        )
    )
    val state: StateFlow<State> = _state.asStateFlow()

    private var tracker: TripTracker? = null
    private var tripFrom: GeoPoint? = null
    private var autoFinishJob: Job? = null
    private var autoStartJob: Job? = null
    private var tickJob: Job? = null

    init {
        pleos.start()
        aaos.start()
        navi.start()
        scope.launch {
            navi.routeEnded.collect { reason ->
                if (_state.value.phase != Phase.DRIVING) return@collect
                // 목적지를 바꾸면 '취소 → 새 경로 시작' 순으로 올 수 있어 잠시 기다렸다가 새 경로가 없을 때만 끝낸다.
                autoFinishJob?.cancel()
                autoFinishJob = scope.launch {
                    delay(AUTO_FINISH_GRACE_MS)
                    if (_state.value.phase == Phase.DRIVING && navi.destination.value == null) {
                        Log.i(TAG, "auto finish trip: navi route $reason")
                        finishTrip()
                    }
                }
            }
        }
        // 출발을 누르지 않고 떠나는 경우: 내비 안내 중에 차가 [AUTO_START_MS] 동안 계속 움직이면 자동으로 시작한다.
        scope.launch {
            combine(aaos.speed, navi.destination) { s, d -> ((s as? AaosMonitor.Speed.Live)?.kph ?: 0f) to d }
                .collect { (kph, dest) ->
                    if (!canAutoStart(kph, dest != null)) {
                        autoStartJob?.cancel()
                        autoStartJob = null
                    } else if (autoStartJob == null) {
                        autoStartJob = scope.launch {
                            delay(AUTO_START_MS)
                            // 속도 이벤트는 값이 바뀔 때만 오므로 시작 직전에 다시 확인한다.
                            if (canAutoStart(speedKph, navi.destination.value != null)) {
                                Log.i(TAG, "auto start trip: navi guidance + moving")
                                startTrip(auto = true)
                            }
                            autoStartJob = null
                        }
                    }
                }
        }
        scope.launch { pleos.energy.collect { e -> _state.update { it.copy(energy = e ?: EnergyProfile.DEFAULT_FUEL) } } }
        scope.launch { aaos.speed.collect { s -> if (s is AaosMonitor.Speed.Live) tracker?.onSpeed(s.kph, now()) } }
        scope.launch { pleos.odometerKm.collect { km -> if (km != null) tracker?.onOdometer(km, now()) } }
        scope.launch {
            var wasParked: Boolean? = null
            pleos.parked.collect { parked ->
                val t = tracker
                if (parked == true && wasParked == false && t != null && t.snapshot(now()).distanceKm > 0.01f) {
                    _state.update { it.copy(arrivalSuggested = true) }
                }
                wasParked = parked
            }
        }
    }

    /** 권한 허용 후 다시 호출 */
    fun restartSpeed() = aaos.start()

    val speedKph: Float get() = (aaos.speed.value as? AaosMonitor.Speed.Live)?.kph ?: 0f

    // ── 출발 전 설정 ──────────────────────────────────────────────
    fun setCatName(name: String) = updateCat { it.copy(name = name.take(12)) }
    fun setFur(fur: Fur) = updateCat { it.copy(fur = fur) }
    fun toggleAccessory(a: Accessory) = updateCat {
        it.copy(accessories = if (a in it.accessories) it.accessories - a else it.accessories + a)
    }

    fun addPassenger(name: String) {
        val n = name.trim().take(10)
        if (n.isEmpty() || n in _state.value.passengers || _state.value.passengers.size >= MAX_PASSENGERS) return
        _state.update { it.copy(passengers = it.passengers + n) }
        store.savePassengers(_state.value.passengers)
    }

    fun removePassenger(name: String) {
        _state.update { it.copy(passengers = it.passengers - name) }
        store.savePassengers(_state.value.passengers)
    }

    private fun updateCat(f: (CatProfile) -> CatProfile) {
        _state.update { it.copy(cat = f(it.cat)) }
        store.saveCat(_state.value.cat)
    }

    // ── 이동 ─────────────────────────────────────────────────────
    /** 출발 전·도착 화면에서, 내비 안내 중이고 차가 움직이고 있을 때 */
    private fun canAutoStart(kph: Float, guiding: Boolean): Boolean =
        _state.value.phase != Phase.DRIVING && guiding && kph > TripTracker.MOVING_KPH

    fun startTrip() = startTrip(auto = false)

    private fun startTrip(auto: Boolean) {
        if (_state.value.phase == Phase.DRIVING) return
        autoStartJob?.cancel()
        autoStartJob = null
        if (_state.value.cat.name.isBlank()) setCatName("나비")
        val t = TripTracker(startedAtMs = now(), startOdometerKm = pleos.odometerKm.value)
        t.onSpeed(speedKph, now())
        tracker = t
        tripFrom = navi.location.value
        pleos.refreshEnergy()
        navi.refreshLocation()
        _state.update {
            it.copy(
                phase = Phase.DRIVING, live = t.snapshot(now()), record = null, arrivalSuggested = false,
                destinationName = navi.destination.value?.name,
                autoStarted = auto,
            )
        }
        tickJob?.cancel()
        tickJob = scope.launch {
            while (isActive) {
                // 속도 이벤트는 값이 바뀔 때만 오므로, 일정 속도 구간도 적분되게 1초마다 현재 속도를 넣는다.
                tracker?.let { tr ->
                    tr.onSpeed(speedKph, now())
                    _state.update { it.copy(live = tr.snapshot(now())) }
                }
                delay(1_000L)
            }
        }
    }

    fun dismissArrival() = _state.update { it.copy(arrivalSuggested = false) }

    fun finishTrip() {
        val t = tracker ?: return
        tickJob?.cancel()
        val end = now()
        t.onSpeed(speedKph, end)
        val s = t.snapshot(end)
        val st = _state.value
        val to = navi.location.value
        val start = toLocal(s.startedAtMs)
        val window = CommuteRule.windowOf(start)
        val alreadySettled = window != null && store.loadTrips().any {
            it.settledAtMs != null && it.commute.window == window && toLocal(it.startedAtMs).toLocalDate() == start.toLocalDate()
        }
        val record = TripRecord(
            id = end,
            startedAtMs = s.startedAtMs,
            endedAtMs = end,
            distanceKm = s.distanceKm,
            movingMs = s.movingMs,
            stoppedMs = s.stoppedMs,
            stopCount = s.stopCount,
            distanceSource = s.distanceSource,
            cat = st.cat,
            passengers = st.passengers,
            rideNumber = st.pastRides + 1,
            energy = st.energy,
            destinationName = st.destinationName ?: navi.destination.value?.name,
            from = tripFrom,
            to = to,
            commute = CommuteRule.check(
                start = start,
                end = toLocal(end),
                from = tripFrom,
                to = to,
                home = navi.home.value,
                office = navi.office.value,
                alreadySettledInWindow = alreadySettled,
            ),
        )
        store.addTrip(record)
        tracker = null
        _state.update {
            it.copy(phase = Phase.ARRIVED, record = record, live = null, pastRides = it.pastRides + 1, arrivalSuggested = false)
        }
    }

    /** 출퇴근 함께 타기로 판정된 이동의 모의 결제를 마친다 */
    fun markSettled() {
        val r = _state.value.record ?: return
        if (!r.commute.eligible || r.settledAtMs != null) return
        val settled = r.copy(settledAtMs = now())
        store.updateTrip(settled)
        _state.update { it.copy(record = settled) }
    }

    /** 영수증을 닫고 다음 이동을 준비한다 */
    fun backToSetup() = _state.update {
        it.copy(phase = Phase.SETUP, record = null, destinationName = null, boardingCode = Boarding.newCode())
    }

    private fun toLocal(ms: Long): LocalDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.of("Asia/Seoul"))

    private fun now() = System.currentTimeMillis()

    companion object {
        const val MAX_PASSENGERS = 7
        private const val TAG = "CatMeterSession"
        /** 내비 안내 종료 후 자동 도착까지 기다리는 시간 */
        private const val AUTO_FINISH_GRACE_MS = 3_000L
        /** 내비 안내 중 이만큼 계속 움직이면 자동 시작 */
        const val AUTO_START_MS = 3_000L
    }
}
