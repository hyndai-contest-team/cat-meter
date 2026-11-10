package kr.catmeter.app.trip

/**
 * 자리에서 일어난 사람을 하차로 판단한다 (이동 중에만 쓴다).
 *
 * - 차가 서 있을 때 자리가 비면(벨트를 풀고 좌석에서도 사람이 감지되지 않음) 하차 후보가 된다.
 *   이때의 거리·시간을 하차 지점으로 기록한다. 판단을 기다리는 시간은 금액에 영향을 주지 않는다.
 * - 좌석 점유 센서가 있는 자리: 후보가 된 뒤 [confirmMs] 동안 계속 비어 있으면 확정.
 * - 좌석 점유 센서가 없는 자리(벨트만 있음): 벨트를 푼 채로 차가 다시 출발하면 확정.
 *   남아 있는 사람이라면 출발 전에 다시 맸을 것이므로.
 * - 확정 전에 다시 앉거나 벨트를 매면 후보를 취소한다.
 * - 달리는 중에 비면 하차가 아니다 (달리는 차에서는 내릴 수 없다). 차가 서면 그때부터 후보가 된다.
 * 도착(운행 종료) 때 남은 사람은 이 판단과 관계없이 모두 내린 것으로 본다.
 * 승객이 '아직 타고 있어요'로 하차를 취소한 자리는, 다시 앉음이 감지될 때까지 판단하지 않는다 ([suppress]).
 */
class AlightTracker(private val confirmMs: Long = CONFIRM_MS) {

    data class Alight(val name: String, val seat: SeatPosition, val point: JoinPoint)

    private data class Candidate(val name: String, val sinceMs: Long, val point: JoinPoint)

    private val candidates = mutableMapOf<SeatPosition, Candidate>()
    private val suppressed = mutableSetOf<SeatPosition>()

    /** 지금 하차 판단을 기다리는 사람 */
    val pending: Set<String> get() = candidates.values.map { it.name }.toSet()

    /**
     * @param seats 좌석 신호 (흔들림을 거른 값)
     * @param riders 자리에 앉아 있는 것으로 아는 탑승자 (자리 → 이름). 이미 내린 사람은 넣지 않는다.
     * @param stopped 차가 서 있음 (속도 기준. P단이 아니어도 된다)
     * @param moving 차가 다시 출발함
     * @param point 지금까지 간 거리·시간
     * @return 이번에 하차로 확정된 사람
     */
    fun update(
        nowMs: Long,
        seats: Map<SeatPosition, SeatSignal>,
        riders: Map<SeatPosition, String>,
        stopped: Boolean,
        moving: Boolean,
        point: JoinPoint,
    ): List<Alight> {
        // 자리 주인이 바뀌었거나 없어진 후보는 버린다
        candidates.entries.removeAll { (seat, c) -> riders[seat] != c.name }

        val confirmed = mutableListOf<Alight>()
        riders.forEach { (seat, name) ->
            val signal = seats[seat] ?: return@forEach   // 센서 값이 없으면 판단하지 않는다
            if (signal.taken) {
                candidates.remove(seat)
                suppressed.remove(seat)
                return@forEach
            }
            if (seat in suppressed) return@forEach
            val candidate = candidates[seat]
            if (candidate == null) {
                if (stopped) candidates[seat] = Candidate(name, nowMs, point)
                return@forEach
            }
            val hasOccupancySensor = signal.occupied != null
            val done = if (hasOccupancySensor) nowMs - candidate.sinceMs >= confirmMs else moving
            if (done) {
                candidates.remove(seat)
                confirmed += Alight(name, seat, candidate.point)
            }
        }
        return confirmed
    }

    /** 하차 판단이 틀렸을 때(승객이 '아직 타고 있어요'): 그 자리에 다시 앉음이 감지될 때까지 판단하지 않는다 */
    fun suppress(seat: SeatPosition) {
        candidates.remove(seat)
        suppressed += seat
    }

    /** 이동이 끝나거나 새 이동을 시작할 때 */
    fun reset() {
        candidates.clear()
        suppressed.clear()
    }

    companion object {
        /** 좌석 점유 센서가 있는 자리에서 비어 있으면 하차로 확정하기까지 */
        const val CONFIRM_MS = 30_000L
    }
}
