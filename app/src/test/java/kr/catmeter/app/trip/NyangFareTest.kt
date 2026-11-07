package kr.catmeter.app.trip

import org.junit.Assert.assertEquals
import org.junit.Test

class NyangFareTest {

    @Test
    fun `distance rises 10 nyang per full 100m`() {
        assertEquals(0, NyangFare.of(0.099f, 0L).distanceNyang)
        assertEquals(10, NyangFare.of(0.1f, 0L).distanceNyang)
        assertEquals(70, NyangFare.of(0.7f, 0L).distanceNyang)   // float 0.7 이 699m 로 깎이지 않는다
        assertEquals(1_230, NyangFare.of(12.34f, 0L).distanceNyang)
    }

    @Test
    fun `time rises 10 nyang per full minute`() {
        assertEquals(0, NyangFare.of(0f, 59_999L).timeNyang)
        assertEquals(10, NyangFare.of(0f, 60_000L).timeNyang)
        assertEquals(250, NyangFare.of(0f, 25 * 60_000L + 30_000L).timeNyang)
    }

    @Test
    fun `total adds distance and time, negatives count as zero`() {
        assertEquals(1_480, NyangFare.of(12.34f, 25 * 60_000L).totalNyang)
        assertEquals(NyangFare(0, 0), NyangFare.of(-1f, -5L))
    }
}
