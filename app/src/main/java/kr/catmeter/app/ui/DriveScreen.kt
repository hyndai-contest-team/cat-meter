package kr.catmeter.app.ui

import kr.catmeter.app.ui.cat3d.Cat3D
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.catmeter.app.CatMeterSession
import kr.catmeter.app.data.BoardingClient
import kr.catmeter.app.trip.CatActivity
import kr.catmeter.app.trip.TripBill
import kr.catmeter.app.trip.TripTracker

/**
 * 이동 중. 운전자 조작 없이 냥 미터와 기록만 보여 준다.
 * - 고양이는 정차 중에만 정지 그림으로 보인다. 주행 중에는 숨기고 냥 숫자만 남긴다.
 * - 냥은 택시 미터기처럼 구간마다 한 칸씩 오르고, 숫자 전환 효과 없이 값만 바뀐다.
 * - 좁은 창(내비 옆): (정차 시 고양이) + 냥 미터 / 넓은 창: 여기에 상세 내역을 더한다.
 * 도착 버튼은 주행 제한이 풀린 정차 중에만 보인다.
 */
@Composable
fun DriveScreen(session: CatMeterSession, state: CatMeterSession.State, car: CarView, wide: Boolean) {
    val live = state.live ?: return
    // 중간 탑승 QR: 주행 제한이 풀린 정차 중에만 띄운다. 차가 움직이면 바로 닫는다.
    var showBoardingQr by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(car.restricted) { if (car.restricted) showBoardingQr = false }
    if (showBoardingQr && !car.restricted) {
        BoardingQrScreen(session, state, car, wide, onClose = { showBoardingQr = false }, midTrip = true)
        return
    }
    val openBoardingQr = { showBoardingQr = true }
    val showCat = !live.isMoving
    val bill = TripBill.of(live.distanceKm, live.elapsedMs, state.energy)
    val canFinish = !car.restricted && !live.isMoving

    val catPanel = @Composable { mod: Modifier ->
        Column(mod, horizontalAlignment = Alignment.CenterHorizontally) {
            // 정차 중에만 보이는 정지 그림 (애니메이션 없음). 움직이기 시작하면 showCat=false 로 빠져 렌더링도 멈춘다.
            Cat3D(state.cat, Modifier.fillMaxWidth().weight(1f), animated = false)
            val activity = CatActivity.of(parked = car.parked == true)
            Text("${state.cat.name} · ${activity.caption}", color = Palette.sub, fontSize = 16.sp, maxLines = 1)
        }
    }

    if (wide) {
        Row(Modifier.fillMaxSize().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            if (showCat) catPanel(Modifier.weight(1.2f).fillMaxHeight())
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                NyangMeter(bill, live, car, state.autoStarted, totalSize = 56.sp, valueSize = 32.sp)
                Details(live, state)
                Spacer(Modifier.weight(1f))
                ArrivalControls(session, state, canFinish, openBoardingQr)
            }
        }
    } else {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (showCat) catPanel(Modifier.fillMaxWidth().weight(1f)) else Spacer(Modifier.weight(1f))
            NyangMeter(bill, live, car, state.autoStarted, totalSize = 48.sp, valueSize = 24.sp)
            if (!showCat) Spacer(Modifier.weight(1f))
            ArrivalControls(session, state, canFinish, openBoardingQr)
        }
    }
}

/** 합계 냥 + 거리·시간·주유비(충전비) 냥. 아래 작은 글씨로 실제 값을 함께 적는다. */
@Composable
private fun NyangMeter(bill: TripBill, live: TripTracker.Snapshot, car: CarView, autoStarted: Boolean, totalSize: TextUnit, valueSize: TextUnit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(Palette.surface, RoundedCornerShape(16.dp))
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Text("지금까지", color = Palette.sub, fontSize = 14.sp)
            NyangValue(bill.totalNyang, totalSize)
            car.destination?.let { d ->
                Text("${d.name}까지 ${formatKm(d.remainKm)} km", color = Palette.sub, fontSize = 14.sp, maxLines = 1)
            }
            if (autoStarted) {
                Text("내비 안내에 맞춰 자동으로 시작했어요 · 안내가 끝나면 자동으로 도착해요", color = Palette.accent, fontSize = 13.sp, maxLines = 2)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Metric("거리", bill.meter.distanceNyang, "${formatKm(live.distanceKm)} km", valueSize, Modifier.weight(1f))
            Metric("시간", bill.meter.timeNyang, formatDuration(live.elapsedMs), valueSize, Modifier.weight(1f))
            Metric(bill.energy.kind.label, bill.energyNyang, energyBasis(bill), valueSize, Modifier.weight(1f))
        }
    }
}

/** 12.0 km/L · 1,700원/L (기본값이면 표시) */
fun energyBasis(bill: TripBill): String {
    val e = bill.energy
    return String.format(java.util.Locale.KOREA, "%.1f km/%s", e.kmPerUnit, e.kind.unit) + if (e.fromVehicle) "" else " (기본값)"
}

@Composable
private fun Metric(label: String, nyang: Int, actual: String, size: TextUnit, modifier: Modifier) {
    Column(
        modifier
            .background(Palette.surface, RoundedCornerShape(16.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(label, color = Palette.sub, fontSize = 14.sp, maxLines = 1)
        NyangValue(nyang, size)
        Text(actual, color = Palette.sub, fontSize = 14.sp, maxLines = 1)
    }
}

@Composable
private fun NyangValue(nyang: Int, size: TextUnit) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(formatNyang(nyang), fontSize = size, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(" 냥", color = Palette.sub, fontSize = 16.sp, maxLines = 1, modifier = Modifier.padding(bottom = 4.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Details(live: TripTracker.Snapshot, state: CatMeterSession.State) {
    val passengers = state.passengers
    Column(
        Modifier
            .fillMaxWidth()
            .background(Palette.surface, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        DetailRow("달린 시간", formatDuration(live.movingMs))
        DetailRow("쉰 시간", formatDuration(live.stoppedMs))
        DetailRow("멈춘 횟수", "${live.stopCount}번")
        DetailRow("평균 속도(주행 중)", "${live.avgMovingKph.toInt()} km/h")
        DetailRow("거리 기준", if (live.distanceSource == TripTracker.DistanceSource.ODOMETER) "계기판 누적 거리" else "차속 추정")
        if (passengers.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text("함께 타는 중", color = Palette.sub, fontSize = 14.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                passengers.forEach { name -> RiderChip(name, state) }
            }
        }
    }
}

/** 탑승자 상태: 중간 탑승, 내리는 중(자리가 빔), 하차 후 결제 대기·결제됨 */
@Composable
private fun RiderChip(name: String, state: CatMeterSession.State) {
    val alight = state.alights[name]
    val (text, color) = when {
        alight != null -> when (alight.status) {
            CatMeterSession.AlightStatus.NOTIFYING, CatMeterSession.AlightStatus.GRACE -> "$name · 하차 · 결제 대기" to Palette.sub
            CatMeterSession.AlightStatus.CHARGING -> "$name · 하차 · 결제 중" to Palette.sub
            CatMeterSession.AlightStatus.AT_ARRIVAL -> "$name · 하차 · 도착 때 결제" to Palette.sub
            CatMeterSession.AlightStatus.DONE -> {
                val c = alight.charge
                if (c?.status == BoardingClient.Charge.Status.PAID) "$name · 하차 · ${formatNyang(c.amount)}원 결제됨" to Palette.ok
                else "$name · 하차" to Palette.sub
            }
        }
        name in state.alightPending -> "$name · 내리는 중?" to Palette.warn
        name in state.midJoins -> "$name · 중간 탑승" to Palette.accent
        else -> name to Palette.accent
    }
    Chip(text, color)
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, color = Palette.sub, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Text(value, fontSize = 15.sp)
    }
}

@Composable
private fun ArrivalControls(session: CatMeterSession, state: CatMeterSession.State, canFinish: Boolean, onShowBoardingQr: () -> Unit) {
    if (!canFinish) return
    if (state.passengers.size < CatMeterSession.MAX_PASSENGERS) {
        OutlinedButton(onClick = onShowBoardingQr, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text("중간 탑승 QR", fontSize = 17.sp)
        }
    }
    if (state.arrivalSuggested) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(Palette.accent.copy(alpha = 0.12f), RoundedCornerShape(16.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("주차했어요. 도착했나요?", fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = session::finishTrip, modifier = Modifier.weight(1f).height(52.dp)) {
                    Text("도착", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
                OutlinedButton(onClick = session::dismissArrival, modifier = Modifier.weight(1f).height(52.dp)) {
                    Text("계속 가기", fontSize = 16.sp)
                }
            }
        }
    } else {
        OutlinedButton(onClick = session::finishTrip, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text("도착", fontSize = 18.sp)
        }
    }
}
