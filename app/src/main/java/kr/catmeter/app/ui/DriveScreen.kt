package kr.catmeter.app.ui

import kr.catmeter.app.ui.cat3d.Cat3D
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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

    if (wide) {
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp)) {
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                MeterCard(bill, live, state, car, showCat, totalSize = 84.sp, Modifier.weight(1.25f).fillMaxHeight())
                Column(
                    Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    DestinationCard(live, car, state.autoStarted)
                    RidersCard(state)
                }
            }
            ArrivalControls(session, state, canFinish, openBoardingQr)
        }
    } else {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
            MeterCard(bill, live, state, car, showCat, totalSize = 64.sp, Modifier.weight(1f).fillMaxWidth())
            ArrivalControls(session, state, canFinish, openBoardingQr)
        }
    }
}

/**
 * 합계 냥을 크게, 아래에 거리·시간·주유비(충전비).
 * 고양이는 정차 중에만 보이는 정지 그림이다 (애니메이션 없음). 움직이기 시작하면 빠져 렌더링도 멈춘다.
 */
@Composable
private fun MeterCard(
    bill: TripBill,
    live: TripTracker.Snapshot,
    state: CatMeterSession.State,
    car: CarView,
    showCat: Boolean,
    totalSize: TextUnit,
    modifier: Modifier,
) {
    Card(modifier, padding = 22.dp, gap = 4.dp) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("지금까지", color = Palette.sub, fontSize = 16.sp, modifier = Modifier.weight(1f))
            if (showCat) {
                val activity = CatActivity.of(parked = car.parked == true)
                Text(
                    "${state.cat.name} · ${activity.caption}",
                    fontSize = 14.sp, maxLines = 1,
                    modifier = Modifier.background(Palette.surfaceHigh, RowShape).padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
        BigValue(formatNyang(bill.totalNyang), "냥", totalSize, Palette.accent)
        Text("1냥 = 1원 · ${energyBasis(bill)}", color = Palette.sub, fontSize = 14.sp, maxLines = 1)
        if (showCat) {
            Cat3D(state.cat, Modifier.fillMaxWidth().weight(1f), animated = false)
        } else {
            Spacer(Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Metric("거리", formatKm(live.distanceKm), "km", Modifier.weight(1f))
            Metric("걸린 시간", formatDuration(live.elapsedMs), "", Modifier.weight(1f))
            Metric(bill.energy.kind.label, formatNyang(bill.energyNyang), "냥", Modifier.weight(1f))
        }
    }
}

/** 12.0 km/L · 1,700원/L (기본값이면 표시) */
fun energyBasis(bill: TripBill): String {
    val e = bill.energy
    return String.format(java.util.Locale.KOREA, "%.1f km/%s", e.kmPerUnit, e.kind.unit) + if (e.fromVehicle) "" else " (기본값)"
}

@Composable
private fun Metric(label: String, value: String, unit: String, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, color = Palette.sub, fontSize = 13.sp, maxLines = 1)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            if (unit.isNotEmpty()) Text(" $unit", color = Palette.sub, fontSize = 14.sp, maxLines = 1, modifier = Modifier.padding(bottom = 3.dp))
        }
    }
}

/** 내비 목적지까지 남은 거리와 진행 정도 */
@Composable
private fun DestinationCard(live: TripTracker.Snapshot, car: CarView, autoStarted: Boolean) {
    val d = car.destination ?: return
    Card(Modifier.fillMaxWidth()) {
        Text("목적지 · ${d.name}", color = Palette.sub, fontSize = 14.sp, maxLines = 1)
        Text("${formatKm(d.remainKm)} km 남았어요", fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        val total = live.distanceKm + d.remainKm
        val progress = if (total > 0f) (live.distanceKm / total).coerceIn(0f, 1f) else 0f
        Box(Modifier.fillMaxWidth().height(8.dp).background(Palette.surfaceHigh, PillShape)) {
            Box(Modifier.fillMaxWidth(progress).height(8.dp).background(Palette.accent, PillShape))
        }
        Text(
            "약 ${formatDuration(d.remainTimeS * 1000L)}" + if (autoStarted) " · 안내가 끝나면 자동으로 도착해요" else "",
            color = Palette.sub, fontSize = 13.sp, maxLines = 2,
        )
    }
}

/** 함께 타는 사람: 자리, 중간 탑승, 내리는 중(자리가 빔), 하차 후 결제 상태 */
@Composable
private fun RidersCard(state: CatMeterSession.State) {
    val passengers = state.passengers
    if (passengers.isEmpty()) return
    Card(Modifier.fillMaxWidth(), gap = 10.dp) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("함께 타는 중 ${passengers.count { it !in state.alights }}명", fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (state.seats.isNotEmpty()) Text("좌석 센서로 확인", color = Palette.sub, fontSize = 13.sp)
        }
        passengers.forEachIndexed { i, name -> RiderRow(name, i, state) }
    }
}

@Composable
private fun RiderRow(name: String, index: Int, state: CatMeterSession.State) {
    val rider = state.riders.firstOrNull { it.name == name }
    val seat = rider?.seat?.label
    val joined = state.midJoins[name]?.let { "${formatKm(it.distanceKm)} km부터" } ?: "처음부터"
    val where = listOfNotNull(seat, joined).joinToString(" · ")
    val alight = state.alights[name]
    val pending = name in state.alightPending
    val (status, color) = when {
        alight != null -> when (alight.status) {
            CatMeterSession.AlightStatus.NOTIFYING, CatMeterSession.AlightStatus.GRACE -> "하차 · 결제 대기" to Palette.sub
            CatMeterSession.AlightStatus.CHARGING -> "하차 · 결제 중" to Palette.sub
            CatMeterSession.AlightStatus.AT_ARRIVAL -> "하차 · 도착 때 결제" to Palette.sub
            CatMeterSession.AlightStatus.DONE -> {
                val c = alight.charge
                if (c?.status == BoardingClient.Charge.Status.PAID) "하차 · ${formatNyang(c.amount)}원 결제됨" to Palette.ok
                else "하차" to Palette.sub
            }
        }
        pending -> "하차 확인 중" to Palette.warn
        rider?.paymentLabel != null && rider.commuting -> "자동결제" to Palette.ok
        else -> "" to Palette.sub
    }
    PersonRow(
        name, index,
        detail = if (pending) "$where · 자리가 비었어요" else where,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (pending) Modifier.border(2.dp, Palette.warn, RowShape) else Modifier),
        detailColor = if (pending) Palette.warn else Palette.sub,
    ) {
        if (status.isNotEmpty()) Text(status, color = color, fontSize = 13.sp, maxLines = 1)
    }
}

@Composable
private fun ArrivalControls(session: CatMeterSession, state: CatMeterSession.State, canFinish: Boolean, onShowBoardingQr: () -> Unit) {
    if (!canFinish) return
    Column(Modifier.fillMaxWidth().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.arrivalSuggested) Text("주차했어요. 도착했나요?", fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.arrivalSuggested) {
                OutlinePill("계속 가기", session::dismissArrival, Modifier.weight(1f).height(ButtonHeight))
            } else if (state.passengers.size < CatMeterSession.MAX_PASSENGERS) {
                OutlinePill("중간 탑승 QR", onShowBoardingQr, Modifier.weight(1f).height(ButtonHeight))
            }
            PrimaryPill("도착했어요!", session::finishTrip, Modifier.weight(2f).height(ButtonHeight))
        }
    }
}
