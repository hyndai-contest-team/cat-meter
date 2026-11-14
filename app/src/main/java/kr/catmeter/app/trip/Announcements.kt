package kr.catmeter.app.trip

import kr.catmeter.app.data.BoardingClient
import java.util.Locale

/** 안내 음성으로 읽을 문장. 주행 중에도 들리므로 한 문장으로 짧게 만든다. */
object Announcements {

    /** 중간 하차가 확정됐을 때. 금액은 취소할 수 있는 시간이 지난 뒤에 정해지므로 말하지 않는다. */
    fun alighted(name: String): String = "${name}님 하차를 확인했어요."

    /**
     * 도착해 자동결제를 마쳤을 때. 결제된 사람이 없으면 null (읽지 않는다).
     * 사람별 금액은 화면과 휴대폰에 있으므로 인원과 합계만 말한다.
     */
    fun settled(charges: List<BoardingClient.Charge>): String? {
        val paid = charges.filter { it.status == BoardingClient.Charge.Status.PAID && it.amount > 0 }
        if (paid.isEmpty()) return null
        val total = String.format(Locale.KOREA, "%,d", paid.sumOf { it.amount })
        val unpaid = charges.count {
            it.status == BoardingClient.Charge.Status.NO_METHOD || it.status == BoardingClient.Charge.Status.NOT_BOARDED
        }
        return "도착했어요. ${paid.size}명에게 ${total}원을 정산했어요." +
            if (unpaid > 0) " ${unpaid}명은 직접 받아 주세요." else ""
    }
}
