package kr.catmeter.app.car

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

/**
 * AAOS 표준 Car API 로 받는 값.
 *
 * - 차속: `PERF_VEHICLE_SPEED`(m/s). Pleos 공식 FAQ 가 계기판과 같은 소스로 안내하는 경로다.
 *   Vehicle SDK 2.3.2 에는 차속 API 가 없다. 권한 `android.car.permission.CAR_SPEED`(dangerous → 런타임 요청).
 * - 주행 제한: `CarUxRestrictionsManager`. requiresDistractionOptimization 이 true 면 운전 중 제한 상태다.
 *   에뮬레이터에서 고양이가 움직였다고 제한 상태였다고 볼 수 없으므로 상태를 로그로 남긴다.
 */
@Suppress("DEPRECATION")   // registerCallback(callback, id, rate) 는 API 34 기기에서도 동작하는 형태
class AaosMonitor(context: Context) {

    sealed interface Speed {
        data object Starting : Speed
        data object NoPermission : Speed
        data class Unavailable(val reason: String) : Speed
        /** 항상 0 이상 (후진도 절댓값) */
        data class Live(val kph: Float) : Speed
    }

    /** null = 아직 모름(차량 API 없음 포함) */
    private val _restricted = MutableStateFlow<Boolean?>(null)
    val drivingRestricted: StateFlow<Boolean?> = _restricted.asStateFlow()

    private val _speed = MutableStateFlow<Speed>(Speed.Starting)
    val speed: StateFlow<Speed> = _speed.asStateFlow()

    private val appContext = context.applicationContext
    private var car: Car? = null
    private var propertyManager: CarPropertyManager? = null
    private var uxManager: CarUxRestrictionsManager? = null

    private val speedCallback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            val mps = (value.value as? Float) ?: return
            _speed.value = Speed.Live(abs(mps) * 3.6f)
        }

        override fun onErrorEvent(propertyId: Int, zone: Int) {
            Log.w(TAG, "speed error event prop=$propertyId zone=$zone")
        }
    }

    private val uxListener = CarUxRestrictionsManager.OnUxRestrictionsChangedListener { r -> onUx(r) }

    fun start() {
        if (car != null) {
            // 권한을 나중에 허용한 경우 속도 구독만 다시 시도
            if (_speed.value is Speed.NoPermission) car?.let { subscribeSpeed(it) }
            return
        }
        try {
            car = Car.createCar(appContext, null, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { c, ready ->
                if (ready) {
                    subscribeUx(c)
                    subscribeSpeed(c)
                } else {
                    propertyManager = null
                    uxManager = null
                    _speed.value = Speed.Unavailable("차량 서비스 연결이 끊어졌어요")
                }
            }
        } catch (t: Throwable) {   // 일반 폰 에뮬레이터: android.car 클래스 자체가 없음
            Log.w(TAG, "Car API unavailable", t)
            _speed.value = Speed.Unavailable("차량 API가 없는 기기예요")
        }
    }

    private fun subscribeUx(c: Car) {
        try {
            val m = c.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager
            uxManager = m
            m.registerListener(uxListener)
            m.currentCarUxRestrictions?.let(::onUx)
        } catch (t: Throwable) {
            Log.w(TAG, "UX restrictions unavailable", t)
        }
    }

    private fun onUx(r: CarUxRestrictions) {
        val restricted = r.isRequiresDistractionOptimization
        if (_restricted.value != restricted) {
            Log.i(TAG, "UX restriction changed: requiresDO=$restricted active=0x${Integer.toHexString(r.activeRestrictions)}")
        }
        _restricted.value = restricted
    }

    private fun subscribeSpeed(c: Car) {
        if (appContext.checkSelfPermission(SPEED_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            _speed.value = Speed.NoPermission
            return
        }
        try {
            val m = c.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
            propertyManager = m
            val config = m.getCarPropertyConfig(VehiclePropertyIds.PERF_VEHICLE_SPEED)
            if (config == null) {
                _speed.value = Speed.Unavailable("이 차량은 속도 정보를 제공하지 않아요")
                return
            }
            // 애니메이션·적분용으로 10Hz 면 충분 (기기 최대치를 넘지 않게)
            val rate = minOf(10f, config.maxSampleRate.takeIf { it > 0f } ?: 10f)
            m.registerCallback(speedCallback, VehiclePropertyIds.PERF_VEHICLE_SPEED, rate)
            Log.i(TAG, "speed subscribed: rate=${rate}Hz")
            runCatching { m.getFloatProperty(VehiclePropertyIds.PERF_VEHICLE_SPEED, 0) }
                .onSuccess { _speed.value = Speed.Live(abs(it) * 3.6f) }
                .onFailure { if (_speed.value !is Speed.Live) _speed.value = Speed.Live(0f) }
        } catch (t: Throwable) {
            Log.w(TAG, "speed subscribe failed", t)
            _speed.value = Speed.Unavailable("속도 구독 실패: ${t.message}")
        }
    }

    fun release() {
        runCatching { propertyManager?.unregisterCallback(speedCallback) }
        runCatching { uxManager?.unregisterListener() }
        runCatching { car?.disconnect() }
        propertyManager = null
        uxManager = null
        car = null
    }

    companion object {
        const val SPEED_PERMISSION = "android.car.permission.CAR_SPEED"
        private const val TAG = "CatMeterAaos"
    }
}
