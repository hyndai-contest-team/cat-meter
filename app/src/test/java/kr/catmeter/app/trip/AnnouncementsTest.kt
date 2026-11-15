package kr.catmeter.app.trip

import kr.catmeter.app.data.BoardingClient.Charge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AnnouncementsTest {
    @Test
    fun `alight announcement names the person and no amount`() {
        assertEquals("민수님 하차를 확인했어요.", Announcements.alighted("민수"))
    }

    @Test
    fun `settle announcement gives the head count and the total`() {
        val charges = listOf(
            Charge("민수", 1_325, Charge.Status.PAID, "카카오페이"),
            Charge("지훈", 1_325, Charge.Status.PAID, "카드"),
        )
        assertEquals("도착했어요. 2명에게 2,650원을 정산했어요.", Announcements.settled(charges))
    }

    @Test
    fun `people to collect from directly are mentioned and those not sharing are not`() {
        val charges = listOf(
            Charge("민수", 1_000, Charge.Status.PAID, "카카오페이"),
            Charge("나인", 1_000, Charge.Status.NOT_BOARDED, null),
            Charge("수아", 0, Charge.Status.NOT_COMMUTING, null),
        )
        assertEquals("도착했어요. 1명에게 1,000원을 정산했어요. 1명은 직접 받아 주세요.", Announcements.settled(charges))
    }

    @Test
    fun `nothing is said when nobody was charged`() {
        assertNull(Announcements.settled(emptyList()))
        assertNull(Announcements.settled(listOf(Charge("나인", 500, Charge.Status.NOT_BOARDED, null))))
    }
}
