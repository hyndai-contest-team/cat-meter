package kr.catmeter.app.trip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripBillTest {

    private val fuel = EnergyProfile(EnergyKind.FUEL, kmPerUnit = 12f, wonPerUnit = 1_700, fromVehicle = true)

    @Test
    fun `cost to share is the energy cost only and the meter is a record`() {
        // 24km, 30분: 주유비 24/12*1700 = 3,400원. 거리 2,400냥 + 시간 300냥은 기록용
        val b = TripBill.of(24f, 30 * 60_000L, fuel)
        assertEquals(3_400, b.energyWon)
        assertEquals(3_400, b.costWon)
        assertEquals(2_400, b.meter.distanceNyang)
        assertEquals(300, b.meter.timeNyang)
        assertEquals(2_700, b.meterNyang)
    }

    @Test
    fun `per person counts the driver and drops fractions of a won`() {
        val b = TripBill.of(24f, 30 * 60_000L, fuel)   // 3,400원
        assertEquals(850, b.perPerson(3))                // 3,400 / 4
        assertEquals(1_700, b.perPerson(1))              // 운전자와 둘이 나눔
        assertEquals(1_133, b.perPerson(2))              // 1,133.3 → 1,133
        assertNull(b.perPerson(0))
    }

    // 24km = 3,400원. 12km 지점까지 누적 실비 = 1,700원
    private val half = JoinPoint(12f, 15 * 60_000L)

    @Test
    fun `driver takes an equal share with the passengers`() {
        val s = TripBill.split(24f, 30 * 60_000L, fuel, listOf("A", "B", "C"), emptyMap())
        assertEquals(mapOf("A" to 850, "B" to 850, "C" to 850), s.passengers)
        assertEquals(850, s.driverWon)
    }

    @Test
    fun `one passenger and the driver split in half`() {
        val s = TripBill.split(24f, 30 * 60_000L, fuel, listOf("A"), emptyMap())
        assertEquals(mapOf("A" to 1_700), s.passengers)
        assertEquals(1_700, s.driverWon)
    }

    @Test
    fun `mid join pays only the segment after boarding`() {
        // 앞 구간 1,700 → A·B·운전자가 566.7씩, 뒤 구간 1,700 → A·B·C·운전자가 425씩
        val s = TripBill.split(24f, 30 * 60_000L, fuel, listOf("A", "B", "C"), mapOf("C" to half))
        assertEquals(991, s.passengers["A"])     // 991.7 → 991
        assertEquals(991, s.passengers["B"])
        assertEquals(425, s.passengers["C"])
        assertEquals(993, s.driverWon)           // 3,400 − 991 − 991 − 425 (버린 끝전은 운전자가 낸다)
    }

    @Test
    fun `segment with nobody on board is the driver's`() {
        // 앞 구간 1,700 은 운전자 몫, 뒤 구간 1,700 은 C 와 운전자가 반씩
        val s = TripBill.split(24f, 30 * 60_000L, fuel, listOf("C"), mapOf("C" to half))
        assertEquals(mapOf("C" to 850), s.passengers)
        assertEquals(2_550, s.driverWon)
    }

    @Test
    fun `no passengers means the driver bears everything`() {
        val s = TripBill.split(24f, 30 * 60_000L, fuel, emptyList(), emptyMap())
        assertEquals(emptyMap<String, Int>(), s.passengers)
        assertEquals(3_400, s.driverWon)
    }

    @Test
    fun `boarding at the very end costs nothing and join after end is clamped`() {
        val end = JoinPoint(24f, 30 * 60_000L)
        val s = TripBill.shares(24f, 30 * 60_000L, fuel, listOf("A", "Z"), mapOf("Z" to JoinPoint(30f, 40 * 60_000L)))
        assertEquals(0, s["Z"])
        assertEquals(1_700, s["A"])
        assertEquals(0, TripBill.shares(24f, 30 * 60_000L, fuel, listOf("Z"), mapOf("Z" to end))["Z"])
    }

    @Test
    fun `early leaver pays only until getting off`() {
        // A 는 12km 에 내림: 앞 구간 1,700 을 A·B·운전자가 566.7씩, 뒤 구간 1,700 은 B·운전자가 850씩
        val s = TripBill.split(24f, 30 * 60_000L, fuel, listOf("A", "B"), emptyMap(), mapOf("A" to half))
        assertEquals(566, s.passengers["A"])     // 566.7 → 566
        assertEquals(1_416, s.passengers["B"])   // 1,416.7 → 1,416
        assertEquals(1_418, s.driverWon)
    }

    @Test
    fun `early leaver amount does not change with later events`() {
        // A 가 내린 뒤 C 가 타도 A 의 금액은 그대로다
        val atLeave = TripBill.shares(12f, 15 * 60_000L, fuel, listOf("A", "B"), emptyMap(), mapOf("A" to half))
        val later = TripBill.shares(
            24f, 30 * 60_000L, fuel, listOf("A", "B", "C"),
            mapOf("C" to JoinPoint(18f, 20 * 60_000L)), mapOf("A" to half),
        )
        assertEquals(atLeave["A"], later["A"])
    }

    @Test
    fun `join and leave in the middle pays only the middle`() {
        val s = TripBill.shares(
            24f, 30 * 60_000L, fuel, listOf("M"),
            mapOf("M" to JoinPoint(6f, 7 * 60_000L + 30_000L)), mapOf("M" to JoinPoint(18f, 22 * 60_000L + 30_000L)),
        )
        // 6km = 850원, 18km = 2,550원 → 가운데 1,700원을 M 과 운전자가 반씩
        assertEquals(mapOf("M" to 850), s)
    }

    @Test
    fun `someone not on a commute is left out and the rest split with the driver`() {
        // B 는 출퇴근 길이 아니다: 3,400 을 A 와 운전자가 반씩, B 는 0
        val s = TripBill.split(24f, 30 * 60_000L, fuel, listOf("A", "B"), emptyMap(), notSharing = setOf("B"))
        assertEquals(mapOf("A" to 1_700, "B" to 0), s.passengers)
        assertEquals(1_700, s.driverWon)
    }

    @Test
    fun `what the passengers and the driver pay adds up exactly to the cost`() {
        val s = TripBill.split(23.7f, 41 * 60_000L, fuel, listOf("A", "B", "C"), mapOf("C" to JoinPoint(7.3f, 9 * 60_000L)), mapOf("A" to JoinPoint(15.1f, 25 * 60_000L)))
        val cost = TripBill.of(23.7f, 41 * 60_000L, fuel).costWon
        // 동승자들이 낸 돈이 실비를 넘지 않고, 운전자 몫까지 더하면 정확히 실비다
        assertEquals(cost, s.passengers.values.sum() + s.driverWon)
        assertTrue(s.passengers.values.sum() < cost)
    }

    @Test
    fun `toll is split between the driver and whoever was on board at the gate`() {
        // 주유비 3,400 + 통행료 1,900. A·B·C 와 운전자가 처음부터 끝까지 탔다
        val tolls = listOf(TollEvent(JoinPoint(10f, 12 * 60_000L), 1_900))
        val s = TripBill.split(24f, 30 * 60_000L, fuel, listOf("A", "B", "C"), emptyMap(), tolls = tolls)
        assertEquals(mapOf("A" to 1_325, "B" to 1_325, "C" to 1_325), s.passengers)   // 850 + 475
        assertEquals(1_325, s.driverWon)
        assertEquals(5_300, TripBill.of(24f, 30 * 60_000L, fuel, tollWon = 1_900).costWon)
    }

    @Test
    fun `someone who got off before the gate or boarded after it does not pay the toll`() {
        val gate = TollEvent(JoinPoint(18f, 22 * 60_000L), 1_800)
        // A 는 12km 에 내렸고(요금소 전), C 는 20km 에 탔다(요금소 뒤). B 만 요금소를 함께 지났다
        val s = TripBill.split(
            24f, 30 * 60_000L, fuel, listOf("A", "B", "C"),
            joins = mapOf("C" to JoinPoint(20f, 25 * 60_000L)), leaves = mapOf("A" to half), tolls = listOf(gate),
        )
        val noToll = TripBill.split(
            24f, 30 * 60_000L, fuel, listOf("A", "B", "C"),
            joins = mapOf("C" to JoinPoint(20f, 25 * 60_000L)), leaves = mapOf("A" to half),
        )
        assertEquals(noToll.passengers["A"], s.passengers["A"])
        assertEquals(noToll.passengers["C"], s.passengers["C"])
        assertEquals(noToll.passengers.getValue("B") + 900, s.passengers["B"])   // 1,800 을 B 와 운전자가 반씩
        assertEquals(noToll.driverWon + 900, s.driverWon)
    }

    @Test
    fun `a toll after the early leaver got off does not change what they already paid`() {
        val atLeave = TripBill.shares(12f, 15 * 60_000L, fuel, listOf("A", "B"), emptyMap(), mapOf("A" to half))
        val later = TripBill.shares(
            24f, 30 * 60_000L, fuel, listOf("A", "B"), emptyMap(), mapOf("A" to half),
            tolls = listOf(TollEvent(JoinPoint(18f, 22 * 60_000L), 1_900)),
        )
        assertEquals(atLeave["A"], later["A"])
    }

    @Test
    fun `vehicle efficiency outside plausible range falls back`() {
        assertNull(EnergyProfile.fromVehicle(EnergyKind.EV, 160f))   // Wh/km 같은 다른 단위
        assertNull(EnergyProfile.fromVehicle(EnergyKind.FUEL, 0f))
        assertNull(EnergyProfile.fromVehicle(EnergyKind.EV, null))
        assertEquals(5.5f, EnergyProfile.fromVehicle(EnergyKind.EV, 5.5f)!!.kmPerUnit)
    }
}
