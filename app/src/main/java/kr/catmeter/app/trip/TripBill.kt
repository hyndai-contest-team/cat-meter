package kr.catmeter.app.trip

import kotlin.math.floor
import kotlin.math.roundToInt

/** 연료 종류. 차량 API 가 종류를 알려 주지 않아, 전비·연비 중 어느 값이 들어오는지로 정한다. */
enum class EnergyKind(val label: String, val unit: String) {
    EV("충전비", "kWh"),
    FUEL("주유비", "L"),
}

/**
 * 거리당 에너지 비용 계산 기준.
 * @param kmPerUnit 전비(km/kWh) 또는 연비(km/L)
 * @param wonPerUnit 충전 단가(원/kWh) 또는 유가(원/L)
 * @param fromVehicle 연비·전비를 차량(Pleos Vehicle SDK)에서 받았는지. false 면 기본값.
 */
data class EnergyProfile(
    val kind: EnergyKind,
    val kmPerUnit: Float,
    val wonPerUnit: Int,
    val fromVehicle: Boolean,
) {
    companion object {
        /** 차량 값을 못 받을 때 쓰는 기본값. 단가는 팀 합의 전 임시값이다. */
        val DEFAULT_EV = EnergyProfile(EnergyKind.EV, kmPerUnit = 5.0f, wonPerUnit = EV_WON_PER_KWH, fromVehicle = false)
        val DEFAULT_FUEL = EnergyProfile(EnergyKind.FUEL, kmPerUnit = 12.0f, wonPerUnit = FUEL_WON_PER_L, fromVehicle = false)

        const val EV_WON_PER_KWH = 347
        const val FUEL_WON_PER_L = 1_700

        /** 차량에서 받은 전비·연비로 기준을 만든다. 범위를 벗어난 값(단위 착오·센서 오류)은 버리고 null. */
        fun fromVehicle(kind: EnergyKind, kmPerUnit: Float?): EnergyProfile? {
            val v = kmPerUnit ?: return null
            val range = if (kind == EnergyKind.EV) 1f..15f else 3f..40f
            if (v.isNaN() || v !in range) return null
            val won = if (kind == EnergyKind.EV) EV_WON_PER_KWH else FUEL_WON_PER_L
            return EnergyProfile(kind, v, won, fromVehicle = true)
        }
    }
}

/** 나눠 낸 결과: 동승자별 금액과 운전자 몫 */
data class CostSplit(
    /** 동승자별 낼 금액(원). 원 아래는 버린다. 나누는 인원에 넣지 않은 사람은 0 */
    val passengers: Map<String, Int>,
    /** 운전자가 부담하는 금액(원). 실비에서 동승자들이 낸 돈을 뺀 나머지라, 버린 끝전도 운전자가 낸다 */
    val driverWon: Int,
)

/**
 * 이동 한 번의 금액.
 * - 실비([costWon]): 주유비·충전비. 결제는 이것만 나눈다. 운전자를 포함한 1/n 이다.
 *   통행료·주차비도 실비지만 차량이 금액을 주지 않아 아직 넣지 못한다 (Vehicle SDK Toll 은 안내 음량만 있다).
 * - 냥([meter]): 거리·시간 미터. 고양 영수증에 남기는 기록이고 결제에는 쓰지 않는다.
 * 정산은 출퇴근 함께 타기로 판정된 이동에서만 한다(CommuteRule).
 */
data class TripBill(
    val meter: NyangFare,
    val energyWon: Int,
    val energy: EnergyProfile,
) {
    /** 기록용 냥 (거리 + 시간). 결제 금액이 아니다 */
    val meterNyang: Int get() = meter.totalNyang

    /** 나눠 낼 실비 합계(원) */
    val costWon: Int get() = energyWon

    /**
     * 처음부터 끝까지 함께 탔을 때 동승자 한 사람이 낼 금액. 운전자도 한 사람으로 센다.
     * 원 아래는 버린다. 동승자가 없으면 null.
     */
    fun perPerson(passengerCount: Int): Int? {
        if (passengerCount <= 0) return null
        return costWon / (passengerCount + 1)
    }

    companion object {
        /**
         * 실비를 운전자를 포함해 나눈다. 각자 탄 구간만 낸다.
         * 탑승·하차 시점마다 구간을 나누고, 각 구간의 실비(그 시점까지 누적 실비의 차이)를
         * 그 구간에 타고 있던 사람과 운전자가 똑같이 나눈다. 아무도 타지 않은 구간은 운전자 몫이다.
         * 동승자 금액은 원 아래를 버리고, 운전자가 나머지를 낸다. 올리면 동승자들이 낸 합계가 실비보다 많아져
         * 운전자에게 돈이 남기 때문이다 (실비 분담은 운전자에게 남는 돈이 없어야 한다).
         * 먼저 내린 사람의 금액은 그 뒤에 누가 타고 내리든 바뀌지 않는다 (지난 구간만 쓰므로). 그래서 내리자마자 결제할 수 있다.
         * @param joins 중간에 탄 사람의 탑승 지점. 없는 사람은 출발부터 탔다.
         * @param leaves 먼저 내린 사람의 하차 지점. 없는 사람은 도착까지 탔다.
         * @param notSharing 나누는 인원에 넣지 않는 사람 (출퇴근 길이 아니라고 고른 사람). 금액은 0 이고,
         *   그 사람 몫은 나머지 사람과 운전자가 나눈다.
         */
        fun split(
            distanceKm: Float,
            elapsedMs: Long,
            energy: EnergyProfile,
            passengers: List<String>,
            joins: Map<String, JoinPoint>,
            leaves: Map<String, JoinPoint> = emptyMap(),
            notSharing: Set<String> = emptySet(),
        ): CostSplit {
            val end = JoinPoint(distanceKm.coerceAtLeast(0f), elapsedMs.coerceAtLeast(0L))
            val total = of(end.distanceKm, end.elapsedMs, energy).costWon
            val sharing = passengers.filter { it !in notSharing }
            fun startOf(name: String): JoinPoint = joins[name]?.let { minOf(it, end) } ?: JoinPoint.START
            fun endOf(name: String): JoinPoint = leaves[name]?.let { maxOf(minOf(it, end), startOf(name)) } ?: end
            fun costAt(p: JoinPoint): Int = of(p.distanceKm, p.elapsedMs, energy).costWon

            val points = (sharing.map(::startOf) + sharing.map(::endOf) + JoinPoint.START + end).distinct().sorted()
            val owed = passengers.associateWith { 0.0 }.toMutableMap()
            points.zipWithNext { from, to ->
                val riders = sharing.filter { startOf(it) <= from && endOf(it) >= to }
                if (riders.isEmpty()) return@zipWithNext
                val each = (costAt(to) - costAt(from)).coerceAtLeast(0).toDouble() / (riders.size + 1)   // +1 = 운전자
                riders.forEach { owed[it] = owed.getValue(it) + each }
            }
            // 부동소수 오차로 1원이 깎이지 않게 아주 작은 값을 더하고 버린다
            val paid = owed.mapValues { (_, v) -> floor(v + 1e-6).toInt().coerceAtLeast(0) }
            return CostSplit(paid, driverWon = (total - paid.values.sum()).coerceAtLeast(0))
        }

        /** 동승자별 낼 금액만 ([split]) */
        fun shares(
            distanceKm: Float,
            elapsedMs: Long,
            energy: EnergyProfile,
            passengers: List<String>,
            joins: Map<String, JoinPoint>,
            leaves: Map<String, JoinPoint> = emptyMap(),
            notSharing: Set<String> = emptySet(),
        ): Map<String, Int> = split(distanceKm, elapsedMs, energy, passengers, joins, leaves, notSharing).passengers

        fun of(distanceKm: Float, elapsedMs: Long, energy: EnergyProfile): TripBill {
            val km = distanceKm.coerceAtLeast(0f)
            val energyWon = km / energy.kmPerUnit * energy.wonPerUnit
            return TripBill(
                meter = NyangFare.of(km, elapsedMs),
                energyWon = energyWon.roundToInt(),
                energy = energy,
            )
        }
    }
}

/** 중간에 탄 사람의 탑승 지점: 출발 후 거리와 시간 */
data class JoinPoint(val distanceKm: Float, val elapsedMs: Long) : Comparable<JoinPoint> {
    override fun compareTo(other: JoinPoint): Int =
        compareValuesBy(this, other, JoinPoint::elapsedMs, JoinPoint::distanceKm)

    companion object {
        val START = JoinPoint(0f, 0L)
    }
}
