package kr.catmeter.app.trip

import org.junit.Assert.assertEquals
import org.junit.Test

class TollTrackerTest {
    private fun at(km: Float) = JoinPoint(km, (km * 60_000).toLong())

    @Test
    fun `toll is recorded as each gate is passed and adds up to the route toll`() {
        val t = TollTracker()
        t.onRoute(4_700)
        t.onGates(at(0f), listOf(2.9f, 9.6f, 30f))
        assertEquals(emptyList<TollEvent>(), t.update(at(2f)))
        assertEquals(listOf(TollEvent(at(3f), 1_566)), t.update(at(3f)))        // 4,700 / 3
        assertEquals(listOf(TollEvent(at(10f), 1_567)), t.update(at(10f)))      // 3,134 / 2
        assertEquals(listOf(TollEvent(at(31f), 1_567)), t.update(at(31f)))      // 마지막 요금소가 나머지를 모두
        assertEquals(4_700, t.events.sumOf { it.won })
        assertEquals(emptyList<TollEvent>(), t.update(at(50f)))
    }

    @Test
    fun `ending before the last gate records only the gates passed`() {
        val t = TollTracker()
        t.onRoute(3_000)
        t.onGates(at(0f), listOf(5f, 20f))
        t.update(at(6f))
        assertEquals(1_500, t.events.sumOf { it.won })
    }

    @Test
    fun `gate positions count from where the list was received`() {
        val t = TollTracker()
        t.onRoute(2_000)
        // 12km 를 달린 뒤에 경로를 시작했다. 요금소는 거기서 3km 앞이다
        t.onGates(at(12f), listOf(3f))
        assertEquals(emptyList<TollEvent>(), t.update(at(14f)))
        assertEquals(listOf(TollEvent(at(15f), 2_000)), t.update(at(15f)))
    }

    @Test
    fun `a shorter list from the navi means a gate was passed`() {
        val t = TollTracker()
        t.onRoute(2_000)
        t.onGates(at(0f), listOf(5f, 20f))
        // 달린 거리로는 아직인데 내비 목록에서 요금소 하나가 빠졌다 (내비가 더 정확하다)
        assertEquals(listOf(TollEvent(at(4.6f), 1_000)), t.onGates(at(4.6f), listOf(15.2f)))
        // 같은 요금소를 달린 거리로 다시 세지 않는다
        assertEquals(emptyList<TollEvent>(), t.update(at(6f)))
        assertEquals(listOf(TollEvent(at(20f), 1_000)), t.update(at(20f)))
    }

    @Test
    fun `a route without tolls or without gates records nothing`() {
        val free = TollTracker()
        free.onRoute(0)
        free.onGates(at(0f), listOf(5f))
        assertEquals(emptyList<TollEvent>(), free.update(at(10f)))

        val noGates = TollTracker()
        noGates.onRoute(1_900)
        assertEquals(emptyList<TollEvent>(), noGates.update(at(10f)))
    }
}
