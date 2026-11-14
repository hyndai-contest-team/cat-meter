package kr.catmeter.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kr.catmeter.app.CatMeterSession
import kr.catmeter.app.car.AaosMonitor
import kr.catmeter.app.car.NaviSource
import kr.catmeter.app.car.PleosVehicleSource
import kr.catmeter.app.trip.GeoPoint

/** 차량 상태를 화면 전체에 전달하는 묶음 */
data class CarView(
    val speedKph: Float,
    val speed: AaosMonitor.Speed,
    /** 주행 제한 중이면 입력 UI를 숨긴다. 모르면(null) 속도로 대신 판단한다. */
    val restricted: Boolean,
    val parked: Boolean?,
    val pleos: PleosVehicleSource.Status,
    val odometerKm: Float?,
    val navi: NaviSource.Status = NaviSource.Status.Connecting,
    /** 내비에서 안내 중인 목적지 */
    val destination: NaviSource.Destination? = null,
    val location: GeoPoint? = null,
    val home: GeoPoint? = null,
    val office: GeoPoint? = null,
)

@Composable
fun CatMeterApp(session: CatMeterSession, onRequestSpeedPermission: () -> Unit) {
    val state by session.state.collectAsStateWithLifecycle()
    val speed by session.aaos.speed.collectAsStateWithLifecycle()
    val restricted by session.aaos.drivingRestricted.collectAsStateWithLifecycle()
    val parked by session.pleos.parked.collectAsStateWithLifecycle()
    val pleos by session.pleos.status.collectAsStateWithLifecycle()
    val odometer by session.pleos.odometerKm.collectAsStateWithLifecycle()
    val naviStatus by session.navi.status.collectAsStateWithLifecycle()
    val destination by session.navi.destination.collectAsStateWithLifecycle()
    val location by session.navi.location.collectAsStateWithLifecycle()
    val home by session.navi.home.collectAsStateWithLifecycle()
    val office by session.navi.office.collectAsStateWithLifecycle()

    val kph = (speed as? AaosMonitor.Speed.Live)?.kph ?: 0f
    val car = CarView(
        speedKph = kph,
        speed = speed,
        restricted = restricted ?: (kph >= 5f),
        parked = parked,
        pleos = pleos,
        odometerKm = odometer,
        navi = naviStatus,
        destination = destination,
        location = location,
        home = home,
        office = office,
    )

    CatMeterTheme {
      // Pleos Connect 디스플레이는 160dpi(mdpi) 대화면이라 기본 크기로는 글자가 너무 작다. 운전석 거리에서 읽히도록 키운다.
      val base = LocalDensity.current
      val carDensity = if (base.density < 1.5f) Density(base.density * CAR_UI_SCALE, base.fontScale) else base
      CompositionLocalProvider(LocalDensity provides carDensity) {
      Surface(color = Palette.bg, contentColor = Palette.text) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .background(Palette.bg)
                .safeDrawingPadding()
        ) {
            // 내비 옆 분할 화면(좁은 창)인지, 넓은 창인지. Connect-S 1:1 / Connect-W 2:1·1:2 모두 대응.
            val wide = maxWidth >= 640.dp && maxWidth > maxHeight * 0.9f
            Column(Modifier.fillMaxSize()) {
                StatusStrip(car, compact = !wide, onRequestSpeedPermission)
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    // 새로 앉은 자리가 있으면 차가 서 있을 때 탑승 QR 을 먼저 띄운다 (도착 화면 제외).
                    // 한 자리면 그 자리 QR, 여러 자리에 함께 앉았으면 휴대폰에서 자리를 고르는 QR 하나.
                    val prompts = state.seatPrompts
                    val showPrompt = prompts.isNotEmpty() && !car.restricted && state.phase != CatMeterSession.Phase.ARRIVED
                    if (showPrompt && prompts.size == 1) {
                        val seat = prompts.first()
                        BoardingQrScreen(
                            session, state, car, wide,
                            onClose = { session.dismissSeatPrompt(seat) },
                            midTrip = state.phase == CatMeterSession.Phase.DRIVING,
                            seat = seat,
                        )
                    } else if (showPrompt) {
                        BoardingQrScreen(
                            session, state, car, wide,
                            onClose = session::dismissSeatPrompts,
                            midTrip = state.phase == CatMeterSession.Phase.DRIVING,
                        )
                    } else when (state.phase) {
                        CatMeterSession.Phase.SETUP -> SetupScreen(session, state, car, wide)
                        CatMeterSession.Phase.DRIVING -> DriveScreen(session, state, car, wide)
                        CatMeterSession.Phase.ARRIVED -> ReceiptScreen(session, state, car, wide)
                    }
                }
            }
        }
      }
      }
    }
}

private const val CAR_UI_SCALE = 1.75f

/** 차량 데이터 수신 상태. 시연·검증 때 어떤 값이 실제로 들어오는지 바로 보이게 한다. */
@Composable
private fun StatusStrip(car: CarView, compact: Boolean, onRequestSpeedPermission: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (val s = car.speed) {
            is AaosMonitor.Speed.Live -> Chip("${s.kph.toInt()} km/h", Palette.ok)
            AaosMonitor.Speed.NoPermission -> TextButton(onClick = onRequestSpeedPermission) {
                Text("속도 권한 허용", color = Palette.warn, fontSize = 13.sp)
            }
            AaosMonitor.Speed.Starting -> Chip("속도 연결 중", Palette.sub)
            is AaosMonitor.Speed.Unavailable -> Chip(if (compact) "속도 없음" else s.reason, Palette.warn)
        }
        val gear = when (car.parked) {
            true -> "P"
            false -> "주행 기어"
            null -> "기어 ?"
        }
        Chip(gear, if (car.parked == null) Palette.sub else Palette.ok)
        if (!compact) {
            when (val p = car.pleos) {
                PleosVehicleSource.Status.Connecting -> Chip("Pleos 연결 중", Palette.sub)
                PleosVehicleSource.Status.Ready ->
                    Chip(if (car.odometerKm != null) "계기판 거리 수신" else "계기판 거리 없음", if (car.odometerKm != null) Palette.ok else Palette.warn)
                is PleosVehicleSource.Status.Failed -> Chip(p.reason, Palette.warn)
            }
        }
        if (!compact) {
            when (car.navi) {
                NaviSource.Status.Connecting -> Chip("내비 연결 중", Palette.sub)
                NaviSource.Status.Ready -> Chip("내비 연결", Palette.ok)
                is NaviSource.Status.Failed -> Chip("내비 없음", Palette.warn)
            }
        }
        if (car.restricted) Chip("주행 제한", Palette.warn)
    }
}

@Composable
fun Chip(text: String, color: Color) {
    Text(
        text,
        color = color,
        fontSize = 13.sp,
        maxLines = 1,
        modifier = Modifier
            .background(Palette.surface, RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 5.dp),
    )
}
