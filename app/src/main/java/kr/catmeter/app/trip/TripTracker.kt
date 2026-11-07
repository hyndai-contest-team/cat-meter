package kr.catmeter.app.trip

/**
 * 한 번의 이동(출발 ~ 도착)을 기록한다. 안드로이드·SDK 에 의존하지 않는 순수 로직이라 단위 테스트한다.
 *
 * 기준 (MVP 에서 팀이 정한 운영 규칙, 플랫폼 정의가 아님)
 * - 이동 시작·종료: 사용자가 정차 중 [출발]/[도착]을 누른 시점. 차량 트립 초기화와는 무관하다.
 * - 거리: 계기판 누적 거리(Odometer)가 실제로 늘어나는 동안은 그 차이를 쓴다.
 *   누적 거리를 못 받거나, 받더라도 값이 멈춰 있으면(에뮬레이터는 주행 중에도 0.0 고정) 차속을 시간 적분한 값으로 대신한다.
 *   누적 거리가 처음 늘어나면, 직전 값을 기준점으로 삼고 그 직전 값을 처음 받았을 때까지 적분한 거리에 이후 누적 거리 차이를 더한다.
 * - 시간: 출발부터 지금까지의 기기 시간. 주행 시간과 정차 시간을 따로 센다.
 * - 정차: 3km/h 를 넘으면 '이동', 1km/h 미만으로 떨어지면 '정차'(히스테리시스).
 *   정차 횟수는 이동 → 정차 전환 횟수다. 신호 대기·정체·주차를 구분하지 않는다.
 */
class TripTracker(
    val startedAtMs: Long,
    startOdometerKm: Float? = null,
) {
    data class Snapshot(
        val startedAtMs: Long,
        val elapsedMs: Long,
        val movingMs: Long,
        val stoppedMs: Long,
        val distanceKm: Float,
        val stopCount: Int,
        val isMoving: Boolean,
        val distanceSource: DistanceSource,
    ) {
        /** 주행 중 평균 속도. 정차 시간은 빼고 계산한다. */
        val avgMovingKph: Float
            get() = if (movingMs <= 0L) 0f else distanceKm / (movingMs / 3_600_000f)
    }

    enum class DistanceSource { ODOMETER, SPEED_ESTIMATE }

    /** 마지막으로 받은 누적 거리. 값이 바뀌는지 보려고 둔다. */
    private var odoLast: Float? = startOdometerKm
    /** odoLast 값을 처음 받았을 때의 적분 거리 */
    private var integratedAtOdoLast = 0f
    /** 누적 거리가 실제로 늘어난 뒤에만 잡히는 기준점. null 이면 차속 적분을 쓴다. */
    private var odoBaseline: Float? = null
    private var odoLatest: Float? = null
    /** 누적 거리 기준점이 잡히기 전까지 차속으로 적분한 거리 */
    private var integratedBeforeOdoKm = 0f
    /** 전체 구간을 차속으로 적분한 거리 (누적 거리를 못 받을 때 사용) */
    private var integratedKm = 0f

    private var lastSpeedKph = 0f
    private var lastSampleMs = startedAtMs
    private var moving = false
    private var stopCount = 0
    private var movingMs = 0L
    private var stoppedMs = 0L

    fun onSpeed(speedKph: Float, nowMs: Long) {
        advance(nowMs)
        val kph = speedKph.coerceAtLeast(0f)
        if (!moving && kph > MOVING_KPH) {
            moving = true
        } else if (moving && kph < STOPPED_KPH) {
            moving = false
            stopCount++
        }
        lastSpeedKph = kph
    }

    fun onOdometer(totalKm: Float, nowMs: Long) {
        advance(nowMs)
        if (totalKm.isNaN() || totalKm < 0f) return
        val prev = odoLast
        val prevAt = integratedAtOdoLast
        if (prev != totalKm) {
            odoLast = totalKm
            integratedAtOdoLast = integratedKm
        }
        val base = odoBaseline
        if (base == null) {
            // 값이 처음 늘어난 순간부터 누적 거리를 믿는다. 직전 값을 받은 뒤의 구간은 누적 거리 차이가 대신 센다.
            if (prev != null && totalKm > prev) {
                odoBaseline = prev
                odoLatest = totalKm
                integratedBeforeOdoKm = prevAt
            }
            return
        } else if (totalKm < base) {
            // 값이 거꾸로 가면(초기화·오류) 기준점을 다시 잡고 지금까지 거리를 보존한다.
            integratedBeforeOdoKm = distanceKm()
            odoBaseline = totalKm
        }
        odoLatest = totalKm
    }

    fun snapshot(nowMs: Long): Snapshot {
        advance(nowMs)
        return Snapshot(
            startedAtMs = startedAtMs,
            elapsedMs = (nowMs - startedAtMs).coerceAtLeast(0L),
            movingMs = movingMs,
            stoppedMs = stoppedMs,
            distanceKm = distanceKm(),
            stopCount = stopCount,
            isMoving = moving,
            distanceSource = if (odoBaseline != null) DistanceSource.ODOMETER else DistanceSource.SPEED_ESTIMATE,
        )
    }

    private fun distanceKm(): Float {
        val base = odoBaseline
        val latest = odoLatest
        return if (base != null && latest != null) integratedBeforeOdoKm + (latest - base) else integratedKm
    }

    /** 마지막 샘플 이후 시간을 직전 속도로 채운다 (구간 상수 가정). */
    private fun advance(nowMs: Long) {
        val dt = nowMs - lastSampleMs
        if (dt <= 0L) return
        // 센서 공백이 길면(앱이 잠든 경우 등) 속도 적분을 하지 않는다. 시간은 그대로 센다.
        if (dt <= MAX_INTEGRATION_GAP_MS) integratedKm += lastSpeedKph * (dt / 3_600_000f)
        if (moving) movingMs += dt else stoppedMs += dt
        lastSampleMs = nowMs
    }

    companion object {
        const val MOVING_KPH = 3f
        const val STOPPED_KPH = 1f
        const val MAX_INTEGRATION_GAP_MS = 5_000L
    }
}
