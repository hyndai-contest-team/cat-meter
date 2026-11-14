package kr.catmeter.app.ui

import kr.catmeter.app.ui.cat3d.Cat3D
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.text.style.TextOverflow
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
                MeterCard(bill, live, state, car, showCat, compact = false, Modifier.weight(1.25f).fillMaxHeight())
                Column(
                    Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    DestinationCard(live, car)
                    RidersCard(state)
                    DetailsCard(live)
                }
            }
            ArrivalControls(session, state, canFinish, wide = true, openBoardingQr)
        }
    } else {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
            MeterCard(bill, live, state, car, showCat, compact = true, Modifier.weight(1f).fillMaxWidth())
            ArrivalControls(session, state, canFinish, wide = false, openBoardingQr)
        }
    }
}

/**
 * 합계 냥을 크게, 아래에 거리·시간·주유비(충전비) 냥과 실제 값.
 * 고양이는 정차 중에만 보이는 정지 그림이다 (애니메이션 없음). 움직이기 시작하면 빠져 렌더링도 멈춘다.
 * @param compact 좁은 창(내비 옆). 목적지 카드가 따로 없으므로 남은 거리를 여기에 적고, 글자를 줄인다.
 */
@Composable
private fun MeterCard(
    bill: TripBill,
    live: TripTracker.Snapshot,
    state: CatMeterSession.State,
    car: CarView,
    showCat: Boolean,
    compact: Boolean,
    modifier: Modifier,
) {
    Card(modifier, padding = if (compact) 16.dp else 22.dp, gap = 4.dp) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("지금까지", color = Palette.sub, fontSize = 16.sp, maxLines = 1)
            if (showCat) {
                val activity = CatActivity.of(parked = car.parked == true)
                Text(
                    "${state.cat.name} · ${activity.caption}",
                    fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .padding(start = 12.dp)
                        .weight(1f, fill = false)
                        .background(Palette.surfaceHigh, RowShape)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
        BigValue(formatNyang(bill.totalNyang), "냥", if (compact) 64.sp else 84.sp, Palette.accent)
        Text("1냥 = 1원", color = Palette.sub, fontSize = 13.sp, maxLines = 1)
        if (compact) {
            car.destination?.let { d ->
                Text("${d.name}까지 ${formatKm(d.remainKm)} km · 약 ${formatDuration(d.remainTimeS * 1000L)}", color = Palette.sub, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (state.autoStarted) {
            Text("내비 안내에 맞춰 자동으로 시작했어요 · 안내가 끝나면 자동으로 도착해요", color = Palette.accent, fontSize = 13.sp, maxLines = 2)
        }
        if (showCat) {
            Cat3D(state.cat, Modifier.fillMaxWidth().weight(1f), animated = false)
        } else {
            Spacer(Modifier.weight(1f))
        }
        val valueSize = if (compact) 18.sp else 24.sp
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Metric("거리", bill.meter.distanceNyang, "${formatKm(live.distanceKm)} km", valueSize, Modifier.weight(1f))
            Metric("시간", bill.meter.timeNyang, formatDuration(live.elapsedMs), valueSize, Modifier.weight(1f))
            Metric(bill.energy.kind.label, bill.energyNyang, energyBasis(bill), valueSize, Modifier.weight(1f))
        }
    }
}

/** 주유비·충전비를 계산한 기준. 예: 12.0 km/L (차량 값이 없어 기본값을 쓰면 "(기본값)"을 붙인다) */
fun energyBasis(bill: TripBill): String {
    val e = bill.energy
    return String.format(java.util.Locale.KOREA, "%.1f km/%s", e.kmPerUnit, e.kind.unit) + if (e.fromVehicle) "" else " (기본값)"
}

/** 냥 값과 그 근거가 된 실제 값 */
@Composable
private fun Metric(label: String, nyang: Int, actual: String, size: TextUnit, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, color = Palette.sub, fontSize = 13.sp, maxLines = 1)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                formatNyang(nyang), fontSize = size, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
            )
            Text(" 냥", color = Palette.sub, fontSize = 13.sp, maxLines = 1, modifier = Modifier.padding(bottom = 2.dp))
        }
        // 좁은 창에서도 "(기본값)"까지 보이게 두 줄까지 쓴다
        Text(actual, color = Palette.sub, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** 이동 기록 상세 */
@Composable
private fun DetailsCard(live: TripTracker.Snapshot) {
    Card(Modifier.fillMaxWidth(), gap = 6.dp) {
        DetailRow("달린 시간", formatDuration(live.movingMs))
        DetailRow("쉰 시간", formatDuration(live.stoppedMs))
        DetailRow("멈춘 횟수", "${live.stopCount}번")
        DetailRow("평균 속도(주행 중)", "${live.avgMovingKph.toInt()} km/h")
        DetailRow("거리 기준", if (live.distanceSource == TripTracker.DistanceSource.ODOMETER) "계기판 누적 거리" else "차속 추정")
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, color = Palette.sub, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Text(value, fontSize = 15.sp)
    }
}

/** 내비 목적지까지 남은 거리와 진행 정도 */
@Composable
private fun DestinationCard(live: TripTracker.Snapshot, car: CarView) {
    val d = car.destination ?: return
    Card(Modifier.fillMaxWidth()) {
        Text("목적지 · ${d.name}", color = Palette.sub, fontSize = 14.sp, maxLines = 1)
        Text("${formatKm(d.remainKm)} km 남았어요", fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        val total = live.distanceKm + d.remainKm
        val progress = if (total > 0f) (live.distanceKm / total).coerceIn(0f, 1f) else 0f
        Box(Modifier.fillMaxWidth().height(8.dp).background(Palette.surfaceHigh, PillShape)) {
            Box(Modifier.fillMaxWidth(progress).height(8.dp).background(Palette.accent, PillShape))
        }
        Text("약 ${formatDuration(d.remainTimeS * 1000L)}", color = Palette.sub, fontSize = 13.sp, maxLines = 1)
    }
}

/** 함께 타는 사람: 자리, 중간 탑승, 하차 확인 중(자리가 비고 문이 열림), 하차 후 결제 상태 */
@Composable
private fun RidersCard(state: CatMeterSession.State) {
    val passengers = state.passengers
    if (passengers.isEmpty()) return
    Card(Modifier.fillMaxWidth(), gap = 10.dp) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("함께 타는 중 ${passengers.count { it !in state.alights }}명", fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (state.seats.isNotEmpty()) Text("좌석 센서로 확인", color = Palette.sub, fontSize = 13.sp)
        }
        // 자리가 비어 확인이 필요한 사람을 맨 위에 둔다 (목록이 길어도 스크롤 없이 보이게)
        passengers.withIndex().sortedByDescending { it.value in state.alightPending }
            .forEach { (i, name) -> RiderRow(name, i, state) }
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
        // 실제 결제는 도착했을 때 출퇴근 길로 판정돼야 일어난다. 여기서는 등록한 결제 수단만 보여 준다
        rider?.paymentLabel != null && rider.commuting -> rider.paymentLabel to Palette.ok
        else -> "" to Palette.sub
    }
    PersonRow(
        name, index,
        detail = if (pending) "$where · 자리가 비고 문이 열렸어요" else where,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (pending) Modifier.border(2.dp, Palette.warn, RowShape) else Modifier),
        detailColor = if (pending) Palette.warn else Palette.sub,
    ) {
        if (status.isNotEmpty()) Text(status, color = color, fontSize = 13.sp, maxLines = 1)
    }
}

/**
 * 도착·중간 탑승 버튼. 주행 제한이 풀린 정차 중에만 보인다.
 * 넓은 창은 한 줄에, 좁은 창은 글자가 잘리지 않게 세로로 쌓는다.
 */
@Composable
private fun ArrivalControls(
    session: CatMeterSession,
    state: CatMeterSession.State,
    canFinish: Boolean,
    wide: Boolean,
    onShowBoardingQr: () -> Unit,
) {
    if (!canFinish) return
    val canBoard = state.passengers.size < CatMeterSession.MAX_PASSENGERS
    Column(Modifier.fillMaxWidth().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.arrivalSuggested) Text("주차했어요. 도착했나요?", fontSize = 17.sp, fontWeight = FontWeight.Bold)
        if (wide) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (canBoard) OutlinePill("중간 탑승 QR", onShowBoardingQr, Modifier.weight(1f).height(ButtonHeight))
                if (state.arrivalSuggested) OutlinePill("계속 가기", session::dismissArrival, Modifier.weight(1f).height(ButtonHeight))
                PrimaryPill("도착했어요!", session::finishTrip, Modifier.weight(2f).height(ButtonHeight))
            }
        } else {
            PrimaryPill("도착했어요!", session::finishTrip, Modifier.fillMaxWidth().height(ButtonHeight))
            if (state.arrivalSuggested) OutlinePill("계속 가기", session::dismissArrival, Modifier.fillMaxWidth().height(ButtonHeight))
            if (canBoard) OutlinePill("중간 탑승 QR", onShowBoardingQr, Modifier.fillMaxWidth().height(ButtonHeight))
        }
    }
}
