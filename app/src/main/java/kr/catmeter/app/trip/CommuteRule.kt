package kr.catmeter.app.trip

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class GeoPoint(val lat: Double, val lon: Double) {
    fun distanceMTo(o: GeoPoint): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(o.lat - lat)
        val dLon = Math.toRadians(o.lon - lon)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat)) * cos(Math.toRadians(o.lat)) * sin(dLon / 2).pow(2)
        return 2 * r * asin(sqrt(a))
    }
}

enum class CommuteWindow(val label: String, val from: LocalTime, val to: LocalTime) {
    MORNING("출근", LocalTime.of(7, 0), LocalTime.of(9, 0)),
    EVENING("퇴근", LocalTime.of(18, 0), LocalTime.of(20, 0)),
    ;

    fun contains(t: LocalTime) = !t.isBefore(from) && !t.isAfter(to)
}

/** 출퇴근 함께 타기 판정 결과. eligible 이 아니면 결제 없이 기록만 남긴다. */
data class CommuteCheck(
    val eligible: Boolean,
    val window: CommuteWindow?,
    /** 정산할 수 없는 이유 (eligible 이면 비어 있음) */
    val reasons: List<String>,
)

/**
 * 여객자동차 운수사업법 제81조 제1항 제1호(출퇴근 시간대 승용차 함께 타기)에 맞는지 앱이 확인할 수 있는 만큼 판정한다.
 *
 * 앱이 확인하는 것
 * - 평일(토·일·공휴일 제외) 오전 7~9시 또는 오후 6~8시. 출발과 도착이 모두 같은 시간대 안이어야 한다 (팀이 정한 보수적 기준).
 * - 집 ↔ 회사 이동: 내비에 등록된 집·회사 근처(반경 [NEAR_M])에서 출발해 다른 쪽 근처에 도착.
 *   헌재 2018헌바100 은 '주거지와 근무지 사이를 통상적인 경로로 이동'하는 경우로 좁게 해석했다.
 * - 같은 시간대에 한 번만 정산 (헌재: 하루 여러 차례 유상운송은 허용 취지를 벗어남).
 * 앱이 확인하지 못하는 것: 동승자도 출퇴근 중인지 → 정산 전에 운전자·동승자가 직접 확인한다.
 * 이 판정은 보조 장치이며 법적 판단을 대신하지 않는다.
 */
object CommuteRule {
    const val NEAR_M = 1_000.0

    fun windowOf(t: LocalDateTime): CommuteWindow? {
        if (!isWorkday(t.toLocalDate())) return null
        return CommuteWindow.entries.firstOrNull { it.contains(t.toLocalTime()) }
    }

    fun isWorkday(d: LocalDate): Boolean =
        d.dayOfWeek != DayOfWeek.SATURDAY && d.dayOfWeek != DayOfWeek.SUNDAY && d !in KoreanHolidays.dates

    fun check(
        start: LocalDateTime,
        end: LocalDateTime,
        from: GeoPoint?,
        to: GeoPoint?,
        home: GeoPoint?,
        office: GeoPoint?,
        alreadySettledInWindow: Boolean,
    ): CommuteCheck {
        val reasons = mutableListOf<String>()
        val window = windowOf(start)
        when {
            !isWorkday(start.toLocalDate()) -> reasons += "토·일·공휴일에는 정산할 수 없어요"
            window == null -> reasons += "평일 오전 7~9시, 오후 6~8시에 출발한 이동만 정산할 수 있어요"
            windowOf(end) != window || end.toLocalDate() != start.toLocalDate() ->
                reasons += "${window.label} 시간대(${window.from}~${window.to}) 안에 도착해야 정산할 수 있어요"
        }
        reasons += routeReasons(from, to, home, office)
        if (window != null && alreadySettledInWindow) reasons += "이번 ${window.label} 시간대에는 이미 정산했어요"
        return CommuteCheck(reasons.isEmpty(), window, reasons)
    }

    /** 집 ↔ 회사 이동인지. 문제가 없으면 빈 목록. */
    fun routeReasons(from: GeoPoint?, to: GeoPoint?, home: GeoPoint?, office: GeoPoint?): List<String> {
        if (home == null || office == null) return listOf("내비 즐겨찾기에 집과 회사를 등록해 주세요")
        if (from == null || to == null) return listOf("출발·도착 위치를 확인하지 못했어요")
        fun near(a: GeoPoint, b: GeoPoint) = a.distanceMTo(b) <= NEAR_M
        val homeToOffice = near(from, home) && near(to, office)
        val officeToHome = near(from, office) && near(to, home)
        return if (homeToOffice || officeToHome) emptyList() else listOf("집과 회사 사이 이동만 정산할 수 있어요")
    }
}

/**
 * 공휴일(대체공휴일 포함). 관공서의 공휴일에 관한 규정 기준으로 직접 정리한 목록이다.
 * 임시공휴일은 지정될 때마다 추가해야 한다. 실서비스에서는 공공데이터포털 '특일 정보' API 로 바꾼다.
 */
object KoreanHolidays {
    val dates: Set<LocalDate> = listOf(
        // 2026
        "2026-01-01", "2026-02-16", "2026-02-17", "2026-02-18", "2026-03-01", "2026-03-02",
        "2026-05-05", "2026-05-24", "2026-05-25", "2026-06-03", "2026-06-06", "2026-08-15", "2026-08-17",
        "2026-09-24", "2026-09-25", "2026-09-26", "2026-10-03", "2026-10-05", "2026-10-09", "2026-12-25",
        // 2027
        "2027-01-01", "2027-02-06", "2027-02-07", "2027-02-08", "2027-02-09", "2027-03-01",
        "2027-05-05", "2027-05-13", "2027-06-06", "2027-08-15", "2027-08-16",
        "2027-09-14", "2027-09-15", "2027-09-16", "2027-10-03", "2027-10-04", "2027-10-09", "2027-10-11",
        "2027-12-25", "2027-12-27",
    ).map(LocalDate::parse).toSet()
}
