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

    @Test
    fun `vehicle efficiency outside plausible range falls back`() {
        assertNull(EnergyProfile.fromVehicle(EnergyKind.EV, 160f))   // Wh/km 같은 다른 단위
        assertNull(EnergyProfile.fromVehicle(EnergyKind.FUEL, 0f))
        assertNull(EnergyProfile.fromVehicle(EnergyKind.EV, null))
        assertEquals(5.5f, EnergyProfile.fromVehicle(EnergyKind.EV, 5.5f)!!.kmPerUnit)
    }
}
