package kr.catmeter.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.catmeter.app.CatMeterSession
import kr.catmeter.app.data.BoardingClient
import kr.catmeter.app.trip.Boarding
import kr.catmeter.app.trip.SeatPosition

/**
 * 출발 전 탑승 QR.
 * 승객이 휴대폰으로 찍어 이번 이동의 탑승자로 등록한다. 주행 제한 중에는 QR 을 숨긴다
 * (QR 화면은 차가 서 있을 때만 띄운다는 Pleos 주의분산 원칙과 팀 결제 설계 문서의 규칙).
 */
@Composable
fun BoardingQrScreen(
    session: CatMeterSession,
    state: CatMeterSession.State,
    car: CarView,
    wide: Boolean,
    onClose: () -> Unit,
    /** 이동 중 정차해서 띄운 QR. 중간에 탄 사람은 탄 뒤 구간만 나눠 낸다. */
    midTrip: Boolean = false,
    /** 벨트·좌석 센서로 감지한 자리의 QR. 휴대폰 페이지가 이 자리를 미리 채운다. */
    seat: SeatPosition? = null,
) {
    // 화면이 떠 있는 동안만 서버에서 탑승자를 받아 온다
    DisposableEffect(state.boardingCode) {
        session.openBoarding()
        onDispose { session.closeBoarding() }
    }
    val qr = @Composable { size: Dp ->
        QrBlock(state.boardingCode, state.boardingLink, car.restricted, size, seat, onRetry = session::openBoarding)
    }
    val info = @Composable {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            BoardingInfo(state.passengers, state.riders, pickSeat = seat == null && state.seats.isNotEmpty())
            if (seat == null) WaitingSeats(state, onPrompt = session::promptSeat)
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            when {
                seat != null -> "${seat.label} 탑승 QR"
                midTrip -> "중간 탑승 QR"
                else -> "탑승 QR"
            },
            fontSize = 24.sp, fontWeight = FontWeight.Bold,
        )
        Text(
            when {
                seat != null -> "${seat.label}에 앉은 분은 휴대폰으로 찍어 주세요" + if (midTrip) " · 탄 뒤 구간만 나눠 내요" else ""
                midTrip -> "지금 타는 분은 휴대폰으로 찍어 주세요 · 탄 뒤 구간만 나눠 내요"
                state.seats.isNotEmpty() -> "함께 탄 분은 모두 휴대폰으로 찍고, 앉은 자리를 골라 주세요"
                else -> "함께 탈 사람은 출발 전에 휴대폰으로 찍어 주세요"
            },
            color = Palette.sub, fontSize = 15.sp,
        )

        if (wide) {
            Row(
                Modifier.weight(1f).fillMaxWidth().padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) { qr(280.dp) }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { info() }
            }
        } else {
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                qr(220.dp)
                info()
            }
        }

        if (seat != null) {
            // 탑승 등록이 끝나면 자동으로 닫힌다. 아이·짐처럼 등록하지 않을 자리는 운전자가 넘긴다.
            OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Text("이 자리는 나중에 할게요", fontSize = 17.sp)
            }
        } else {
            Button(onClick = onClose, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Text("탑승 확인 끝", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun QrBlock(code: String, link: CatMeterSession.BoardingLink, restricted: Boolean, size: Dp, seat: SeatPosition?, onRetry: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val notice = @Composable { text: String, color: Color ->
            Text(
                text,
                color = color, fontSize = 16.sp,
                modifier = Modifier
                    .size(size)
                    .background(color.copy(alpha = 0.1f), RoundedCornerShape(16.dp))
                    .padding(16.dp),
            )
        }
        if (restricted) {
            notice("주행 중에는 QR을 띄우지 않아요.\n차를 세우면 다시 보여 드려요.", Palette.warn)
            return@Column
        }
        val joinUrl = when (link) {
            is CatMeterSession.BoardingLink.Ready -> if (seat != null) "${link.joinUrl}?seat=${seat.name}" else link.joinUrl
            is CatMeterSession.BoardingLink.Offline -> {
                notice("${link.reason}.\n이름을 직접 넣어 주세요.", Palette.warn)
                OutlinedButton(onClick = onRetry) { Text("다시 연결", fontSize = 16.sp) }
                return@Column
            }
            CatMeterSession.BoardingLink.Idle, CatMeterSession.BoardingLink.Connecting -> {
                notice("탑승 서버에 연결하는 중이에요…", Palette.sub)
                return@Column
            }
        }
        val image = remember(joinUrl) { qrBitmap(joinUrl) }
        Box(
            Modifier
                .background(Color.White, RoundedCornerShape(16.dp))
                .padding(12.dp),
        ) {
            Image(image, contentDescription = "탑승 QR", modifier = Modifier.size(size))
        }
        Text("탑승 코드 ${Boarding.display(code)}", color = Palette.sub, fontSize = 14.sp)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BoardingInfo(passengers: List<String>, riders: List<BoardingClient.Rider>, pickSeat: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Step("1", "휴대폰 카메라로 QR을 찍어요")
            Step("2", if (pickSeat) "앉은 자리를 고르고 이름을 넣으면 탑승 완료" else "열린 화면에서 이름을 넣으면 탑승 완료")
            Step("3", "결제 수단을 한 번 등록하면 내릴 때 자동결제")
            Text("테스트 결제 · 실제 출금 없음", color = Palette.warn, fontSize = 13.sp)
        }

        Column(
            Modifier
                .fillMaxWidth()
                .background(Palette.surface, RoundedCornerShape(16.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("지금 탑승 ${passengers.size}명", fontSize = 17.sp, fontWeight = FontWeight.Bold)
            if (passengers.isEmpty()) {
                Text("아직 탄 사람이 없어요", color = Palette.sub, fontSize = 15.sp)
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    passengers.forEach { name ->
                        val rider = riders.firstOrNull { it.name == name }
                        val who = rider?.seat?.let { "$name(${it.label})" } ?: name
                        when {
                            rider == null -> Chip("$who · 직접 입력", Palette.sub)
                            !rider.commuting -> Chip("$who · 출퇴근 아님", Palette.sub)
                            rider.paymentLabel == null -> Chip("$who · 결제 미등록", Palette.warn)
                            else -> Chip("$who · ${rider.paymentLabel}", Palette.ok)
                        }
                    }
                }
            }
        }
    }
}

/** 앉았지만 아직 탑승 등록하지 않은 자리. 운전자가 '나중에'로 넘긴 자리의 QR 을 다시 띄울 수 있다. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WaitingSeats(state: CatMeterSession.State, onPrompt: (SeatPosition) -> Unit) {
    val assigned = state.riders.mapNotNull { it.seat }.toSet()
    val waiting = state.seats.filter { (seat, signal) -> signal.taken && !seat.isDriver && seat !in assigned }.keys.sorted()
    if (waiting.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("앉았지만 아직 등록하지 않은 자리", color = Palette.sub, fontSize = 14.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            waiting.forEach { seat ->
                OutlinedButton(onClick = { onPrompt(seat) }) { Text("${seat.label} QR", fontSize = 15.sp) }
            }
        }
    }
}

@Composable
private fun Step(number: String, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            number,
            color = Palette.bg, fontSize = 14.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier
                .background(Palette.accent, RoundedCornerShape(50))
                .padding(horizontal = 9.dp, vertical = 2.dp),
        )
        Text(text, fontSize = 16.sp)
    }
}
