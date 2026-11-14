package kr.catmeter.app.trip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlightTrackerTest {
    private val seat = SeatPosition.ROW_2_LEFT
    private val riders = mapOf(seat to "민수")
    private val sitting = SeatSignal(occupied = true, buckled = true)
    private val unbuckledStillSitting = SeatSignal(occupied = true, buckled = false)
    private val empty = SeatSignal(occupied = false, buckled = false)
    private val beltOnlyOff = SeatSignal(occupied = null, buckled = false)
    private val beltOnlyOn = SeatSignal(occupied = null, buckled = true)

    private fun at(km: Float, min: Long) = JoinPoint(km, min * 60_000L)

    @Test
    fun `seat empty while stopped confirms after 30s at the point it emptied`() {
        val t = AlightTracker()
        assertEquals(emptyList<AlightTracker.Alight>(), t.update(0, mapOf(seat to empty), riders, stopped = true, moving = false, point = at(5f, 10)))
        assertEquals(setOf("민수"), t.pending)
        assertEquals(emptyList<AlightTracker.Alight>(), t.update(29_000, mapOf(seat to empty), riders, stopped = true, moving = false, point = at(5f, 10)))
        // 30초가 지나면 확정. 하차 지점은 비었던 순간(5km·10분)이다. 그 사이 더 간 거리는 넣지 않는다
        val out = t.update(30_000, mapOf(seat to empty), riders, stopped = true, moving = false, point = at(6f, 11))
        assertEquals(listOf(AlightTracker.Alight("민수", seat, at(5f, 10))), out)
        assertTrue(t.pending.isEmpty())
    }

    @Test
    fun `sitting again before confirm cancels`() {
        val t = AlightTracker()
        t.update(0, mapOf(seat to empty), riders, stopped = true, moving = false, point = at(5f, 10))
        t.update(10_000, mapOf(seat to sitting), riders, stopped = true, moving = false, point = at(5f, 10))
        assertEquals(emptyList<AlightTracker.Alight>(), t.update(40_000, mapOf(seat to sitting), riders, stopped = true, moving = false, point = at(5f, 10)))
        assertTrue(t.pending.isEmpty())
    }

    @Test
    fun `unbuckling while still sitting is not alighting`() {
        val t = AlightTracker()
        t.update(0, mapOf(seat to unbuckledStillSitting), riders, stopped = true, moving = false, point = at(5f, 10))
        assertEquals(emptyList<AlightTracker.Alight>(), t.update(60_000, mapOf(seat to unbuckledStillSitting), riders, stopped = true, moving = false, point = at(5f, 10)))
    }

    @Test
    fun `empty while moving is not a candidate until the car stops`() {
        val t = AlightTracker()
        t.update(0, mapOf(seat to empty), riders, stopped = false, moving = true, point = at(5f, 10))
        assertTrue(t.pending.isEmpty())
        t.update(5_000, mapOf(seat to empty), riders, stopped = true, moving = false, point = at(6f, 11))
        val out = t.update(35_000, mapOf(seat to empty), riders, stopped = true, moving = false, point = at(6f, 11))
        assertEquals(listOf(AlightTracker.Alight("민수", seat, at(6f, 11))), out)
    }

    @Test
    fun `belt-only seat confirms 5s after the car leaves with the belt still off`() {
        val t = AlightTracker()
        t.update(0, mapOf(seat to beltOnlyOff), riders, stopped = true, moving = false, point = at(5f, 10))
        // 오래 서 있어도 좌석 센서가 없으면 시간만으로는 확정하지 않는다
        assertEquals(emptyList<AlightTracker.Alight>(), t.update(120_000, mapOf(seat to beltOnlyOff), riders, stopped = true, moving = false, point = at(5f, 10)))
        assertEquals(emptyList<AlightTracker.Alight>(), t.update(125_000, mapOf(seat to beltOnlyOff), riders, stopped = false, moving = true, point = at(5.1f, 12)))
        assertEquals(emptyList<AlightTracker.Alight>(), t.update(129_000, mapOf(seat to beltOnlyOff), riders, stopped = false, moving = true, point = at(5.2f, 12)))
        val out = t.update(130_000, mapOf(seat to beltOnlyOff), riders, stopped = false, moving = true, point = at(5.3f, 12))
        assertEquals(listOf(AlightTracker.Alight("민수", seat, at(5f, 10))), out)
    }

    @Test
    fun `belt-only seat buckled again before leaving is not alighting`() {
        val t = AlightTracker()
        t.update(0, mapOf(seat to beltOnlyOff), riders, stopped = true, moving = false, point = at(5f, 10))
        t.update(10_000, mapOf(seat to beltOnlyOn), riders, stopped = true, moving = false, point = at(5f, 10))
        assertEquals(emptyList<AlightTracker.Alight>(), t.update(30_000, mapOf(seat to beltOnlyOn), riders, stopped = false, moving = true, point = at(5.1f, 11)))
    }

    // ── 문 신호가 있는 차 ─────────────────────────────────────────

    private val closed = mapOf(seat to DoorSignal(open = false))
    private fun openedAt(ms: Long) = mapOf(seat to DoorSignal(open = false, lastOpenMs = ms))
    private val open = mapOf(seat to DoorSignal(open = true, lastOpenMs = 0))   // 0초에 열려 지금도 열려 있다

    @Test
    fun `seat empty without the door opening is never confirmed`() {
        val t = AlightTracker()
        t.update(0, mapOf(seat to empty), riders, stopped = true, moving = false, point = at(5f, 10), doors = closed)
        // 문이 열리기 전에는 '하차 확인 중'으로도 보여 주지 않는다
        assertTrue(t.pending.isEmpty())
        assertEquals(emptyList<AlightTracker.Alight>(), t.update(120_000, mapOf(seat to empty), riders, stopped = true, moving = false, point = at(5f, 10), doors = closed))
        // 문을 열지 않은 채 출발해도 확정하지 않는다 (자리를 옮겼거나 센서가 흔들린 경우)
        t.update(121_000, mapOf(seat to empty), riders, stopped = false, moving = true, point = at(5.1f, 12), doors = closed)
        assertEquals(emptyList<AlightTracker.Alight>(), t.update(140_000, mapOf(seat to empty), riders, stopped = false, moving = true, point = at(5.5f, 13), doors = closed))
    }

    @Test
    fun `seat empty and door opened confirms after 30s counted from when both hold`() {
        val t = AlightTracker()
        t.update(0, mapOf(seat to empty), riders, stopped = true, moving = false, point = at(5f, 10), doors = closed)
        // 10초에 문이 열렸다 닫혔다. 그것을 처음 본 때(11초)부터 30초를 센다
        t.update(11_000, mapOf(seat to empty), riders, stopped = true, moving = false, point = at(5f, 10), doors = openedAt(10_000))
        assertEquals(emptyList<AlightTracker.Alight>(), t.update(40_000, mapOf(seat to empty), riders, stopped = true, moving = false, point = at(5f, 10), doors = openedAt(10_000)))
        val out = t.update(41_000, mapOf(seat to empty), riders, stopped = true, moving = false, point = at(5f, 10), doors = openedAt(10_000))
        assertEquals(listOf(AlightTracker.Alight("민수", seat, at(5f, 10))), out)
    }

    @Test
    fun `door opened first and then the seat emptied also counts`() {
        val t = AlightTracker()
        t.update(0, mapOf(seat to sitting), riders, stopped = true, moving = false, point = at(5f, 10), doors = closed)
        t.update(5_000, mapOf(seat to sitting), riders, stopped = true, moving = false, point = at(5f, 10), doors = open)
        t.update(8_000, mapOf(seat to empty), riders, stopped = true, moving = false, point = at(5f, 10), doors = openedAt(7_000))
        val out = t.update(38_000, mapOf(seat to empty), riders, stopped = true, moving = false, point = at(5f, 10), doors = openedAt(7_000))
        assertEquals(listOf(AlightTracker.Alight("민수", seat, at(5f, 10))), out)
    }

    @Test
    fun `leaving with the seat empty after the door opened confirms 5s after departure`() {
        val t = AlightTracker()
        t.update(0, mapOf(seat to empty), riders, stopped = true, moving = false, point = at(5f, 10), doors = open)
        t.update(8_000, mapOf(seat to empty), riders, stopped = false, moving = true, point = at(5f, 10), doors = openedAt(6_000))
        assertEquals(emptyList<AlightTracker.Alight>(), t.update(12_000, mapOf(seat to empty), riders, stopped = false, moving = true, point = at(5.1f, 10), doors = openedAt(6_000)))
        val out = t.update(13_000, mapOf(seat to empty), riders, stopped = false, moving = true, point = at(5.1f, 10), doors = openedAt(6_000))
        assertEquals(listOf(AlightTracker.Alight("민수", seat, at(5f, 10))), out)
    }

    @Test
    fun `stopping again within 5s of departure restarts the departure wait`() {
        val t = AlightTracker()
        t.update(0, mapOf(seat to empty), riders, stopped = true, moving = false, point = at(5f, 10), doors = open)
        t.update(8_000, mapOf(seat to empty), riders, stopped = false, moving = true, point = at(5f, 10), doors = openedAt(6_000))
        t.update(10_000, mapOf(seat to empty), riders, stopped = true, moving = false, point = at(5f, 10), doors = openedAt(6_000))
        assertEquals(emptyList<AlightTracker.Alight>(), t.update(14_000, mapOf(seat to empty), riders, stopped = true, moving = false, point = at(5f, 10), doors = openedAt(6_000)))
        // 그래도 비어 있는 채 30초가 지나면 확정한다
        assertEquals(1, t.update(30_000, mapOf(seat to empty), riders, stopped = true, moving = false, point = at(5f, 10), doors = openedAt(6_000)).size)
    }

    @Test
    fun `a door opened at an earlier stop does not count for this stop`() {
        val t = AlightTracker()
        t.update(0, mapOf(seat to sitting), riders, stopped = true, moving = false, point = at(1f, 2), doors = open)
        t.update(10_000, mapOf(seat to sitting), riders, stopped = false, moving = true, point = at(2f, 3), doors = openedAt(5_000))
        t.update(60_000, mapOf(seat to empty), riders, stopped = true, moving = false, point = at(5f, 10), doors = openedAt(5_000))
        assertEquals(emptyList<AlightTracker.Alight>(), t.update(120_000, mapOf(seat to empty), riders, stopped = true, moving = false, point = at(5f, 10), doors = openedAt(5_000)))
    }

    // ── 코드 리뷰에서 재현된 경우 ─────────────────────────────────

    @Test
    fun `a candidate whose door never opened is dropped on departure and does not confirm at a later stop`() {
        val t = AlightTracker()
        // 1km 지점 정차: 문은 닫힌 채 자리만 빈 것으로 보인다 (센서 흔들림, 자리 옮김)
        t.update(0, mapOf(seat to empty), riders, stopped = true, moving = false, point = at(1f, 1), doors = closed)
        t.update(10_000, mapOf(seat to empty), riders, stopped = false, moving = true, point = at(1.1f, 2), doors = closed)
        // 10분 뒤 다른 정차에서 문이 열린다. 예전 지점(1km)으로 확정되면 안 된다
        t.update(600_000, mapOf(seat to empty), riders, stopped = true, moving = false, point = at(9f, 12), doors = closed)
        t.update(605_000, mapOf(seat to empty), riders, stopped = true, moving = false, point = at(9f, 12), doors = openedAt(604_000))
        val out = t.update(636_000, mapOf(seat to empty), riders, stopped = true, moving = false, point = at(9f, 12), doors = openedAt(604_000))
        assertEquals(listOf(AlightTracker.Alight("민수", seat, at(9f, 12))), out)
    }

    @Test
    fun `a door left open while driving does not count as opened at this stop`() {
        val t = AlightTracker()
        val ajar = mapOf(seat to DoorSignal(open = true, lastOpenMs = 1_000))   // 덜 닫힌 채 달려온 문
        t.update(100_000, mapOf(seat to beltOnlyOn), riders, stopped = false, moving = true, point = at(3f, 5), doors = ajar)
        t.update(200_000, mapOf(seat to beltOnlyOff), riders, stopped = true, moving = false, point = at(3f, 6), doors = ajar)
        t.update(210_000, mapOf(seat to beltOnlyOff), riders, stopped = false, moving = true, point = at(3.1f, 6), doors = ajar)
        assertEquals(emptyList<AlightTracker.Alight>(), t.update(220_000, mapOf(seat to beltOnlyOff), riders, stopped = false, moving = true, point = at(3.3f, 7), doors = ajar))
    }

    @Test
    fun `a door opened just before the car came to a full stop still counts`() {
        val t = AlightTracker()
        // 0.9초에 문이 열리고, 1.0초 틱에서 처음 정차를 본다. 좌석 신호는 늦게 오고 그때는 문이 이미 닫혀 있다
        t.update(1_000, mapOf(seat to sitting), riders, stopped = true, moving = false, point = at(5f, 10), doors = openedAt(900))
        t.update(4_000, mapOf(seat to empty), riders, stopped = true, moving = false, point = at(5f, 10), doors = openedAt(900))
        assertEquals(setOf("민수"), t.pending)
        val out = t.update(34_000, mapOf(seat to empty), riders, stopped = true, moving = false, point = at(5f, 10), doors = openedAt(900))
        assertEquals(listOf(AlightTracker.Alight("민수", seat, at(5f, 10))), out)
    }

    @Test
    fun `sitting again within 5s of departure cancels`() {
        val t = AlightTracker()
        t.update(0, mapOf(seat to empty), riders, stopped = true, moving = false, point = at(5f, 10), doors = openedAt(0))
        t.update(8_000, mapOf(seat to empty), riders, stopped = false, moving = true, point = at(5f, 10), doors = openedAt(0))
        t.update(10_000, mapOf(seat to sitting), riders, stopped = false, moving = true, point = at(5f, 10), doors = openedAt(0))
        assertEquals(emptyList<AlightTracker.Alight>(), t.update(20_000, mapOf(seat to sitting), riders, stopped = false, moving = true, point = at(5.2f, 11), doors = openedAt(0)))
        assertTrue(t.pending.isEmpty())
    }

    @Test
    fun `an undone alight is not judged again even if the door opens`() {
        val t = AlightTracker()
        t.suppress(seat)
        t.update(0, mapOf(seat to empty), riders, stopped = true, moving = false, point = at(5f, 10), doors = openedAt(0))
        assertEquals(emptyList<AlightTracker.Alight>(), t.update(60_000, mapOf(seat to empty), riders, stopped = true, moving = false, point = at(5f, 10), doors = openedAt(50_000)))
        assertTrue(t.pending.isEmpty())
    }

    @Test
    fun `sitting again after the door opened cancels`() {
        val t = AlightTracker()
        t.update(0, mapOf(seat to empty), riders, stopped = true, moving = false, point = at(5f, 10), doors = open)
        t.update(10_000, mapOf(seat to sitting), riders, stopped = true, moving = false, point = at(5f, 10), doors = openedAt(9_000))
        assertEquals(emptyList<AlightTracker.Alight>(), t.update(60_000, mapOf(seat to sitting), riders, stopped = true, moving = false, point = at(5f, 10), doors = openedAt(9_000)))
        assertTrue(t.pending.isEmpty())
    }

    @Test
    fun `after a wrong alight is undone the seat waits until someone sits again`() {
        val t = AlightTracker()
        t.suppress(seat)
        t.update(0, mapOf(seat to empty), riders, stopped = true, moving = false, point = at(5f, 10))
        assertEquals(emptyList<AlightTracker.Alight>(), t.update(60_000, mapOf(seat to empty), riders, stopped = true, moving = false, point = at(5f, 10)))
        // 다시 앉았다가 비우면 다시 판단한다
        t.update(61_000, mapOf(seat to sitting), riders, stopped = true, moving = false, point = at(5f, 10))
        t.update(62_000, mapOf(seat to empty), riders, stopped = true, moving = false, point = at(5f, 10))
        assertEquals(1, t.update(92_000, mapOf(seat to empty), riders, stopped = true, moving = false, point = at(5f, 10)).size)
    }

    @Test
    fun `no sensor value or no rider means no decision`() {
        val t = AlightTracker()
        assertEquals(emptyList<AlightTracker.Alight>(), t.update(0, emptyMap(), riders, stopped = true, moving = false, point = at(5f, 10)))
        assertEquals(emptyList<AlightTracker.Alight>(), t.update(0, mapOf(seat to empty), emptyMap(), stopped = true, moving = false, point = at(5f, 10)))
        assertTrue(t.pending.isEmpty())
    }
}
