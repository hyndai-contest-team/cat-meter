package kr.catmeter.app.trip

/** 좌석 위치. 이름은 Pleos SDK SeatValue 와 같다 (한국 차 기준 운전석 = 1열 왼쪽). */
enum class SeatPosition(val label: String) {
    ROW_1_LEFT("운전석"),
    ROW_1_CENTER("앞 가운데"),
    ROW_1_RIGHT("조수석"),
    ROW_2_LEFT("뒷좌석 왼쪽"),
    ROW_2_CENTER("뒷좌석 가운데"),
    ROW_2_RIGHT("뒷좌석 오른쪽"),
    ROW_3_LEFT("3열 왼쪽"),
    ROW_3_CENTER("3열 가운데"),
    ROW_3_RIGHT("3열 오른쪽"),
    ;

    val isDriver: Boolean get() = this == ROW_1_LEFT

    companion object {
        fun ofName(name: String?): SeatPosition? = entries.firstOrNull { it.name == name }
    }
}

/**
 * 좌석 하나의 센서 신호. null = 이 좌석은 그 센서를 지원하지 않거나 아직 값을 받지 못함.
 * @param occupied 좌석 점유 (사람이 앉아 있음)
 * @param buckled 안전벨트 착용
 */
data class SeatSignal(val occupied: Boolean? = null, val buckled: Boolean? = null) {
    /** 사람이 앉았다고 볼 수 있는가: 벨트를 맸거나 좌석에 사람이 감지됨 */
    val taken: Boolean get() = buckled == true || occupied == true
}

/**
 * 좌석 탑승 QR 을 띄울 자리 목록.
 * - 비어 있다가 새로 앉은 자리(벨트를 매거나 좌석에 사람이 감지됨)를 더한다.
 * - 이미 탑승 등록한 사람이 앉은 자리와, 다시 비워진 자리는 뺀다.
 * - 운전자가 '나중에'를 누른 자리는 목록에서 빠지고, 그 자리가 비었다가 다시 앉을 때까지 다시 뜨지 않는다.
 */
object SeatPrompts {
    fun update(
        prompts: List<SeatPosition>,
        before: Map<SeatPosition, SeatSignal>,
        after: Map<SeatPosition, SeatSignal>,
        assigned: Set<SeatPosition>,
        allowNew: Boolean = true,
    ): List<SeatPosition> {
        val kept = prompts.filter { after[it]?.taken == true && it !in assigned }
        if (!allowNew) return kept
        val added = after.filter { (seat, signal) -> signal.taken && before[seat]?.taken != true && !seat.isDriver }
            .keys.filter { it !in assigned && it !in kept }.sorted()
        return kept + added
    }
}
