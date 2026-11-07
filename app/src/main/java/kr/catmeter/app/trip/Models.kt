package kr.catmeter.app.trip

/**
 * 고양이 털 색. 색 값은 ARGB(Long)로 두어 UI 의존 없이 저장·테스트한다.
 * main 기본 / shade 그늘 / light 배·주둥이 / striped 줄무늬 여부 (art/cat/README.md 의 임시 색과 짝을 이룬다)
 */
enum class Fur(val label: String, val main: Long, val shade: Long, val light: Long, val striped: Boolean) {
    CHEESE("치즈", 0xFFF6B85C, 0xFFD98C3A, 0xFFFFF1DC, striped = true),
    MACKEREL("고등어", 0xFFA9B1BB, 0xFF6E7782, 0xFFF1F2F4, striped = true),
    BLACK("까망", 0xFF43414A, 0xFF26252B, 0xFF6B6873, striped = false),
    WHITE("하양", 0xFFFBF8F2, 0xFFDCD3C6, 0xFFFFFFFF, striped = false),
}

enum class Accessory(val label: String) {
    HAT("모자"),
    SUNGLASSES("선글라스"),
    RIBBON("리본"),
    SCARF("목도리"),
}

/** 출발 전에 정하는 오늘의 고양이 */
data class CatProfile(
    val name: String = "나비",
    val fur: Fur = Fur.CHEESE,
    val accessories: Set<Accessory> = emptySet(),
)

/** 도착 후 영수증에 남기는 이동 기록 */
data class TripRecord(
    val id: Long,
    val startedAtMs: Long,
    val endedAtMs: Long,
    val distanceKm: Float,
    val movingMs: Long,
    val stoppedMs: Long,
    val stopCount: Int,
    val distanceSource: TripTracker.DistanceSource,
    val cat: CatProfile,
    val passengers: List<String>,
    /** 이 고양이와 함께한 몇 번째 이동인지 */
    val rideNumber: Int,
    /** 거리당 에너지 비용 기준 (연비·전비, 단가) */
    val energy: EnergyProfile = EnergyProfile.DEFAULT_FUEL,
    /** 내비 목적지 이름. 내비 연동이 없으면 null */
    val destinationName: String? = null,
    val from: GeoPoint? = null,
    val to: GeoPoint? = null,
    val commute: CommuteCheck = CommuteCheck(false, null, emptyList()),
    /** 모의 결제를 마친 시각 */
    val settledAtMs: Long? = null,
) {
    val durationMs: Long get() = (endedAtMs - startedAtMs).coerceAtLeast(0L)
    val bill: TripBill get() = TripBill.of(distanceKm, durationMs, energy)
}

/**
 * 정차 중 고양이 상태. 고양이는 정차 중에만 보이므로 주행 상태는 없다.
 * 속도만으로는 신호 대기·정체를 구분할 수 없어 P단이 아니면 '정차'로 묶는다.
 */
enum class CatActivity(val caption: String) {
    PARKED("식빵 굽는 중"),
    STOPPED("앞발 핥으며 쉬는 중"),
    ;

    companion object {
        fun of(parked: Boolean): CatActivity = if (parked) PARKED else STOPPED
    }
}
