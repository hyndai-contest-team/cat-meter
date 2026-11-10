package kr.catmeter.app.trip

import java.security.SecureRandom

/**
 * 탑승 코드.
 * 탑승 QR 에는 탑승 등록 서버가 돌려준 주소(…/ride/코드)를 넣는다. 승객이 그 페이지에서 이름을 넣으면 이번 이동의 탑승자가 된다.
 * QR 에는 이번 이동을 가리키는 코드만 들어가고 이름·계좌 같은 개인정보는 넣지 않는다.
 * 코드 규칙은 서버(server/src/app.mjs CODE_PATTERN)와 같아야 한다.
 */
object Boarding {
    /** 헷갈리기 쉬운 0/O, 1/I/L 을 뺀 글자 */
    private const val ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"
    private const val CODE_LENGTH = 8
    private val random = SecureRandom()

    /** 이동마다 새로 만드는 탑승 코드. 지난 이동의 QR 로는 탈 수 없게 한다. */
    fun newCode(): String = buildString(CODE_LENGTH) {
        repeat(CODE_LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) }
    }

    /** 화면에 읽기 쉽게 보여 줄 때: ABCD-2345 */
    fun display(code: String): String = code.chunked(4).joinToString("-")
}
