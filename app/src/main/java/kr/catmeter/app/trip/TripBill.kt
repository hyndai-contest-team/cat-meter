package kr.catmeter.app.trip

import kotlin.math.ceil
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

/**
 * 냥수증 요금 = 미터 요금(거리·시간 냥) + 에너지 비용(주유비·충전비).
 * 1냥 = 1원으로 본다. 정산은 출퇴근 함께 타기로 판정된 이동에서만 한다(CommuteRule).
 */
data class TripBill(
    val meter: NyangFare,
    val energyNyang: Int,
    val energy: EnergyProfile,
) {
    val totalNyang: Int get() = meter.totalNyang + energyNyang

    /** 운전자를 뺀 동승자 1인당 금액. 10냥 단위로 올린다. 동승자가 없으면 null. */
    fun perPassenger(passengerCount: Int): Int? {
        if (passengerCount <= 0) return null
        return (ceil(totalNyang / passengerCount / 10.0) * 10).toInt()
    }

    companion object {
        /**
         * 사람별 나눠 낼 금액 (운전자 제외). 중간에 탄 사람은 탄 뒤 구간만 낸다.
         * 탑승 시점마다 구간을 나누고, 각 구간 요금(그 시점까지 누적 요금의 차이)을 그 구간에 타고 있던 사람끼리 똑같이 나눈다.
         * 아무도 타지 않은 구간의 요금은 운전자 몫이다. 사람별 금액은 10냥 단위로 올린다.
         * @param joins 중간에 탄 사람의 탑승 지점. 없는 사람은 출발부터 탄 것으로 본다.
         */
        fun shares(
            distanceKm: Float,
            elapsedMs: Long,
            energy: EnergyProfile,
            passengers: List<String>,
            joins: Map<String, JoinPoint>,
        ): Map<String, Int> {
            if (passengers.isEmpty()) return emptyMap()
            val end = JoinPoint(distanceKm.coerceAtLeast(0f), elapsedMs.coerceAtLeast(0L))
            fun startOf(name: String): JoinPoint = joins[name]?.let { minOf(it, end) } ?: JoinPoint.START
            fun costAt(p: JoinPoint): Int = of(p.distanceKm, p.elapsedMs, energy).totalNyang

            val points = (passengers.map(::startOf) + JoinPoint.START + end).distinct().sorted()
            val owed = passengers.associateWith { 0.0 }.toMutableMap()
            points.zipWithNext { from, to ->
                val riders = passengers.filter { startOf(it) <= from }
                if (riders.isEmpty()) return@zipWithNext
                val each = (costAt(to) - costAt(from)).coerceAtLeast(0).toDouble() / riders.size
                riders.forEach { owed[it] = owed.getValue(it) + each }
            }
            // 부동소수 오차로 10냥이 한 칸 더 올라가지 않게 아주 작은 값을 빼고 올린다
            return owed.mapValues { (_, v) -> (ceil(v / 10.0 - 1e-9) * 10).toInt().coerceAtLeast(0) }
        }

        fun of(distanceKm: Float, elapsedMs: Long, energy: EnergyProfile): TripBill {
            val km = distanceKm.coerceAtLeast(0f)
            val energyWon = km / energy.kmPerUnit * energy.wonPerUnit
            return TripBill(
                meter = NyangFare.of(km, elapsedMs),
                energyNyang = energyWon.roundToInt(),
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
