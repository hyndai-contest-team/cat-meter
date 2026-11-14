package kr.catmeter.app

import android.content.Context
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
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
import kr.catmeter.app.car.DoorSensor
import kr.catmeter.app.car.SeatSensor
import kr.catmeter.app.data.BoardingClient
import kr.catmeter.app.data.CatMeterStore
import kr.catmeter.app.trip.Accessory
import kr.catmeter.app.trip.Boarding
import kr.catmeter.app.trip.CatProfile
import kr.catmeter.app.trip.CommuteCheck
import kr.catmeter.app.trip.CommuteRule
import kr.catmeter.app.trip.CommuteWindow
import kr.catmeter.app.trip.EnergyProfile
import kr.catmeter.app.trip.GeoPoint
import kr.catmeter.app.trip.AlightTracker
import kr.catmeter.app.trip.DoorSignal
import kr.catmeter.app.trip.doorSignal
import kr.catmeter.app.trip.JoinPoint
import kr.catmeter.app.trip.TripBill
import kr.catmeter.app.trip.SeatPosition
import kr.catmeter.app.trip.SeatPrompts
import kr.catmeter.app.trip.SeatSignal
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
        /** 탑승 등록 서버 연결 상태. QR 화면을 띄울 때 연결한다. */
        val boardingLink: BoardingLink = BoardingLink.Idle,
        /** 휴대폰으로 탑승 등록한 사람 (출퇴근 여부, 결제 수단) */
        val riders: List<BoardingClient.Rider> = emptyList(),
        /** 도착 후 자동결제 (테스트) 상태 */
        val autoPay: AutoPay = AutoPay.None,
        /** 이동 중에 탄 사람의 탑승 지점 */
        val midJoins: Map<String, JoinPoint> = emptyMap(),
        /** 동승석별 좌석 점유·안전벨트 신호 */
        val seats: Map<SeatPosition, SeatSignal> = emptyMap(),
        /** 새로 앉았지만 아직 탑승 등록하지 않은 자리. 정차 중이면 첫 자리의 탑승 QR 을 자동으로 띄운다. */
        val seatPrompts: List<SeatPosition> = emptyList(),
        /** 이동 중에 먼저 내린 사람 */
        val alights: Map<String, AlightInfo> = emptyMap(),
        /** 자리가 비어 하차 판단을 기다리는 사람 */
        val alightPending: Set<String> = emptySet(),
    ) {
        /** 먼저 내린 사람의 하차 지점 */
        val leaves: Map<String, JoinPoint> get() = alights.mapValues { it.value.point }
    }

    /** 먼저 내린 사람. 하차 지점은 자리가 빈 순간이다. */
    data class AlightInfo(
        val seat: SeatPosition,
        val point: JoinPoint,
        val status: AlightStatus,
        /** 서버에 하차를 알린 시각. 이때부터 [ALIGHT_GRACE_MS] 동안 승객이 취소할 수 있다. */
        val graceSinceMs: Long = 0L,
        val charge: BoardingClient.Charge? = null,
    )

    enum class AlightStatus {
        /** 서버에 하차를 알리는 중 */
        NOTIFYING,
        /** 승객이 '아직 타고 있어요'를 누를 수 있는 시간 */
        GRACE,
        CHARGING,
        /** 결제 결과를 받음 (결제됨·미등록·출퇴근 아님 등) */
        DONE,
        /** 지금 결제하지 않고 도착 때 함께 결제 (출퇴근 판정 전, 서버 연결 실패 등) */
        AT_ARRIVAL,
    }

    sealed interface AutoPay {
        /** 탑승 QR 을 쓰지 않았거나 결제 대상이 아닌 이동 */
        data object None : AutoPay
        data object Charging : AutoPay
        data class Done(val charges: List<BoardingClient.Charge>) : AutoPay
        data class Failed(val reason: String) : AutoPay
    }

    sealed interface BoardingLink {
        data object Idle : BoardingLink
        data object Connecting : BoardingLink
        /** 승객 휴대폰이 열 탑승 등록 페이지 주소 (QR 에 넣는다) */
        data class Ready(val joinUrl: String) : BoardingLink
        data class Offline(val reason: String) : BoardingLink
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val store = CatMeterStore(context)
    val aaos = AaosMonitor(context)
    val pleos = PleosVehicleSource(context, scope)
    val navi = NaviSource(context, scope)
    val seatSensor = SeatSensor(context, scope, BuildConfig.SEAT_OCCUPANCY_INVERTED)
    val doorSensor = DoorSensor(context, scope, SystemClock::elapsedRealtime)

    private val _state = MutableStateFlow(
        State(
            cat = store.loadCat(),
            passengers = store.loadPassengers(),
            pastRides = store.loadTrips().size,
        )
    )
    val state: StateFlow<State> = _state.asStateFlow()

    private val boardingClient = BoardingClient(BuildConfig.BOARDING_SERVER_URL)
    private var boardingJob: Job? = null
    /** 이번 탑승 코드로 휴대폰에서 이미 받은 이름. 운전자가 목록에서 지운 사람을 다시 넣지 않기 위해 기억한다. */
    private val boardedNames = mutableSetOf<String>()
    /** 이번 탑승 코드로 서버에 연 이동의 차량 토큰. QR 화면을 닫아도 도착 후 자동결제에 쓰므로 이동이 끝날 때까지 둔다. */
    private var boardingToken: String? = null
    /** 탑승자 목록을 받아 오는 일. QR 화면이 떠 있거나 이동 중이면 계속한다 (하차 취소·중간 탑승을 받기 위해). */
    private var pollJob: Job? = null
    private var qrOpen = false
    /** 서버에 마지막으로 알린 좌석 상태 (바뀔 때만 다시 보낸다) */
    private var sentSeats: Map<SeatPosition, Boolean>? = null
    private val alightTracker = AlightTracker()

    /**
     * 시연용: 켜면 시간·장소와 관계없이 출퇴근 함께 타기로 판정해 자동결제(테스트)를 보여 준다.
     * debug 빌드의 DebugRouteReceiver 로만 켤 수 있다. release 에서는 항상 false.
     */
    @Volatile var demoCommute: Boolean = false

    private var tracker: TripTracker? = null
    private var tripFrom: GeoPoint? = null
    private var autoFinishJob: Job? = null
    private var autoStartJob: Job? = null
    private var tickJob: Job? = null

    init {
        pleos.start()
        aaos.start()
        navi.start()
        seatSensor.start()
        doorSensor.start()
        // 좌석에 새로 앉으면 그 자리 탑승 QR 을 띄울 목록에 넣는다 (도착 화면에서는 띄우지 않는다).
        // 알림을 등록하는 순간이나 앉는 동안 값이 잠깐 흔들리므로, 값이 [SEAT_SETTLE_MS] 동안 그대로일 때만 본다.
        scope.launch {
            @OptIn(FlowPreview::class)
            seatSensor.signals.debounce(SEAT_SETTLE_MS).collect { after ->
                _state.update {
                    it.copy(
                        seats = after,
                        seatPrompts = SeatPrompts.update(it.seatPrompts, it.seats, after, it.assignedSeats(), allowNew = it.phase != Phase.ARRIVED),
                    )
                }
            }
        }
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
        // 이동 중에 탄 사람은 탄 지점을 기록해 그 뒤 구간만 나눠 내게 한다
        val join = tracker?.takeIf { _state.value.phase == Phase.DRIVING }?.snapshot(now())
            ?.let { JoinPoint(it.distanceKm, it.elapsedMs) }
        _state.update {
            it.copy(passengers = it.passengers + n, midJoins = if (join != null) it.midJoins + (n to join) else it.midJoins)
        }
        store.savePassengers(_state.value.passengers)
    }

    fun removePassenger(name: String) {
        _state.update { it.copy(passengers = it.passengers - name, midJoins = it.midJoins - name) }
        store.savePassengers(_state.value.passengers)
    }

    // ── 탑승 QR ──────────────────────────────────────────────────
    /**
     * 탑승 QR 화면을 띄울 때 부른다. 서버에 이번 이동을 열고, 화면이 떠 있는 동안 휴대폰으로 등록한 이름을 받아 동승자에 넣는다.
     * 서버에 연결하지 못하면 Offline 으로 두고, 다시 부르면 다시 시도한다.
     */
    fun openBoarding() {
        qrOpen = true
        if (boardingJob?.isActive == true) return
        val code = _state.value.boardingCode
        _state.update { it.copy(boardingLink = BoardingLink.Connecting) }
        boardingJob = scope.launch {
            val opened = try {
                boardingClient.openRide(code, _state.value.cat.name.ifBlank { "나비" }, boardingToken)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w(TAG, "boarding server unreachable: ${e.message}")
                _state.update { it.copy(boardingLink = BoardingLink.Offline("탑승 서버에 연결할 수 없어요")) }
                return@launch
            }
            boardingToken = opened.ownerToken
            _state.update { it.copy(boardingLink = BoardingLink.Ready(opened.joinUrl)) }
            ensurePolling()
        }
    }

    /** QR 화면이 떠 있거나 이동 중인 동안 탑승자 목록을 받아 온다 */
    private fun ensurePolling() {
        if (pollJob?.isActive == true || boardingToken == null) return
        val code = _state.value.boardingCode
        pollJob = scope.launch {
            while (isActive && (qrOpen || _state.value.phase == Phase.DRIVING)) {
                val fetchedAt = now()
                try {
                    syncSeats(code)
                    applyRiders(boardingClient.riders(code), fetchedAt)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.w(TAG, "boarding poll failed: ${e.message}")
                }
                delay(BOARDING_POLL_MS)
            }
        }
    }

    /** 좌석 상태가 바뀌었으면 서버에 알린다 (운전석 제외, 차량이 지원하는 자리만) */
    private suspend fun syncSeats(code: String) {
        val token = boardingToken ?: return
        val seats = _state.value.seats.filterKeys { !it.isDriver }.mapValues { it.value.taken }
        if (seats.isEmpty() || seats == sentSeats) return
        boardingClient.putSeats(code, token, seats)
        sentSeats = seats
    }

    private fun applyRiders(riders: List<BoardingClient.Rider>, fetchedAt: Long) {
        riders.forEach { r -> if (boardedNames.add(r.name)) addPassenger(r.name) }
        // 하차를 알린 뒤에 받은 목록에서 하차가 아니면 승객이 '아직 타고 있어요'를 누른 것이다
        val undone = _state.value.alights.filter { (name, a) ->
            a.status == AlightStatus.GRACE && fetchedAt > a.graceSinceMs && riders.any { it.name == name && !it.alighted }
        }
        undone.forEach { (name, a) ->
            Log.i(TAG, "alight undone by rider: $name")
            alightTracker.suppress(a.seat)
        }
        _state.update {
            val assigned = riders.mapNotNull(BoardingClient.Rider::seat).toSet()
            it.copy(
                riders = riders,
                seatPrompts = it.seatPrompts.filterNot { seat -> seat in assigned },
                alights = it.alights - undone.keys,
            )
        }
    }

    /** 운전자가 좌석 탑승 QR 에서 '나중에'를 눌렀을 때. 그 자리가 비었다가 다시 앉을 때까지 다시 띄우지 않는다. */
    fun dismissSeatPrompt(seat: SeatPosition) = _state.update { it.copy(seatPrompts = it.seatPrompts - seat) }

    /** 여러 자리를 한꺼번에 띄운 자리 고르기 QR 을 닫을 때 */
    fun dismissSeatPrompts() = _state.update { it.copy(seatPrompts = emptyList()) }

    private fun State.assignedSeats(): Set<SeatPosition> = riders.mapNotNull(BoardingClient.Rider::seat).toSet()

    /** 운전자가 '나중에'로 넘긴 자리의 QR 을 다시 띄운다 */
    fun promptSeat(seat: SeatPosition) = _state.update { it.copy(seatPrompts = listOf(seat) + (it.seatPrompts - seat)) }

    /** 탑승 QR 화면을 닫을 때 부른다. 서버의 탑승 정보는 남아 있어서 다시 열면 이어서 받는다. 이동 중이면 목록은 계속 받는다. */
    fun closeBoarding() {
        qrOpen = false
        boardingJob?.cancel()
        boardingJob = null
        _state.update { it.copy(boardingLink = BoardingLink.Idle) }
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
        closeBoarding()
        if (_state.value.cat.name.isBlank()) setCatName("나비")
        val t = TripTracker(startedAtMs = now(), startOdometerKm = pleos.odometerKm.value)
        t.onSpeed(speedKph, now())
        tracker = t
        tripFrom = navi.location.value
        pleos.refreshEnergy()
        navi.refreshLocation()
        _state.update {
            it.copy(
                phase = Phase.DRIVING, live = t.snapshot(now()), record = null, arrivalSuggested = false, midJoins = emptyMap(),
                alights = emptyMap(), alightPending = emptySet(),
                destinationName = navi.destination.value?.name,
                autoStarted = auto,
            )
        }
        alightTracker.reset()
        ensurePolling()
        tickJob?.cancel()
        tickJob = scope.launch {
            while (isActive) {
                // 속도 이벤트는 값이 바뀔 때만 오므로, 일정 속도 구간도 적분되게 1초마다 현재 속도를 넣는다.
                tracker?.let { tr ->
                    val t0 = now()
                    tr.onSpeed(speedKph, t0)
                    val snap = tr.snapshot(t0)
                    checkAlights(t0, JoinPoint(snap.distanceKm, snap.elapsedMs))
                    _state.update { it.copy(live = snap, alightPending = alightTracker.pending) }
                }
                delay(1_000L)
            }
        }
    }

    // ── 중간 하차 ────────────────────────────────────────────────
    /** 1초마다: 자리가 빈 사람을 하차로 판단하고, 취소할 시간이 지난 사람을 결제한다 */
    private fun checkAlights(nowMs: Long, point: JoinPoint) {
        val st = _state.value
        val seated = st.riders
            .filter { it.seat != null && it.name in st.passengers && it.name !in st.alights }
            .associate { it.seat!! to it.name }
        // 하차 판단의 대기 시간은 부팅 뒤 흐른 시간으로 잰다. 벽시계는 차량이 시간을 맞출 때 뛸 수 있다.
        alightTracker.update(
            SystemClock.elapsedRealtime(), st.seats, seated,
            stopped = speedKph < TripTracker.STOPPED_KPH,
            moving = speedKph >= DEPART_KPH,
            point = point,
            doors = doorsBySeat(seated.keys),
        ).forEach(::onAlight)
        st.alights
            .filter { (_, a) -> a.status == AlightStatus.GRACE && nowMs - a.graceSinceMs >= ALIGHT_GRACE_MS }
            .keys.forEach(::chargeAlighted)
    }

    /** 자리별 문 신호. 차가 그 자리 쪽 문의 신호를 주지 않으면 넣지 않는다 (좌석만으로 판단) */
    private fun doorsBySeat(seats: Set<SeatPosition>): Map<SeatPosition, DoorSignal> {
        val doors = doorSensor.signals.value
        return seats.mapNotNull { seat -> seat.doorSignal(doors)?.let { seat to it } }.toMap()
    }

    private fun onAlight(a: AlightTracker.Alight) {
        Log.i(TAG, "alight: ${a.name} at ${a.point}")
        setAlight(a.name, AlightInfo(a.seat, a.point, AlightStatus.NOTIFYING))
        val token = boardingToken ?: return setAlight(a.name) { it.copy(status = AlightStatus.AT_ARRIVAL) }
        val code = _state.value.boardingCode
        scope.launch {
            try {
                boardingClient.alight(code, token, a.name)
                setAlight(a.name) { it.copy(status = AlightStatus.GRACE, graceSinceMs = now()) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w(TAG, "alight notify failed: ${e.message}")
                setAlight(a.name) { it.copy(status = AlightStatus.AT_ARRIVAL) }
            }
        }
    }

    /**
     * 먼저 내린 사람만 결제한다. 그 사람이 탄 구간은 이미 끝나서 금액이 바뀌지 않는다.
     * 출퇴근 함께 타기 최종 판정은 도착해야 할 수 있으므로, 지금까지의 조건(출발 시각·출발지·내비 목적지)으로 미리 판정해
     * 맞을 때만 결제하고, 아니면 도착 때 최종 판정으로 결제한다.
     */
    private fun chargeAlighted(name: String) {
        val tr = tracker ?: return
        val token = boardingToken
        if (token == null || !provisionalCommute()) {
            setAlight(name) { it.copy(status = AlightStatus.AT_ARRIVAL) }
            return
        }
        val st = _state.value
        val snap = tr.snapshot(now())
        val amount = TripBill.shares(snap.distanceKm, snap.elapsedMs, st.energy, st.passengers, st.midJoins, st.leaves)[name]
            ?: return setAlight(name) { it.copy(status = AlightStatus.AT_ARRIVAL) }
        setAlight(name) { it.copy(status = AlightStatus.CHARGING) }
        val code = st.boardingCode
        scope.launch {
            try {
                val charge = boardingClient.charge(code, token, tripId(tr), mapOf(name to amount)).firstOrNull()
                setAlight(name) { it.copy(status = AlightStatus.DONE, charge = charge) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w(TAG, "alight charge failed: ${e.message}")
                setAlight(name) { it.copy(status = AlightStatus.AT_ARRIVAL) }
            }
        }
    }

    /** 이동 중 미리 판정: 지금까지의 출발 시각·출발지와 내비 목적지로 출퇴근 함께 타기인지 본다 */
    private fun provisionalCommute(): Boolean {
        if (demoCommute) return true
        val tr = tracker ?: return false
        val start = toLocal(tr.snapshot(now()).startedAtMs)
        return CommuteRule.check(
            start = start, end = toLocal(now()),
            from = tripFrom, to = navi.destination.value?.point,
            home = navi.home.value, office = navi.office.value,
            alreadySettledInWindow = false,
        ).eligible
    }

    private fun setAlight(name: String, info: AlightInfo) = _state.update { it.copy(alights = it.alights + (name to info)) }

    private fun setAlight(name: String, f: (AlightInfo) -> AlightInfo) = _state.update { st ->
        val cur = st.alights[name] ?: return@update st
        st.copy(alights = st.alights + (name to f(cur)))
    }

    /** 서버 결제 요청에 쓰는 이동 번호 (먼저 내린 사람과 도착 결제가 같은 번호를 써야 두 번 결제하지 않는다) */
    private fun tripId(t: TripTracker): String = t.snapshot(now()).startedAtMs.toString()

    fun dismissArrival() = _state.update { it.copy(arrivalSuggested = false) }

    fun finishTrip() {
        val t = tracker ?: return
        tickJob?.cancel()
        alightTracker.reset()
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
            joins = st.midJoins.filterKeys { it in st.passengers },
            leaves = st.leaves.filterKeys { it in st.passengers },
            rideNumber = st.pastRides + 1,
            energy = st.energy,
            destinationName = st.destinationName ?: navi.destination.value?.name,
            from = tripFrom,
            to = to,
            commute = if (demoCommute) {
                CommuteCheck(eligible = true, window = window ?: CommuteWindow.MORNING, reasons = emptyList())
            } else {
                CommuteRule.check(
                    start = start,
                    end = toLocal(end),
                    from = tripFrom,
                    to = to,
                    home = navi.home.value,
                    office = navi.office.value,
                    alreadySettledInWindow = alreadySettled,
                )
            },
        )
        store.addTrip(record)
        tracker = null
        _state.update {
            it.copy(phase = Phase.ARRIVED, record = record, live = null, pastRides = it.pastRides + 1, arrivalSuggested = false, alightPending = emptySet())
        }
        chargeRiders()
    }

    /**
     * 도착 후 자동결제 (테스트). 출퇴근 함께 타기로 판정되고, 탑승 QR 로 서버에 이동을 연 경우에만 한다.
     * 서버가 사람별로 결제 수단·출퇴근 여부를 확인해 결제하고, 결제하지 못한 사람은 이유를 돌려준다.
     * 실패하면 다시 부를 수 있다. 같은 이동은 서버가 두 번 결제하지 않는다.
     */
    fun chargeRiders() {
        val r = _state.value.record ?: return
        val token = boardingToken
        val shares = r.shares
        if (!r.commute.eligible || token == null || shares.isEmpty() || _state.value.riders.isEmpty()) {
            _state.update { it.copy(autoPay = AutoPay.None) }
            return
        }
        if (_state.value.autoPay is AutoPay.Charging) return
        val code = _state.value.boardingCode
        _state.update { it.copy(autoPay = AutoPay.Charging) }
        scope.launch {
            try {
                // 먼저 내린 사람도 함께 보낸다. 이미 결제한 사람은 서버가 지난 결과를 돌려준다.
                val charges = boardingClient.charge(code, token, r.startedAtMs.toString(), shares)
                val paidAny = charges.any { it.status == BoardingClient.Charge.Status.PAID }
                val settled = if (paidAny && r.settledAtMs == null) r.copy(settledAtMs = now()).also(store::updateTrip) else r
                _state.update { if (it.record?.id == r.id) it.copy(record = settled, autoPay = AutoPay.Done(charges)) else it }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w(TAG, "auto pay failed: ${e.message}")
                _state.update { if (it.record?.id == r.id) it.copy(autoPay = AutoPay.Failed("자동결제 서버에 연결하지 못했어요")) else it }
            }
        }
    }

    /** 영수증을 닫고 다음 이동을 준비한다 */
    fun backToSetup() {
        closeBoarding()
        pollJob?.cancel()
        pollJob = null
        sentSeats = null
        boardedNames.clear()
        boardingToken = null
        _state.update {
            it.copy(
                phase = Phase.SETUP, record = null, destinationName = null,
                boardingCode = Boarding.newCode(), riders = emptyList(), autoPay = AutoPay.None, midJoins = emptyMap(),
                alights = emptyMap(), alightPending = emptySet(),
            )
        }
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
        /** 좌석 신호가 이만큼 그대로일 때만 앉음·비움으로 본다 */
        private const val SEAT_SETTLE_MS = 1_500L
        /** 하차를 알린 뒤 승객이 '아직 타고 있어요'를 누를 수 있는 시간. 지나면 그 사람만 결제한다. */
        const val ALIGHT_GRACE_MS = 60_000L
        /** 하차 판단에서 '다시 출발'로 보는 속도 */
        private const val DEPART_KPH = 5f
        /** 탑승 QR 화면이 떠 있는 동안 탑승자 목록을 확인하는 간격 */
        private const val BOARDING_POLL_MS = 2_000L
    }
}
