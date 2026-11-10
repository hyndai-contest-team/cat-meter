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
    fun `belt-only seat confirms when the car leaves with the belt still off`() {
        val t = AlightTracker()
        t.update(0, mapOf(seat to beltOnlyOff), riders, stopped = true, moving = false, point = at(5f, 10))
        // 오래 서 있어도 좌석 센서가 없으면 시간만으로는 확정하지 않는다
        assertEquals(emptyList<AlightTracker.Alight>(), t.update(120_000, mapOf(seat to beltOnlyOff), riders, stopped = true, moving = false, point = at(5f, 10)))
        val out = t.update(125_000, mapOf(seat to beltOnlyOff), riders, stopped = false, moving = true, point = at(5.1f, 12))
        assertEquals(listOf(AlightTracker.Alight("민수", seat, at(5f, 10))), out)
    }

    @Test
    fun `belt-only seat buckled again before leaving is not alighting`() {
        val t = AlightTracker()
        t.update(0, mapOf(seat to beltOnlyOff), riders, stopped = true, moving = false, point = at(5f, 10))
        t.update(10_000, mapOf(seat to beltOnlyOn), riders, stopped = true, moving = false, point = at(5f, 10))
        assertEquals(emptyList<AlightTracker.Alight>(), t.update(15_000, mapOf(seat to beltOnlyOn), riders, stopped = false, moving = true, point = at(5.1f, 11)))
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
