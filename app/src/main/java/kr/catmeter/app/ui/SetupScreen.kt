package kr.catmeter.app.ui

import kr.catmeter.app.ui.cat3d.Cat3D
import kr.catmeter.app.ui.cat3d.CatPose
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.InputChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.catmeter.app.CatMeterSession
import kr.catmeter.app.trip.Accessory
import kr.catmeter.app.trip.Fur
import kr.catmeter.app.trip.CommuteRule
import kr.catmeter.app.trip.TripBill
import kr.catmeter.app.car.NaviSource
import java.time.LocalDateTime
import java.time.ZoneId

/** 출발 전: 오늘의 고양이 꾸미기 + 함께 타는 사람 이름. 정차(주행 제한 해제) 중에만 편집할 수 있다. */
@Composable
fun SetupScreen(session: CatMeterSession, state: CatMeterSession.State, car: CarView, wide: Boolean) {
    var showBoardingQr by rememberSaveable { mutableStateOf(false) }
    if (showBoardingQr) {
        BoardingQrScreen(state, car, wide, onClose = { showBoardingQr = false })
        return
    }
    val openBoardingQr = { showBoardingQr = true }

    val preview = @Composable { mod: Modifier ->
        Column(mod, horizontalAlignment = Alignment.CenterHorizontally) {
            Cat3D(state.cat, Modifier.fillMaxWidth().weight(1f), pose = CatPose.GREET)
            Text(state.cat.name.ifBlank { "이름을 지어 주세요" }, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text(
                if (state.pastRides == 0) "첫 드라이브를 기다리는 중" else "함께 달린 지 ${state.pastRides}번",
                color = Palette.sub, fontSize = 14.sp,
            )
            Spacer(Modifier.height(12.dp))
        }
    }

    // 목적지(예상 냥) 카드와 출발 버튼은 아래에 고정하고, 나머지는 한 덩어리로 스크롤한다.
    if (wide) {
        Row(Modifier.fillMaxSize().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            preview(Modifier.weight(1f).fillMaxHeight())
            Column(Modifier.weight(1.1f).fillMaxHeight()) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { SetupFields(session, state, car, openBoardingQr) }
                StartBar(session, state, car)
            }
        }
    } else {
        // 좁은 창: 고양이도 함께 위로 밀려 올라가 설정 항목을 넓게 볼 수 있게 한다.
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                preview(Modifier.fillMaxWidth().height(260.dp))
                SetupFields(session, state, car, openBoardingQr)
                Spacer(Modifier.height(8.dp))
            }
            StartBar(session, state, car)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SetupFields(session: CatMeterSession, state: CatMeterSession.State, car: CarView, onShowBoardingQr: () -> Unit) {
    val locked = car.restricted
    run {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (locked) {
                Text(
                    "주행 중에는 설정을 바꿀 수 없어요. 차를 세운 뒤 꾸며 주세요.",
                    color = Palette.warn, fontSize = 15.sp,
                    modifier = Modifier.fillMaxWidth().background(Palette.warn.copy(alpha = 0.1f), RoundedCornerShape(12.dp)).padding(12.dp),
                )
            }

            Section("고양이 이름") {
                OutlinedTextField(
                    value = state.cat.name,
                    onValueChange = session::setCatName,
                    enabled = !locked,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Section("털 색") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Fur.entries.forEach { fur ->
                        FilterChip(
                            selected = state.cat.fur == fur,
                            onClick = { session.setFur(fur) },
                            enabled = !locked,
                            label = { Text(fur.label, fontSize = 16.sp) },
                            leadingIcon = { Box(Modifier.size(16.dp).background(Color(fur.main), CircleShape)) },
                        )
                    }
                }
            }

            Section("소품 (친구가 골라 주세요)") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Accessory.entries.forEach { a ->
                        FilterChip(
                            selected = a in state.cat.accessories,
                            onClick = { session.toggleAccessory(a) },
                            enabled = !locked,
                            label = { Text(a.label, fontSize = 16.sp) },
                        )
                    }
                }
            }

            Section("함께 타는 사람") {
                OutlinedButton(
                    onClick = onShowBoardingQr,
                    enabled = !locked,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    Text("탑승 QR 띄우기", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                }
                Text("또는 이름을 직접 넣어 주세요", color = Palette.sub, fontSize = 13.sp)
                PassengerEditor(session, state.passengers, locked)
            }
        }
    }
}

/** 아래 고정 영역: 목적지·예상 냥 + 출발 버튼 */
@Composable
private fun StartBar(session: CatMeterSession, state: CatMeterSession.State, car: CarView) {
    val locked = car.restricted
    Column(Modifier.fillMaxWidth()) {
        EstimateCard(state, car)

        Button(
            onClick = session::startTrip,
            enabled = !locked,
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp).height(56.dp),
        ) {
            Text(if (locked) "정차 후 출발할 수 있어요" else "출발!", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PassengerEditor(session: CatMeterSession, passengers: List<String>, locked: Boolean) {
    var input by remember { mutableStateOf("") }
    val add = {
        session.addPassenger(input)
        input = ""
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (passengers.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                passengers.forEach { name ->
                    InputChip(
                        selected = false,
                        onClick = { session.removePassenger(name) },
                        enabled = !locked,
                        label = { Text(name, fontSize = 16.sp) },
                        trailingIcon = { Text("✕", fontSize = 12.sp) },
                    )
                }
            }
        }
        if (passengers.size < CatMeterSession.MAX_PASSENGERS) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    enabled = !locked,
                    singleLine = true,
                    placeholder = { Text("이름") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { add() }),
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = add, enabled = !locked && input.isNotBlank()) { Text("추가", fontSize = 16.sp) }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, color = Palette.sub, fontSize = 14.sp)
        content()
    }
}

/**
 * 출발 전 예상 냥. 내비에서 목적지를 찍으면 자동으로 잡힌다.
 * 예상 요금 = 미터(내비 예상 거리·시간) + 주유비·충전비. 통행료는 넣지 않는다.
 */
@Composable
private fun EstimateCard(state: CatMeterSession.State, car: CarView) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .background(Palette.surface, RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val dest = car.destination
        if (dest == null) {
            Text(
                if (car.navi is NaviSource.Status.Ready) "내비에서 목적지를 찍으면 예상 냥을 미리 보여 드려요"
                else "내비와 연결되지 않아 예상 냥을 계산할 수 없어요",
                color = Palette.sub, fontSize = 15.sp,
            )
            return@Column
        }
        val bill = TripBill.of(dest.remainKm, dest.remainTimeS * 1000L, state.energy)
        Text("목적지 · ${dest.name}", fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Text("${formatKm(dest.remainKm)} km · 약 ${formatDuration(dest.remainTimeS * 1000L)}", color = Palette.sub, fontSize = 14.sp)
        Row(verticalAlignment = Alignment.Bottom) {
            Text("예상 ${formatNyang(bill.totalNyang)}", fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text(" 냥", color = Palette.sub, fontSize = 16.sp, modifier = Modifier.padding(bottom = 3.dp))
        }
        Text(
            "미터 ${formatNyang(bill.meter.totalNyang)} + ${bill.energy.kind.label} ${formatNyang(bill.energyNyang)}" +
                (bill.perPassenger(state.passengers.size)?.let { " · 동승자 1인당 약 ${formatNyang(it)}냥" } ?: ""),
            color = Palette.sub, fontSize = 14.sp,
        )
        val preview = CommuteRule.check(
            start = LocalDateTime.now(SEOUL),
            end = LocalDateTime.now(SEOUL).plusSeconds(dest.remainTimeS.toLong()),
            from = car.location,
            to = dest.point,
            home = car.home,
            office = car.office,
            alreadySettledInWindow = false,
        )
        Text("출발을 누르지 않아도 차가 움직이고 3초 뒤 자동으로 시작해요", color = Palette.accent, fontSize = 14.sp)
        Spacer(Modifier.height(4.dp))
        if (preview.eligible) {
            Text("${preview.window?.label} 함께 타기 · 도착하면 정산 QR을 보여 드려요", color = Palette.ok, fontSize = 14.sp)
        } else {
            Text("결제 없이 냥만 표시돼요 · ${preview.reasons.first()}", color = Palette.warn, fontSize = 14.sp)
        }
    }
}

private val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
