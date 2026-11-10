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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.catmeter.app.CatMeterSession
import kr.catmeter.app.trip.Boarding

/**
 * 출발 전 탑승 QR.
 * 승객이 휴대폰으로 찍어 이번 이동의 탑승자로 등록한다. 주행 제한 중에는 QR 을 숨긴다
 * (QR 화면은 차가 서 있을 때만 띄운다는 Pleos 주의분산 원칙과 팀 결제 설계 문서의 규칙).
 */
@Composable
fun BoardingQrScreen(state: CatMeterSession.State, car: CarView, wide: Boolean, onClose: () -> Unit) {
    val qr = @Composable { size: Dp -> QrBlock(state.boardingCode, car.restricted, size) }
    val info = @Composable { BoardingInfo(state.passengers) }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("탑승 QR", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text("함께 탈 사람은 출발 전에 휴대폰으로 찍어 주세요", color = Palette.sub, fontSize = 15.sp)

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

        Button(onClick = onClose, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Text("탑승 확인 끝", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun QrBlock(code: String, restricted: Boolean, size: Dp) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (restricted) {
            Text(
                "주행 중에는 QR을 띄우지 않아요.\n차를 세우면 다시 보여 드려요.",
                color = Palette.warn, fontSize = 16.sp,
                modifier = Modifier
                    .size(size)
                    .background(Palette.warn.copy(alpha = 0.1f), RoundedCornerShape(16.dp))
                    .padding(16.dp),
            )
            return@Column
        }
        val image = remember(code) { qrBitmap(Boarding.link(code)) }
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
private fun BoardingInfo(passengers: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Step("1", "휴대폰 카메라로 QR을 찍어요")
            Step("2", "열린 화면에서 이름을 넣으면 탑승 완료")
            Step("3", "내릴 때는 아무것도 안 해도 돼요")
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
                    passengers.forEach { Chip(it, Palette.ok) }
                }
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
