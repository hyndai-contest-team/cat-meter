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

/** 동승자가 타고 내리는 문. 이름은 Pleos SDK DoorValue 와 같다. 운전석 문(1열 왼쪽)은 쓰지 않는다. */
enum class DoorPosition {
    ROW_1_RIGHT, ROW_2_LEFT, ROW_2_RIGHT, ROW_3_LEFT, ROW_3_RIGHT;

    companion object {
        fun ofName(name: String?): DoorPosition? = entries.firstOrNull { it.name == name }
    }
}

/**
 * 문 하나의 신호.
 * @param open 지금 열려 있음
 * @param lastOpenMs 마지막으로 닫힘에서 열림으로 바뀐 시각 (부팅 뒤 흐른 시간). 한 번도 열리지 않았으면 null
 */
data class DoorSignal(val open: Boolean, val lastOpenMs: Long? = null)

/** 이 자리에 앉은 사람이 내릴 때 쓰는 문. 가운데 자리와 3열은 양쪽 2열 문으로도 내린다. */
val SeatPosition.exitDoors: List<DoorPosition>
    get() = when (this) {
        SeatPosition.ROW_1_LEFT -> emptyList()
        SeatPosition.ROW_1_CENTER, SeatPosition.ROW_1_RIGHT -> listOf(DoorPosition.ROW_1_RIGHT)
        SeatPosition.ROW_2_LEFT -> listOf(DoorPosition.ROW_2_LEFT)
        SeatPosition.ROW_2_RIGHT -> listOf(DoorPosition.ROW_2_RIGHT)
        SeatPosition.ROW_2_CENTER -> listOf(DoorPosition.ROW_2_LEFT, DoorPosition.ROW_2_RIGHT)
        SeatPosition.ROW_3_LEFT -> listOf(DoorPosition.ROW_3_LEFT, DoorPosition.ROW_2_LEFT, DoorPosition.ROW_2_RIGHT)
        SeatPosition.ROW_3_CENTER -> listOf(DoorPosition.ROW_3_LEFT, DoorPosition.ROW_3_RIGHT, DoorPosition.ROW_2_LEFT, DoorPosition.ROW_2_RIGHT)
        SeatPosition.ROW_3_RIGHT -> listOf(DoorPosition.ROW_3_RIGHT, DoorPosition.ROW_2_LEFT, DoorPosition.ROW_2_RIGHT)
    }

/**
 * 이 자리에서 내리는 문들의 신호를 하나로 합친다.
 * 차가 그 문들의 신호를 하나도 주지 않으면 null (문 없이 좌석만으로 판단한다).
 */
fun SeatPosition.doorSignal(doors: Map<DoorPosition, DoorSignal>): DoorSignal? {
    val mine = exitDoors.mapNotNull { doors[it] }
    if (mine.isEmpty()) return null
    return DoorSignal(open = mine.any { it.open }, lastOpenMs = mine.mapNotNull { it.lastOpenMs }.maxOrNull())
}
