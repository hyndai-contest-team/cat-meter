package kr.catmeter.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 내비 연동 확인용 (debug 빌드 전용).
 * adb shell am broadcast --user current -a kr.catmeter.app.DEBUG_ROUTE \
 *   -n kr.catmeter.app/.DebugRouteReceiver --ed lat 37.4979 --ed lon 127.0276 --es name 강남역
 * 경로 취소: ... -n kr.catmeter.app/.DebugRouteReceiver --ez cancel true
 * 안내 음성 확인: ... -n kr.catmeter.app/.DebugRouteReceiver --es say "민수님 하차를 확인했어요."
 * 시연용 출퇴근 판정 켜기/끄기 (자동결제 테스트): ... -n kr.catmeter.app/.DebugRouteReceiver --ez demoCommute true
 */
class DebugRouteReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val session = (context.applicationContext as CatMeterApplication).session
        if (intent.hasExtra("demoCommute")) {
            session.demoCommute = intent.getBooleanExtra("demoCommute", false)
            return
        }
        intent.getStringExtra("say")?.let {
            session.announceForTest(it)
            return
        }
        val navi = session.navi
        if (intent.getBooleanExtra("cancel", false)) {
            navi.cancelRouteForTest()
            return
        }
        val lat = intent.getDoubleExtra("lat", Double.NaN)
        val lon = intent.getDoubleExtra("lon", Double.NaN)
        if (lat.isNaN() || lon.isNaN()) return
        val name = intent.getStringExtra("name") ?: "테스트 목적지"
        navi.requestRouteForTest(lat, lon, name)
    }
}
