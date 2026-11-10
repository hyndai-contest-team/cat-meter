package kr.catmeter.app.trip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TripBillTest {

    private val fuel = EnergyProfile(EnergyKind.FUEL, kmPerUnit = 12f, wonPerUnit = 1_700, fromVehicle = true)

    @Test
    fun `total is meter plus energy cost`() {
        // 24km, 30분: 거리 2,400 + 시간 300 + 주유비 24/12*1700 = 3,400
        val b = TripBill.of(24f, 30 * 60_000L, fuel)
        assertEquals(2_400, b.meter.distanceNyang)
        assertEquals(300, b.meter.timeNyang)
        assertEquals(3_400, b.energyNyang)
        assertEquals(6_100, b.totalNyang)
    }

    @Test
    fun `per passenger rounds up to 10 nyang and needs a passenger`() {
        val b = TripBill.of(24f, 30 * 60_000L, fuel)   // 6,100
        assertEquals(2_040, b.perPassenger(3))           // 2,033.3 → 2,040
        assertEquals(6_100, b.perPassenger(1))
        assertNull(b.perPassenger(0))
    }

    // 24km, 30분 = 6,100냥. 12km·15분 지점까지 누적 요금 = 1,200 + 150 + 1,700 = 3,050냥
    private val half = JoinPoint(12f, 15 * 60_000L)

    @Test
    fun `shares without mid joins equal per passenger`() {
        val s = TripBill.shares(24f, 30 * 60_000L, fuel, listOf("A", "B", "C"), emptyMap())
        assertEquals(mapOf("A" to 2_040, "B" to 2_040, "C" to 2_040), s)
    }

    @Test
    fun `mid join pays only the segment after boarding`() {
        // 앞 구간 3,050 → A·B 가 1,525씩, 뒤 구간 3,050 → A·B·C 가 1,016.7씩
        val s = TripBill.shares(24f, 30 * 60_000L, fuel, listOf("A", "B", "C"), mapOf("C" to half))
        assertEquals(2_550, s["A"])   // 2,541.7 → 2,550
        assertEquals(2_550, s["B"])
        assertEquals(1_020, s["C"])   // 1,016.7 → 1,020
    }

    @Test
    fun `segment with nobody on board is the driver's`() {
        // 앞 구간에는 동승자가 없어 운전자 몫, C 는 뒤 구간 3,050 만 낸다
        val s = TripBill.shares(24f, 30 * 60_000L, fuel, listOf("C"), mapOf("C" to half))
        assertEquals(mapOf("C" to 3_050), s)
    }

    @Test
    fun `boarding at the very end costs nothing and join after end is clamped`() {
        val end = JoinPoint(24f, 30 * 60_000L)
        val s = TripBill.shares(24f, 30 * 60_000L, fuel, listOf("A", "Z"), mapOf("Z" to JoinPoint(30f, 40 * 60_000L)))
        assertEquals(0, s["Z"])
        assertEquals(6_100, s["A"])
        assertEquals(0, TripBill.shares(24f, 30 * 60_000L, fuel, listOf("Z"), mapOf("Z" to end))["Z"])
        assertEquals(emptyMap<String, Int>(), TripBill.shares(24f, 30 * 60_000L, fuel, emptyList(), emptyMap()))
    }

    @Test
    fun `vehicle efficiency outside plausible range falls back`() {
        assertNull(EnergyProfile.fromVehicle(EnergyKind.EV, 160f))   // Wh/km 같은 다른 단위
        assertNull(EnergyProfile.fromVehicle(EnergyKind.FUEL, 0f))
        assertNull(EnergyProfile.fromVehicle(EnergyKind.EV, null))
        assertEquals(5.5f, EnergyProfile.fromVehicle(EnergyKind.EV, 5.5f)!!.kmPerUnit)
    }
}
