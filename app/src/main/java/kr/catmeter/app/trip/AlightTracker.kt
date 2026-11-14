package kr.catmeter.app.trip

/**
 * 자리에서 일어난 사람을 하차로 판단한다 (이동 중에만 쓴다).
 *
 * 하차 후보: 차가 서 있을 때 자리가 비면(벨트를 풀고 좌석에서도 사람이 감지되지 않음) 후보가 된다.
 *   이때의 거리·시간을 하차 지점으로 기록한다. 판단을 기다리는 시간은 금액에 영향을 주지 않는다.
 * 문 열림: 후보의 자리 쪽 문이 이번 정차에서 닫힘 → 열림으로 바뀌었어야 한다. 차가 완전히 서기 직전([doorLeadMs] 안)에 연 것도 친다.
 *   문이 열린 채로 달려온 것(덜 닫힌 문)이나 이전 정차 때 연 것은 치지 않는다.
 *   자리를 비운 것과 문이 열린 것의 순서는 따지지 않는다. 차가 그 문의 신호를 주지 않으면 문 조건 없이 좌석만으로 판단한다.
 *   문이 열리지 않은 채 차가 출발하면 후보를 버린다. 그래야 나중 정차에서 예전 지점으로 하차가 확정되지 않는다.
 * 확정: 자리가 비고 문이 열린 뒤, 아래 둘 중 하나가 되면 하차로 확정한다.
 *   - [confirmMs](30초) 동안 계속 비어 있다. 좌석 점유 센서가 있는 자리만 해당한다.
 *     벨트만 있는 자리는 벨트를 풀고 앉아 있는 사람과 내린 사람을 구분할 수 없어 이 길로는 확정하지 않는다.
 *   - 비어 있는 채로 차가 출발하고 [departMs](5초)가 지났다.
 * 확정 전에 다시 앉거나 벨트를 매면 후보를 취소한다.
 * 달리는 중에 비면 하차가 아니다 (달리는 차에서는 내릴 수 없다). 차가 서면 그때부터 후보가 된다.
 * 도착(운행 종료) 때 남은 사람은 이 판단과 관계없이 모두 내린 것으로 본다.
 * 승객이 '아직 타고 있어요'로 하차를 취소한 자리는, 다시 앉음이 감지될 때까지 판단하지 않는다 ([suppress]).
 */
class AlightTracker(
    private val confirmMs: Long = CONFIRM_MS,
    private val departMs: Long = DEPART_MS,
    private val doorLeadMs: Long = DOOR_LEAD_MS,
) {

    data class Alight(val name: String, val seat: SeatPosition, val point: JoinPoint)

    private data class Candidate(
        val name: String,
        val point: JoinPoint,
        /** 자리가 비고 문 조건도 채워진 시각. 문이 아직 열리지 않았으면 null */
        val readySinceMs: Long? = null,
        /** 준비된 뒤 차가 출발한 시각 */
        val departedMs: Long? = null,
    )

    private val candidates = mutableMapOf<SeatPosition, Candidate>()
    private val suppressed = mutableSetOf<SeatPosition>()
    /** 이번 정차가 시작된 시각. 달리는 중이면 null */
    private var stopSinceMs: Long? = null

    /** 지금 하차 판단을 기다리는 사람 (자리가 비고 문 조건도 채워져, 그대로 두면 하차로 확정될 사람) */
    val pending: Set<String> get() = candidates.values.filter { it.readySinceMs != null }.map { it.name }.toSet()

    /**
     * @param nowMs 지금 시각. [DoorSignal.lastOpenMs]와 같은 시계여야 한다 (벽시계가 아니라 부팅 뒤 흐른 시간을 쓴다)
     * @param seats 좌석 신호 (흔들림을 거른 값)
     * @param riders 자리에 앉아 있는 것으로 아는 탑승자 (자리 → 이름). 이미 내린 사람은 넣지 않는다.
     * @param doors 자리별 문 신호. 차가 그 자리 쪽 문의 신호를 주지 않으면 넣지 않는다.
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
        doors: Map<SeatPosition, DoorSignal> = emptyMap(),
    ): List<Alight> {
        if (moving) stopSinceMs = null else if (stopped && stopSinceMs == null) stopSinceMs = nowMs

        // 자리 주인이 바뀌었거나 없어진 후보는 버린다.
        // 문이 열리지 않은 채 출발한 후보도 버린다 (자리를 옮겼거나 센서가 흔들린 것으로 본다).
        candidates.entries.removeAll { (seat, c) -> riders[seat] != c.name || (moving && c.readySinceMs == null) }

        val confirmed = mutableListOf<Alight>()
        riders.forEach { (seat, name) ->
            val signal = seats[seat] ?: return@forEach   // 센서 값이 없으면 판단하지 않는다
            if (signal.taken) {
                candidates.remove(seat)
                suppressed.remove(seat)
                return@forEach
            }
            if (seat in suppressed) return@forEach
            var c = candidates[seat]
            if (c == null) {
                if (!stopped) return@forEach
                c = Candidate(name, point)
            }
            if (c.readySinceMs == null && doorOpened(doors[seat], hasSignal = seat in doors)) c = c.copy(readySinceMs = nowMs)
            val readySince = c.readySinceMs
            if (readySince == null) {
                candidates[seat] = c
                return@forEach
            }
            c = when {
                moving && c.departedMs == null -> c.copy(departedMs = nowMs)
                stopped && c.departedMs != null -> c.copy(departedMs = null)   // 5초가 되기 전에 다시 섰다
                else -> c
            }
            val hasOccupancySensor = signal.occupied != null
            val stayedEmpty = hasOccupancySensor && nowMs - readySince >= confirmMs
            val leftBehind = c.departedMs?.let { nowMs - it >= departMs } == true
            if (stayedEmpty || leftBehind) {
                candidates.remove(seat)
                confirmed += Alight(name, seat, c.point)
            } else {
                candidates[seat] = c
            }
        }
        return confirmed
    }

    /**
     * 이번 정차에서 문이 열렸는가. 문 신호가 없는 자리는 문 조건을 채운 것으로 본다.
     * '지금 열려 있음'만으로는 치지 않는다. 열린 채로 달려온 문일 수 있기 때문이다.
     */
    private fun doorOpened(door: DoorSignal?, hasSignal: Boolean): Boolean {
        if (!hasSignal || door == null) return true
        val since = stopSinceMs ?: return false
        return door.lastOpenMs != null && door.lastOpenMs >= since - doorLeadMs
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
        stopSinceMs = null
    }

    companion object {
        /** 자리가 비고 문이 열린 뒤, 계속 비어 있으면 하차로 확정하기까지 */
        const val CONFIRM_MS = 30_000L
        /** 자리가 비고 문이 열린 뒤 차가 출발하면, 출발하고 하차로 확정하기까지 */
        const val DEPART_MS = 5_000L
        /** 차가 완전히 서기 전 이 시간 안에 연 문도 이번 정차에서 연 것으로 본다 */
        const val DOOR_LEAD_MS = 10_000L
    }
}
