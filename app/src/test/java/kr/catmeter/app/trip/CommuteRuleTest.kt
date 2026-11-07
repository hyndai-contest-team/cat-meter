package kr.catmeter.app.trip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class CommuteRuleTest {

    private val home = GeoPoint(37.5665, 126.9780)      // 시청 근처
    private val office = GeoPoint(37.4979, 127.0276)    // 강남역 근처
    private val nearHome = GeoPoint(37.5700, 126.9800)  // 약 400m

    private fun at(s: String) = LocalDateTime.parse(s)

    private fun check(start: String, end: String, from: GeoPoint? = home, to: GeoPoint? = office, settled: Boolean = false) =
        CommuteRule.check(at(start), at(end), from, to, home, office, settled)

    @Test
    fun `weekday morning home to office is eligible`() {
        val c = check("2026-11-09T07:40", "2026-11-09T08:30", from = nearHome)   // 월요일
        assertTrue(c.reasons.toString(), c.eligible)
        assertEquals(CommuteWindow.MORNING, c.window)
    }

    @Test
    fun `evening office to home is eligible, 8pm sharp still counts`() {
        assertTrue(check("2026-11-09T18:00", "2026-11-09T20:00", from = office, to = home).eligible)
    }

    @Test
    fun `outside window, weekend and holiday are not eligible`() {
        assertFalse(check("2026-11-09T10:00", "2026-11-09T10:40").eligible)   // 시간대 밖
        assertFalse(check("2026-11-07T08:00", "2026-11-07T08:40").eligible)   // 토요일
        assertFalse(check("2026-12-25T08:00", "2026-12-25T08:40").eligible)   // 성탄절(금)
        assertFalse(check("2026-10-05T08:00", "2026-10-05T08:40").eligible)   // 개천절 대체공휴일(월)
    }

    @Test
    fun `arriving after the window is not eligible`() {
        val c = check("2026-11-09T08:40", "2026-11-09T09:20")
        assertFalse(c.eligible)
        assertTrue(c.reasons.single().contains("안에 도착"))
    }

    @Test
    fun `route must connect home and office`() {
        assertFalse(check("2026-11-09T08:00", "2026-11-09T08:40", from = home, to = home).eligible)
        assertFalse(check("2026-11-09T08:00", "2026-11-09T08:40", from = null).eligible)
        val noBookmarks = CommuteRule.check(at("2026-11-09T08:00"), at("2026-11-09T08:40"), home, office, null, office, false)
        assertTrue(noBookmarks.reasons.single().contains("즐겨찾기"))
    }

    @Test
    fun `only one settlement per window`() {
        val c = check("2026-11-09T08:00", "2026-11-09T08:40", settled = true)
        assertFalse(c.eligible)
        assertTrue(c.reasons.single().contains("이미 정산"))
    }
}
