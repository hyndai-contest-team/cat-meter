package kr.catmeter.app.ui

import kr.catmeter.app.ui.cat3d.Cat3D
import kr.catmeter.app.ui.cat3d.CatPose
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.InputChip
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
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
        BoardingQrScreen(session, state, car, wide, onClose = { showBoardingQr = false })
        return
    }
    val locked = car.restricted

    val catCard = @Composable { mod: Modifier ->
        Card(mod, gap = 4.dp, horizontalAlignment = Alignment.CenterHorizontally) {
            Cat3D(state.cat, Modifier.fillMaxWidth().weight(1f), pose = CatPose.GREET)
            Text(state.cat.name.ifBlank { "이름을 지어 주세요" }, fontSize = 26.sp, fontWeight = FontWeight.Black)
            Text(
                if (state.pastRides == 0) "첫 드라이브를 기다리는 중" else "함께 달린 지 ${state.pastRides}번",
                color = Palette.sub, fontSize = 14.sp,
            )
        }
    }
    val buttons = @Composable {
        val startLabel = if (locked) "정차 후 출발할 수 있어요" else "출발!"
        if (wide) {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinePill("탑승 QR 띄우기", { showBoardingQr = true }, Modifier.weight(1f).height(ButtonHeight), enabled = !locked)
                PrimaryPill(startLabel, session::startTrip, Modifier.weight(2f).height(ButtonHeight), enabled = !locked)
            }
        } else {
            // 좁은 창: 글자가 잘리지 않게 세로로 쌓는다
            Column(Modifier.fillMaxWidth().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryPill(startLabel, session::startTrip, Modifier.fillMaxWidth().height(ButtonHeight), enabled = !locked)
                OutlinePill("탑승 QR 띄우기", { showBoardingQr = true }, Modifier.fillMaxWidth().height(ButtonHeight), enabled = !locked)
            }
        }
    }

    // 출발 버튼은 아래에 고정하고, 나머지는 한 덩어리로 스크롤한다.
    if (wide) {
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp)) {
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                catCard(Modifier.weight(1f).fillMaxHeight())
                Column(Modifier.weight(1.3f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("새 드라이브 준비", fontSize = 26.sp, fontWeight = FontWeight.Black)
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { SetupFields(session, state, car) }
                }
            }
            buttons()
        }
    } else {
        // 좁은 창: 고양이도 함께 위로 밀려 올라가 설정 항목을 넓게 볼 수 있게 한다.
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                catCard(Modifier.fillMaxWidth().height(280.dp))
                SetupFields(session, state, car)
            }
            buttons()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SetupFields(session: CatMeterSession, state: CatMeterSession.State, car: CarView) {
    val locked = car.restricted
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (locked) {
            Text(
                "주행 중에는 설정을 바꿀 수 없어요. 차를 세운 뒤 꾸며 주세요.",
                color = Palette.warn, fontSize = 15.sp,
                modifier = Modifier.fillMaxWidth().background(Palette.warn.copy(alpha = 0.1f), RowShape).padding(12.dp),
            )
        }

        EstimateCard(state, car)

        Card(Modifier.fillMaxWidth(), gap = 12.dp) {
            Text("꾸미기 (차를 세웠을 때만)", color = Palette.sub, fontSize = 14.sp)
            LabeledRow("이름") {
                OutlinedTextField(
                    value = state.cat.name,
                    onValueChange = session::setCatName,
                    enabled = !locked,
                    singleLine = true,
                    shape = RowShape,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            LabeledRow("털 색") {
                // 좁은 창에서는 다음 줄로 넘긴다
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.alpha(if (locked) LOCKED_ALPHA else 1f),
                ) {
                    Fur.entries.forEach { fur ->
                        val selected = state.cat.fur == fur
                        Column(
                            Modifier
                                .clip(RowShape)
                                .selectable(selected = selected, enabled = !locked, role = Role.RadioButton) { session.setFur(fur) }
                                .padding(4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Box(
                                Modifier
                                    .size(44.dp)
                                    .border(3.dp, if (selected) Palette.accent else Color.Transparent, CircleShape)
                                    .padding(6.dp)
                                    .background(Color(fur.main), CircleShape)
                                    // 어두운 털 색도 카드 위에서 보이게 가는 테두리를 두른다
                                    .border(1.dp, Palette.sub, CircleShape),
                            )
                            Text(fur.label, color = if (selected) Palette.text else Palette.sub, fontSize = 12.sp, maxLines = 1)
                        }
                    }
                }
            }
            LabeledRow("소품") {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.alpha(if (locked) LOCKED_ALPHA else 1f),
                ) {
                    Accessory.entries.forEach { a ->
                        val selected = a in state.cat.accessories
                        Text(
                            a.label,
                            color = if (selected) Palette.onAccent else Palette.text,
                            fontSize = 15.sp, fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                            modifier = Modifier
                                .clip(PillShape)
                                .background(if (selected) Palette.accent else Palette.surfaceHigh)
                                .toggleable(value = selected, enabled = !locked, role = Role.Checkbox) { session.toggleAccessory(a) }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                        )
                    }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Text("함께 타는 사람", color = Palette.sub, fontSize = 14.sp)
            Text("탑승 QR을 찍으면 자동으로 들어와요. 이름을 직접 넣어도 돼요.", color = Palette.sub, fontSize = 13.sp)
            PassengerEditor(session, state.passengers, locked)
        }

        // 자동 시작은 내비가 목적지를 안내하고 있을 때만 된다
        if (car.destination != null) {
            Text("출발을 누르지 않아도 차가 움직이고 3초 뒤 자동으로 시작해요", color = Palette.sub, fontSize = 13.sp)
        }
    }
}

/** 주행 제한으로 잠긴 꾸미기 항목의 투명도 */
private const val LOCKED_ALPHA = 0.4f

@Composable
private fun LabeledRow(label: String, content: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, color = Palette.sub, fontSize = 15.sp, modifier = Modifier.width(56.dp))
        content()
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
                    shape = RowShape,
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

/**
 * 출발 전 예상 실비. 내비에서 목적지를 찍으면 자동으로 잡힌다.
 * 실비 = 주유비·충전비 (내비 예상 거리 기준) + 내비가 알려 준 경로 통행료. 운전자를 포함해 나눈다.
 */
@Composable
private fun EstimateCard(state: CatMeterSession.State, car: CarView) {
    Card(Modifier.fillMaxWidth(), gap = 4.dp) {
        Text("목적지", color = Palette.sub, fontSize = 14.sp)
        val dest = car.destination
        if (dest == null) {
            Text(
                if (car.navi is NaviSource.Status.Ready) "내비에서 목적지를 찍으면 나눌 실비를 미리 보여 드려요"
                else "내비와 연결되지 않아 예상 실비를 계산할 수 없어요",
                fontSize = 16.sp,
            )
            return@Card
        }
        val bill = TripBill.of(dest.remainKm, dest.remainTimeS * 1000L, state.energy, dest.tollFare)
        Text(
            "${dest.name} · ${formatKm(dest.remainKm)} km · 약 ${formatDuration(dest.remainTimeS * 1000L)}",
            fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 2,
        )
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("예상 실비", color = Palette.sub, fontSize = 15.sp, modifier = Modifier.padding(bottom = 5.dp))
            BigValue(formatNyang(bill.costWon), "원", 30.sp, Palette.accent)
        }
        Text(
            "${bill.energy.kind.label} ${formatNyang(bill.energyWon)}원" +
                (if (bill.tollWon > 0) " + 통행료 ${formatNyang(bill.tollWon)}원" else "") +
                (bill.perPerson(state.passengers.size)?.let { " · 운전자 포함 1인당 약 ${formatNyang(it)}원" } ?: " · 운전자도 함께 나눠 내요"),
            color = Palette.sub, fontSize = 14.sp,
        )
        Text("냥 기록은 약 ${formatNyang(bill.meterNyang)}냥 (결제와 무관)", color = Palette.sub, fontSize = 13.sp)
        val preview = CommuteRule.check(
            start = LocalDateTime.now(SEOUL),
            end = LocalDateTime.now(SEOUL).plusSeconds(dest.remainTimeS.toLong()),
            from = car.location,
            to = dest.point,
            home = car.home,
            office = car.office,
            alreadySettledInWindow = false,
        )
        if (preview.eligible) {
            Text("${preview.window?.label} 함께 타기 · 도착하면 자동으로 정산해요", color = Palette.accent, fontSize = 14.sp)
        } else {
            Text("결제 없이 기록만 남겨요 · ${preview.reasons.first()}", color = Palette.warn, fontSize = 14.sp)
        }
    }
}

private val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
