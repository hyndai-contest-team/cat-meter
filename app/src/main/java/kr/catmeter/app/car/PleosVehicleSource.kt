package kr.catmeter.app.car

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.EvBattery
import ai.pleos.playground.vehicle.api.Fuel
import ai.pleos.playground.vehicle.api.Gear
import ai.pleos.playground.vehicle.api.Odometer
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.DistanceDrivenListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kr.catmeter.app.trip.EnergyKind
import kr.catmeter.app.trip.EnergyProfile
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Pleos Vehicle SDK 로 받는 값 (조회·구독만, 차량 제어 없음).
 *
 * - 누적 주행 거리: Odometer.getCurrentDistanceDriven / registerDistanceDriven (km 로 가정, 에뮬레이터에서 확인 필요)
 *   차량 트립(getTripCurrent...)은 초기화 기준이 앱의 '이동'과 다를 수 있어, 앱은 출발 시점 누적 거리와의 차이를 쓴다.
 * - 기어: Gear.getCurrentGearSelectionState / registerGearSelection. P 단이면 '주차'로 보고 도착 확인을 띄운다.
 * - 전비·연비: EvBattery.getCumulativeEvEfficiency / Fuel.getCumulativeFuelEconomy (누적 평균, 없으면 current).
 *   차량 API 에 연료 종류가 없어, 전비가 들어오면 전기차, 연비가 들어오면 내연기관으로 본다.
 *   단위는 km/kWh·km/L 로 가정하고, 그럴듯한 범위를 벗어나면 버리고 기본값을 쓴다 (EnergyProfile.fromVehicle).
 * 권한: pleos.car.permission.CAR_DRIVE, CAR_ENERGY
 */
class PleosVehicleSource(context: Context, private val scope: CoroutineScope) {

    sealed interface Status {
        data object Connecting : Status
        data object Ready : Status
        data class Failed(val reason: String) : Status
    }

    private val vehicle = Vehicle(context.applicationContext)
    private var odometer: Odometer? = null
    private var gear: Gear? = null
    private var evBattery: EvBattery? = null
    private var fuel: Fuel? = null

    /** 차량에서 받은 전비·연비 기준. null = 못 받음 (기본값 사용) */
    private val _energy = MutableStateFlow<EnergyProfile?>(null)
    val energy: StateFlow<EnergyProfile?> = _energy.asStateFlow()

    private val _status = MutableStateFlow<Status>(Status.Connecting)
    val status: StateFlow<Status> = _status.asStateFlow()

    /** 계기판 누적 거리(km). null = 못 받음 */
    private val _odometerKm = MutableStateFlow<Float?>(null)
    val odometerKm: StateFlow<Float?> = _odometerKm.asStateFlow()

    /** true = P 단. null = 못 받음 */
    private val _parked = MutableStateFlow<Boolean?>(null)
    val parked: StateFlow<Boolean?> = _parked.asStateFlow()

    private val distanceListener = object : DistanceDrivenListener {
        override fun onDistanceDrivenUpdated(value: Float?) {
            value?.let { _odometerKm.value = it }
        }

        override fun onFailed(e: Exception) {
            Log.w(TAG, "odometer listener failed: ${e.errorCodeName()} ${e.message}")
        }
    }

    private val gearListener = object : GearSelectionListener {
        override fun onSelected(value: VehicleGear?) {
            _parked.value = value.toParked()
        }

        override fun onFailed(e: Exception) {
            Log.w(TAG, "gear listener failed: ${e.errorCodeName()} ${e.message}")
        }
    }

    fun start() {
        scope.launch {
            try {
                vehicle.initialize()
                odometer = vehicle.getOdometer()
                gear = vehicle.getGear()
                evBattery = vehicle.getEvBattery()
                fuel = vehicle.getFuel()
            } catch (t: Throwable) {
                Log.e(TAG, "Vehicle initialize failed", t)
                _status.value = Status.Failed("Pleos 차량 서비스를 찾을 수 없어요")
                return@launch
            }
            val odoError = retrying("Odometer") { loadOdometer() }
            val gearError = retrying("Gear") { loadGear() }
            loadEnergy()
            _status.value = if (odoError != null && gearError != null) {
                Status.Failed("차량 정보를 불러오지 못했어요: ${odoError.describe()}")
            } else {
                Status.Ready
            }
        }
    }

    private suspend fun loadOdometer() {
        val odo = odometer ?: return
        val km = await<Float?> { ok, fail -> odo.getCurrentDistanceDriven({ ok(it) }, fail) }
        km?.let { _odometerKm.value = it }
        odo.registerDistanceDriven(distanceListener)
        Log.i(TAG, "odometer: $km")
    }

    private suspend fun loadGear() {
        val g = gear ?: return
        val current = await<VehicleGear?> { ok, fail -> g.getCurrentGearSelectionState({ ok(it) }, fail) }
        _parked.value = current.toParked()
        g.registerGearSelection(gearListener)
        Log.i(TAG, "gear: $current")
    }

    /** 출발할 때마다 다시 불러 최신 누적 평균을 쓴다. */
    fun refreshEnergy() {
        scope.launch { loadEnergy() }
    }

    private suspend fun loadEnergy() {
        val ev = evBattery?.let { b ->
            readFloat("EV cumulative efficiency") { ok, fail -> b.getCumulativeEvEfficiency(ok, fail) }
                ?.takeIf { it > 0f }
                ?: readFloat("EV current efficiency") { ok, fail -> b.getCurrentEvEfficiency(ok, fail) }
        }
        val profile = EnergyProfile.fromVehicle(EnergyKind.EV, ev?.takeIf { it > 0f })
            ?: fuel?.let { f ->
                val v = readFloat("fuel cumulative economy") { ok, fail -> f.getCumulativeFuelEconomy(ok, fail) }
                    ?.takeIf { it > 0f }
                    ?: readFloat("fuel current economy") { ok, fail -> f.getCurrentFuelEconomy(ok, fail) }
                EnergyProfile.fromVehicle(EnergyKind.FUEL, v)
            }
        // 전비·연비를 못 받으면 배터리 값이 읽히는지로 전기차인지만 판단해 기본값을 고른다 (fromVehicle = false)
        _energy.value = profile ?: if (isEv()) EnergyProfile.DEFAULT_EV else EnergyProfile.DEFAULT_FUEL
        Log.i(TAG, "energy profile: ${_energy.value} (from vehicle: ${profile != null})")
    }

    private suspend fun isEv(): Boolean {
        val b = evBattery ?: return false
        val soc = try {
            await<Int?>(timeoutMs = 3_000) { ok, fail -> b.getEvBatteryStateOfCharge(ok, fail) }
        } catch (t: Throwable) {
            if (t is CancellationException && t !is TimeoutCancellationException) throw t
            null
        }
        val level = soc ?: readFloat("EV battery level") { ok, fail -> b.getEvBatteryLevel(ok, fail) }?.toInt()
        return level != null && level > 0
    }

    private suspend fun readFloat(name: String, block: (ok: (Float?) -> Unit, fail: (Exception) -> Unit) -> Unit): Float? =
        try {
            await<Float?>(timeoutMs = 3_000) { ok, fail -> block(ok, fail) }.also { Log.i(TAG, "$name: $it") }
        } catch (t: Throwable) {
            if (t is CancellationException && t !is TimeoutCancellationException) throw t
            Log.w(TAG, "$name failed: ${t.errorCodeName()} ${t.message}")
            null
        }

    /** initialize() 직후에는 서비스 바인딩이 끝나지 않았을 수 있어 연결 오류는 몇 번 재시도한다. */
    private suspend fun retrying(name: String, block: suspend () -> Unit): Throwable? {
        var last: Throwable? = null
        repeat(5) { attempt ->
            try {
                block()
                return null
            } catch (t: Throwable) {
                if (t is CancellationException && t !is TimeoutCancellationException) throw t
                last = t
                Log.w(TAG, "$name load failed (attempt ${attempt + 1}): ${t.errorCodeName()} ${t.message}")
                if (t.errorCodeName() != "ERROR_SERVICE_CONNECTION_FAILURE" && t !is TimeoutCancellationException) return t
                delay(800L * (attempt + 1))
            }
        }
        return last
    }

    fun release() {
        runCatching { odometer?.unregisterDistanceDriven(distanceListener) }
        runCatching { gear?.unregisterGearSelection(gearListener) }
        runCatching { vehicle.release() }
    }

    companion object {
        private const val TAG = "CatMeterPleos"
    }
}

/** P 단이면 true, 모르면 null */
private fun VehicleGear?.toParked(): Boolean? = when (this) {
    null, VehicleGear.GEAR_UNKNOWN -> null
    VehicleGear.GEAR_PARK -> true
    else -> false
}

private suspend fun <T> await(
    timeoutMs: Long = 5_000,
    block: (ok: (T) -> Unit, fail: (Exception) -> Unit) -> Unit,
): T = withTimeout(timeoutMs) {
    suspendCancellableCoroutine { cont ->
        val done = AtomicBoolean(false)
        block(
            { value -> if (done.compareAndSet(false, true) && cont.isActive) cont.resume(value) },
            { e -> if (done.compareAndSet(false, true) && cont.isActive) cont.resumeWithException(e) },
        )
    }
}

/** 2.3.2 AAR 에서 VehicleException 이 난독화되어 있어 ErrorCode 이름을 리플렉션으로 읽는다. */
private fun Throwable.errorCodeName(): String? = runCatching {
    javaClass.methods
        .firstOrNull { it.parameterCount == 0 && it.returnType.isEnum && it.returnType.simpleName == "ErrorCode" }
        ?.invoke(this)
        ?.let { (it as Enum<*>).name }
}.getOrNull()

private fun Throwable.describe(): String = when (errorCodeName()) {
    "ERROR_NOT_SUPPORTED_PROPERTY" -> "이 차량에서 지원하지 않아요"
    "ERROR_SECURITY_PERMISSION" -> "권한이 없어요"
    "ERROR_SERVICE_CONNECTION_FAILURE" -> "차량 서비스에 연결하지 못했어요"
    else -> if (this is TimeoutCancellationException) "차량 응답이 없어요" else message ?: javaClass.simpleName
}
