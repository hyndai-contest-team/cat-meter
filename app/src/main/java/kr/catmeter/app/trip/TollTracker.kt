package kr.catmeter.app.trip

/** 요금소를 지난 지점과 그때 나눌 통행료(원) */
data class TollEvent(val point: JoinPoint, val won: Int)

/**
 * 내비가 알려 준 경로 통행료를 요금소를 지날 때마다 나눠 기록한다.
 *
 * 내비(NaviHelper)가 주는 것은 두 가지다.
 * - 경로 전체의 통행료 합계 (경로를 시작할 때 한 번)
 * - 앞으로 남은 요금소까지의 거리 (회전 안내 목록의 TURN_TOLL)
 * 요금소 하나하나의 금액은 주지 않는다. 그래서 남은 통행료를 남은 요금소 수로 똑같이 나눠 기록한다.
 * 마지막 요금소에서 남은 금액을 모두 기록하므로, 요금소를 다 지나면 합계는 경로 통행료와 같다.
 * 요금소를 다 지나기 전에 이동을 끝내면 지나간 요금소 몫만 기록한다.
 *
 * 요금소 통과는 달린 거리로 판단한다. 요금소 목록을 받은 순간의 달린 거리에 요금소까지 거리를 더해 두고,
 * 달린 거리가 그 값을 넘으면 지난 것으로 본다. 내비가 목록을 새로 주면(지나간 요금소가 빠지면) 그에 맞춘다.
 */
class TollTracker {
    /** 아직 기록하지 않은 경로 통행료 */
    private var remainingWon = 0
    /** 남은 요금소의 위치 (이동을 시작한 뒤 달린 거리, km) */
    private var gatesKm: List<Float> = emptyList()

    private val _events = mutableListOf<TollEvent>()
    val events: List<TollEvent> get() = _events.toList()

    /** 새 경로를 시작했다. [tollWon] 은 그 경로 전체의 통행료다. 이미 기록한 통행료는 그대로 둔다. */
    fun onRoute(tollWon: Int) {
        remainingWon = tollWon.coerceAtLeast(0)
    }

    /**
     * 내비가 남은 요금소 목록을 새로 줬다.
     * 남은 요금소가 우리가 아는 것보다 적으면 그 사이에 지나간 것으로 보고 지금 지점에 기록한다.
     * @param aheadKm 지금 위치에서 남은 요금소까지의 거리 (가까운 순서)
     * @return 새로 기록한 통행료
     */
    fun onGates(now: JoinPoint, aheadKm: List<Float>): List<TollEvent> {
        val passed = (gatesKm.size - aheadKm.size).coerceAtLeast(0)
        val added = (0 until passed).mapNotNull { pass(now) }
        gatesKm = aheadKm.sorted().map { now.distanceKm + it }
        return added
    }

    /** 달린 거리가 요금소 위치를 넘었으면 기록한다. @return 새로 기록한 통행료 */
    fun update(now: JoinPoint): List<TollEvent> {
        val added = mutableListOf<TollEvent>()
        while (gatesKm.isNotEmpty() && gatesKm.first() <= now.distanceKm) pass(now)?.let(added::add)
        return added
    }

    private fun pass(now: JoinPoint): TollEvent? {
        if (gatesKm.isEmpty()) return null
        val won = if (gatesKm.size == 1) remainingWon else remainingWon / gatesKm.size
        gatesKm = gatesKm.drop(1)
        remainingWon -= won
        if (won <= 0) return null
        return TollEvent(now, won).also(_events::add)
    }
}
