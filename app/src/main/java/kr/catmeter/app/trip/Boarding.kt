package kr.catmeter.app.trip

import java.security.SecureRandom

/**
 * 탑승 QR 에 담는 내용.
 * 승객이 QR 을 찍으면 탑승 등록 페이지가 열리고, 이름을 넣으면 이번 이동의 탑승자로 확정된다.
 * QR 에는 이번 이동을 가리키는 코드만 넣고 이름·계좌 같은 개인정보는 넣지 않는다.
 */
object Boarding {
    /**
     * 탑승 등록 링크의 앞부분.
     * 아직 탑승 등록 웹이 없어서 앱 전용 주소를 쓴다. 웹을 만들면 https 주소로 바꾼다.
     */
    const val LINK_BASE = "catmeter://board"

    /** 헷갈리기 쉬운 0/O, 1/I/L 을 뺀 글자 */
    private const val ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"
    private const val CODE_LENGTH = 8
    private val random = SecureRandom()

    /** 이동마다 새로 만드는 탑승 코드. 지난 이동의 QR 로는 탈 수 없게 한다. */
    fun newCode(): String = buildString(CODE_LENGTH) {
        repeat(CODE_LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) }
    }

    fun link(code: String): String = "$LINK_BASE?ride=$code"

    /** 화면에 읽기 쉽게 보여 줄 때: ABCD-2345 */
    fun display(code: String): String = code.chunked(4).joinToString("-")
}
