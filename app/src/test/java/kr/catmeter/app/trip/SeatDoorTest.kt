package kr.catmeter.app.trip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SeatDoorTest {
    private val closed = DoorSignal(open = false)

    @Test
    fun `each seat uses the door on its own side`() {
        assertEquals(listOf(DoorPosition.ROW_1_RIGHT), SeatPosition.ROW_1_RIGHT.exitDoors)
        assertEquals(listOf(DoorPosition.ROW_2_LEFT), SeatPosition.ROW_2_LEFT.exitDoors)
        assertEquals(listOf(DoorPosition.ROW_2_RIGHT), SeatPosition.ROW_2_RIGHT.exitDoors)
        assertEquals(emptyList<DoorPosition>(), SeatPosition.ROW_1_LEFT.exitDoors)
    }

    @Test
    fun `the middle seat counts either rear door and keeps the latest opening`() {
        val doors = mapOf(
            DoorPosition.ROW_2_LEFT to DoorSignal(open = false, lastOpenMs = 100),
            DoorPosition.ROW_2_RIGHT to DoorSignal(open = true, lastOpenMs = 500),
        )
        assertEquals(DoorSignal(open = true, lastOpenMs = 500), SeatPosition.ROW_2_CENTER.doorSignal(doors))
        assertEquals(DoorSignal(open = false, lastOpenMs = 100), SeatPosition.ROW_2_LEFT.doorSignal(doors))
    }

    @Test
    fun `a seat whose doors the car does not report has no door signal`() {
        val onlyRight = mapOf(DoorPosition.ROW_2_RIGHT to closed)
        assertNull(SeatPosition.ROW_2_LEFT.doorSignal(onlyRight))
        assertNull(SeatPosition.ROW_1_RIGHT.doorSignal(emptyMap()))
        // 가운데 자리는 신호가 있는 문만으로 판단한다
        assertEquals(closed, SeatPosition.ROW_2_CENTER.doorSignal(onlyRight))
        // 3열은 3열 문이 없어도 2열 문으로 판단한다
        assertEquals(closed, SeatPosition.ROW_3_LEFT.doorSignal(onlyRight))
    }
}
