package kr.catmeter.app.trip

import kotlin.math.roundToInt

/**
 * 이동 거리와 시간을 가상 단위 '냥'으로 바꾼다 (docs/ideas/2026_11_01_leejihun_nyang_meter.md).
 *
 * - 택시 미터기처럼 일정 구간을 채울 때마다 한 칸씩 오른다. 숫자가 계속 흘러가지 않아 주행 중에도 한눈에 읽힌다.
 * - 냥은 앱 안의 놀이용 숫자다. 원화 환산·결제와 연결하지 않는다 (취합본 5-다).
 * - 요율은 팀 합의 전 임시값이다. 기본요금·저속 시간요금·심야 할증은 아직 넣지 않았다.
 */
data class NyangFare(val distanceNyang: Int, val timeNyang: Int) {
    val totalNyang: Int get() = distanceNyang + timeNyang

    companion object {
        /** 100m마다 10냥 */
        const val DISTANCE_STEP_M = 100
        const val NYANG_PER_DISTANCE_STEP = 10

        /** 1분마다 10냥 */
        const val TIME_STEP_MS = 60_000L
        const val NYANG_PER_TIME_STEP = 10

        fun of(distanceKm: Float, elapsedMs: Long): NyangFare {
            val meters = (distanceKm.coerceAtLeast(0f) * 1000f).roundToInt()  // 0.7f km 가 699m 로 깎이지 않게 m 단위로 반올림
            val distanceSteps = meters / DISTANCE_STEP_M
            val timeSteps = (elapsedMs.coerceAtLeast(0L) / TIME_STEP_MS).toInt()
            return NyangFare(
                distanceNyang = distanceSteps * NYANG_PER_DISTANCE_STEP,
                timeNyang = timeSteps * NYANG_PER_TIME_STEP,
            )
        }
    }
}
