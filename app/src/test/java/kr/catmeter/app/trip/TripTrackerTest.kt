package kr.catmeter.app.trip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TripTrackerTest {

    private val min = 60_000L

    @Test
    fun `odometer delta is used as distance`() {
        val t = TripTracker(startedAtMs = 0L, startOdometerKm = 1000f)
        t.onSpeed(60f, 0L)
        t.onOdometer(1012.5f, 15 * min)
        val s = t.snapshot(15 * min)
        assertEquals(12.5f, s.distanceKm, 0.001f)
        assertEquals(TripTracker.DistanceSource.ODOMETER, s.distanceSource)
    }

    @Test
    fun `speed is integrated when odometer is unavailable`() {
        val t = TripTracker(startedAtMs = 0L)
        // 4초 간격 샘플(적분 공백 한도 이내)로 60km/h 30분 주행
        var now = 0L
        t.onSpeed(60f, now)
        while (now < 30 * min) { now += 4_000L; t.onSpeed(60f, now) }
        val s = t.snapshot(now)
        assertEquals(30f, s.distanceKm, 0.01f)
        assertEquals(TripTracker.DistanceSource.SPEED_ESTIMATE, s.distanceSource)
        assertEquals(60f, s.avgMovingKph, 0.1f)
    }

    @Test
    fun `odometer arriving late keeps distance integrated before it`() {
        val t = TripTracker(startedAtMs = 0L)
        var now = 0L
        t.onSpeed(36f, now)                                   // 36km/h = 0.01km/s
        repeat(25) { now += 4_000L; t.onSpeed(36f, now) }     // 100초 → 1km
        t.onOdometer(500f, now)
        t.onOdometer(502f, now + 1)
        assertEquals(3f, t.snapshot(now + 1).distanceKm, 0.01f)
    }

    @Test
    fun `frozen odometer falls back to speed integration`() {
        // 에뮬레이터: 누적 거리가 주행 중에도 0.0 으로 멈춰 있다
        val t = TripTracker(startedAtMs = 0L, startOdometerKm = 0f)
        var now = 0L
        t.onSpeed(36f, now)
        repeat(25) { now += 4_000L; t.onSpeed(36f, now); t.onOdometer(0f, now) }   // 100초 → 1km
        val s = t.snapshot(now)
        assertEquals(1f, s.distanceKm, 0.01f)
        assertEquals(TripTracker.DistanceSource.SPEED_ESTIMATE, s.distanceSource)
    }

    @Test
    fun `odometer that starts moving later takes over without double counting`() {
        val t = TripTracker(startedAtMs = 0L, startOdometerKm = 50f)
        var now = 0L
        t.onSpeed(36f, now)
        repeat(25) { now += 4_000L; t.onSpeed(36f, now) }     // 적분 1km, 누적 거리는 50 에 멈춤
        t.onOdometer(51f, now)                                // 같은 1km 를 누적 거리가 뒤늦게 반영
        val s = t.snapshot(now)
        assertEquals(1f, s.distanceKm, 0.01f)
        assertEquals(TripTracker.DistanceSource.ODOMETER, s.distanceSource)
    }

    @Test
    fun `odometer going backwards does not lose distance`() {
        val t = TripTracker(startedAtMs = 0L, startOdometerKm = 100f)
        t.onOdometer(105f, 1L)
        t.onOdometer(3f, 2L)       // 계기판 값 초기화
        t.onOdometer(4f, 3L)
        assertEquals(6f, t.snapshot(3L).distanceKm, 0.001f)
    }

    @Test
    fun `stops are counted on moving to stopped transitions with hysteresis`() {
        val t = TripTracker(startedAtMs = 0L, startOdometerKm = 0f)
        t.onSpeed(0f, 0L)
        assertFalse(t.snapshot(0L).isMoving)
        t.onSpeed(2f, 1_000L)          // 3km/h 이하 → 아직 정차
        assertFalse(t.snapshot(1_000L).isMoving)
        t.onSpeed(20f, 2_000L)
        assertTrue(t.snapshot(2_000L).isMoving)
        t.onSpeed(2f, 3_000L)          // 1km/h 이상이면 계속 이동
        assertTrue(t.snapshot(3_000L).isMoving)
        t.onSpeed(0f, 4_000L)
        t.onSpeed(30f, 5_000L)
        t.onSpeed(0.5f, 6_000L)
        val s = t.snapshot(6_000L)
        assertEquals(2, s.stopCount)
        assertFalse(s.isMoving)
    }

    @Test
    fun `moving and stopped time add up to elapsed time`() {
        val t = TripTracker(startedAtMs = 1_000L, startOdometerKm = 0f)
        t.onSpeed(0f, 1_000L)
        t.onSpeed(40f, 3_000L)     // 2초 정차
        t.onSpeed(0f, 13_000L)     // 10초 이동
        val s = t.snapshot(16_000L) // 3초 정차
        assertEquals(15_000L, s.elapsedMs)
        assertEquals(10_000L, s.movingMs)
        assertEquals(5_000L, s.stoppedMs)
    }

    @Test
    fun `long sensor gaps are not integrated`() {
        val t = TripTracker(startedAtMs = 0L)
        t.onSpeed(100f, 0L)
        t.onSpeed(100f, 10 * min)  // 10분 동안 값이 없었다
        assertEquals(0f, t.snapshot(10 * min).distanceKm, 0.001f)
    }

    @Test
    fun `cat activity follows parking`() {
        assertEquals(CatActivity.PARKED, CatActivity.of(parked = true))
        assertEquals(CatActivity.STOPPED, CatActivity.of(parked = false))
    }
}
