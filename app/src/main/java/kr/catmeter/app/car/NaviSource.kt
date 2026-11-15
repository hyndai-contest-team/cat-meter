package kr.catmeter.app.car

import ai.pleos.playground.navi.constants.BookmarkType
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.TurnType
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.data.BookmarkInfo
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.constants.RouteOption
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kr.catmeter.app.trip.GeoPoint

/**
 * Pleos NaviHelper SDK 로 내비 상태를 읽는다 (조회·구독만, 경로 요청은 하지 않는다).
 *
 * - 목적지: 내비에서 경로를 시작하면 onRouteStarted(거리·시간·통행료), 이후 getDestinationInfo 로 이름·좌표·남은 거리·시간.
 *   → 냥미터기에 목적지가 자동으로 잡히고 출발 전 예상 냥을 보여 준다.
 * - 집·회사: 즐겨찾기(getBookmarkInfo)의 HOME / OFFICE → 출퇴근 판정에 쓴다.
 * - 현재 위치: getCurrentLocationInfo → 출발·도착 지점.
 * 결과는 모두 비동기 콜백으로 온다. 거리 단위는 m, 시간 단위는 초로 가정했다 (에뮬레이터 로그로 확인 필요).
 * 권한: pleos.car.permission.NAVI_ROUTE
 */
class NaviSource(context: Context, private val scope: CoroutineScope) {

    sealed interface Status {
        data object Connecting : Status
        data object Ready : Status
        data class Failed(val reason: String) : Status
    }

    data class Destination(
        val name: String,
        val point: GeoPoint?,
        /** m */
        val remainDistanceM: Int,
        /** 초 */
        val remainTimeS: Int,
        /** 이 경로 전체의 통행료(원). 내비가 경로를 시작할 때 알려 준 값이다. 없으면 0 */
        val tollFare: Int,
    ) {
        val remainKm: Float get() = remainDistanceM / 1000f
    }

    private val navi = NaviHelper(context.applicationContext)

    private val _status = MutableStateFlow<Status>(Status.Connecting)
    val status: StateFlow<Status> = _status.asStateFlow()

    /** 경로 안내 중인 목적지. 안내가 없으면 null */
    private val _destination = MutableStateFlow<Destination?>(null)
    val destination: StateFlow<Destination?> = _destination.asStateFlow()

    private val _home = MutableStateFlow<GeoPoint?>(null)
    val home: StateFlow<GeoPoint?> = _home.asStateFlow()

    private val _office = MutableStateFlow<GeoPoint?>(null)
    val office: StateFlow<GeoPoint?> = _office.asStateFlow()

    /** 경로 안내가 끝난 이유. 냥미터기 자동 도착에 쓴다. */
    enum class RouteEnd { ARRIVED, CANCELLED }

    private val _routeEnded = MutableSharedFlow<RouteEnd>(extraBufferCapacity = 4)
    val routeEnded: SharedFlow<RouteEnd> = _routeEnded

    /** 앞으로 남은 요금소까지의 거리(km), 가까운 순서. 내비의 회전 안내 목록(TURN_TOLL)에서 읽는다. 안내가 없으면 비어 있다. */
    private val _tollGatesKm = MutableStateFlow<List<Float>>(emptyList())
    val tollGatesKm: StateFlow<List<Float>> = _tollGatesKm.asStateFlow()

    /** 새 경로를 시작할 때마다 그 경로 전체의 통행료(원)를 알린다 */
    private val _routeStarted = MutableSharedFlow<Int>(extraBufferCapacity = 4)
    val routeStarted: SharedFlow<Int> = _routeStarted

    private val _location = MutableStateFlow<GeoPoint?>(null)
    val location: StateFlow<GeoPoint?> = _location.asStateFlow()

    private var pollJob: Job? = null

    private val listener = object : NaviHelperEventListener {
        override fun onRouteStarted(routeStartInfo: RouteStartInfo) {
            Log.i(TAG, "route started: $routeStartInfo")
            _destination.value = Destination(
                name = routeStartInfo.name,
                point = _destination.value?.point,
                remainDistanceM = routeStartInfo.distance,
                remainTimeS = routeStartInfo.duration,
                tollFare = routeStartInfo.tollFare,
            )
            _routeStarted.tryEmit(routeStartInfo.tollFare)
            navi.getDestinationInfo()
            navi.getTBTInfo()
        }

        override fun onDestinationChanged() {
            navi.getDestinationInfo()
        }

        override fun onDestinationInfo(destinationInfo: DestinationInfo) {
            Log.i(TAG, "destination: $destinationInfo")
            // 안내 중이 아니면 이름·좌표가 빈 값으로 온다
            if (destinationInfo.name.isBlank() && destinationInfo.remainDistance <= 0) {
                _destination.value = null
                return
            }
            _destination.value = Destination(
                name = destinationInfo.name,
                point = pointOrNull(destinationInfo.latitude, destinationInfo.longitude),
                remainDistanceM = destinationInfo.remainDistance,
                remainTimeS = destinationInfo.remainTime,
                // 에뮬레이터 내비는 여기서 통행료를 0 으로 준다 (경로 시작 이벤트에만 값이 있다).
                // 0 이면 같은 경로를 시작할 때 받은 값을 그대로 둔다.
                tollFare = destinationInfo.tollFare.takeIf { it > 0 } ?: _destination.value?.tollFare ?: 0,
            )
        }

        override fun onTBTInfo(tbtInfoList: List<TBTInfo>) {
            // 목록은 앞으로 남은 안내가 순서대로 들어 있고, 거리는 바로 앞 안내에서 그 안내까지의 구간 길이다
            // (에뮬레이터에서 확인). 그래서 요금소까지의 거리는 그 앞 구간을 모두 더한 값이다.
            var sumM = 0.0
            val gates = buildList {
                tbtInfoList.forEach { tbt ->
                    sumM += tbt.distance
                    if (tbt.type == TurnType.TURN_TOLL) add((sumM / 1000.0).toFloat())
                }
            }
            if (gates != _tollGatesKm.value) Log.i(TAG, "toll gates ahead (km): $gates")
            _tollGatesKm.value = gates
        }

        override fun onRouteCancelled() {
            Log.i(TAG, "route cancelled")
            _destination.value = null
            _routeEnded.tryEmit(RouteEnd.CANCELLED)
        }

        override fun onRouteEnded() {
            // 에뮬레이터 내비는 경로를 시작한 직후에도 이 이벤트를 보낸다 (경로 탐색 완료로 보임).
            // 안내 종료 신호로 쓰면 출발하자마자 냥미터기가 끝나므로 쓰지 않는다.
            Log.i(TAG, "route ended (ignored)")
        }

        override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) {
            Log.i(TAG, "destination arrived: $destinationArrivedInfo")
            _destination.value = null
            _routeEnded.tryEmit(RouteEnd.ARRIVED)
        }

        override fun onBookMarkChanged() {
            navi.getBookmarkInfo()
        }

        override fun onBookmarkInfo(bookmarkInfo: List<BookmarkInfo>) {
            Log.i(TAG, "bookmarks: ${bookmarkInfo.map { "${it.category}:${it.name}" }}")
            // 등록하지 않은 집·회사도 빈 항목(좌표 0,0)으로 온다
            _home.value = bookmarkInfo.firstOrNull { it.category == BookmarkType.HOME }?.let { pointOrNull(it.latitude, it.longitude) }
            _office.value = bookmarkInfo.firstOrNull { it.category == BookmarkType.OFFICE }?.let { pointOrNull(it.latitude, it.longitude) }
        }

        override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) {
            pointOrNull(currentLocationInfo.latitude, currentLocationInfo.longitude)?.let { _location.value = it }
        }

        override fun onError(errorCode: NaviErrorCode) {
            Log.w(TAG, "navi error: $errorCode")
        }
    }

    fun start() {
        runCatching {
            navi.initialize(
                {
                    navi.addListener(listener)
                    _status.value = Status.Ready
                    navi.getBookmarkInfo()
                    navi.getDestinationInfo()
                    startPolling()
                },
                { t ->
                    Log.e(TAG, "NaviHelper initialize failed", t)
                    _status.value = Status.Failed("내비 연결 실패")
                },
            )
        }.onFailure {
            Log.e(TAG, "NaviHelper initialize threw", it)
            _status.value = Status.Failed("내비 연결 실패")
        }
    }

    /** 현재 위치와 남은 거리를 주기적으로 갱신한다. 이벤트가 내비 구현에 따라 오지 않을 수 있어 직접 묻는다. */
    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                runCatching {
                    navi.getCurrentLocationInfo()
                    if (_destination.value != null) {
                        navi.getDestinationInfo()
                        navi.getTBTInfo()
                    } else {
                        _tollGatesKm.value = emptyList()
                    }
                }
                delay(POLL_MS)
            }
        }
    }

    /**
     * 테스트용: 내비에 목적지를 요청한다. 에뮬레이터 기본 내비에서 목적지를 찍을 수 없을 때 연동 확인에 쓴다.
     * debug 빌드의 DebugRouteReceiver 에서만 부른다. 앱 화면에서는 경로를 바꾸지 않는다.
     */
    fun requestRouteForTest(lat: Double, lon: Double, name: String) {
        Log.i(TAG, "test route request: $name ($lat, $lon)")
        runCatching {
            navi.requestRoute(
                RouteInfo(
                    latitude = lat,
                    longitude = lon,
                    poiName = name,
                    poiId = "",
                    poiSubId = "",
                    address = name,
                    routeOption = RouteOption.RECOMMENDED,
                )
            )
        }.onFailure { Log.e(TAG, "test route request failed", it) }
    }

    /** 테스트용: 경로 안내를 취소한다 (DebugRouteReceiver 전용) */
    fun cancelRouteForTest() {
        Log.i(TAG, "test route cancel")
        runCatching { navi.cancelRoute() }.onFailure { Log.e(TAG, "test route cancel failed", it) }
    }

    /** 출발·도착 직전에 위치를 한 번 더 요청한다 (결과는 location 으로 들어온다). */
    fun refreshLocation() {
        runCatching { navi.getCurrentLocationInfo() }
    }

    fun release() {
        pollJob?.cancel()
        runCatching { navi.removeListener(listener) }
        runCatching { navi.release() }
    }

    private fun pointOrNull(lat: Double, lon: Double): GeoPoint? =
        if (lat == 0.0 && lon == 0.0) null else GeoPoint(lat, lon)

    companion object {
        private const val TAG = "CatMeterNavi"
        private const val POLL_MS = 3_000L
    }
}
