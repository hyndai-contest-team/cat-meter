package kr.catmeter.app.trip

import org.junit.Assert.assertEquals
import org.junit.Test

class SeatPromptsTest {
    private val empty = SeatSignal(occupied = false, buckled = false)
    private val sat = SeatSignal(occupied = true, buckled = false)
    private val buckled = SeatSignal(occupied = null, buckled = true)

    private fun seats(vararg p: Pair<SeatPosition, SeatSignal>) = mapOf(*p)

    @Test
    fun `newly taken seat is prompted, by belt or by occupancy`() {
        val before = seats(SeatPosition.ROW_1_RIGHT to empty, SeatPosition.ROW_2_LEFT to empty)
        val after = seats(SeatPosition.ROW_1_RIGHT to sat, SeatPosition.ROW_2_LEFT to buckled)
        assertEquals(listOf(SeatPosition.ROW_1_RIGHT, SeatPosition.ROW_2_LEFT), SeatPrompts.update(emptyList(), before, after, emptySet()))
    }

    @Test
    fun `seat already taken at start is prompted once and not again while still taken`() {
        val now = seats(SeatPosition.ROW_2_RIGHT to sat)
        val first = SeatPrompts.update(emptyList(), emptyMap(), now, emptySet())
        assertEquals(listOf(SeatPosition.ROW_2_RIGHT), first)
        // 운전자가 '나중에' → 목록에서 뺀 뒤, 계속 앉아 있으면 다시 뜨지 않는다
        assertEquals(emptyList<SeatPosition>(), SeatPrompts.update(emptyList(), now, now, emptySet()))
    }

    @Test
    fun `boarded or vacated seats drop out`() {
        val before = seats(SeatPosition.ROW_1_RIGHT to sat, SeatPosition.ROW_2_LEFT to sat)
        val after = seats(SeatPosition.ROW_1_RIGHT to sat, SeatPosition.ROW_2_LEFT to empty)
        val prompts = listOf(SeatPosition.ROW_1_RIGHT, SeatPosition.ROW_2_LEFT)
        // 조수석은 탑승 등록함, 뒷좌석 왼쪽은 비었음
        assertEquals(emptyList<SeatPosition>(), SeatPrompts.update(prompts, before, after, setOf(SeatPosition.ROW_1_RIGHT)))
    }

    @Test
    fun `driver seat and arrival phase never prompt`() {
        val after = seats(SeatPosition.ROW_1_LEFT to sat, SeatPosition.ROW_2_LEFT to sat)
        assertEquals(listOf(SeatPosition.ROW_2_LEFT), SeatPrompts.update(emptyList(), emptyMap(), after, emptySet()))
        assertEquals(emptyList<SeatPosition>(), SeatPrompts.update(emptyList(), emptyMap(), after, emptySet(), allowNew = false))
    }
}
