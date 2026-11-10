package kr.catmeter.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.catmeter.app.CatMeterSession
import kr.catmeter.app.data.BoardingClient
import kr.catmeter.app.trip.TripRecord

/**
 * 도착 후 정산 영역 (영수증 이미지에는 들어가지 않는다).
 * - 출퇴근 함께 타기로 판정된 이동: 탑승 QR 로 탄 사람에게 자동결제(테스트)한 결과를 보여 준다.
 *   결제 수단을 등록하지 않았거나 출퇴근 길이 아니라고 고른 사람은 결제하지 않고 이유를 보여 준다.
 * - 그 밖의 이동: 결제 없이 냥만 표시하고 이유를 알려 준다.
 * 결제는 공모전용 테스트 결제다. 실제 출금은 없다.
 */
@Composable
fun SettlePanel(session: CatMeterSession, record: TripRecord, autoPay: CatMeterSession.AutoPay) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Palette.surface, RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val commute = record.commute
        if (!commute.eligible) {
            Text("결제 없이 냥만 표시해요", fontSize = 17.sp, fontWeight = FontWeight.Bold)
            commute.reasons.forEach { Text("· $it", color = Palette.sub, fontSize = 14.sp) }
            Text("출퇴근 시간대에 집 ↔ 회사를 함께 탄 경우에만 정산할 수 있어요.", color = Palette.sub, fontSize = 13.sp)
            return@Column
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${commute.window?.label} 함께 타기 자동결제", fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Chip("테스트 · 실제 출금 없음", Palette.warn)
        }
        val perPerson = record.bill.perPassenger(record.passengers.size)
        when {
            record.joins.isNotEmpty() -> Text("중간에 탄 사람은 탄 뒤 구간만 나눠 내요 (1냥 = 1원)", color = Palette.sub, fontSize = 14.sp)
            perPerson != null -> Text("1인당 ${formatNyang(perPerson)}원 (1냥 = 1원)", color = Palette.sub, fontSize = 14.sp)
        }

        when (autoPay) {
            CatMeterSession.AutoPay.None -> Text(
                if (record.passengers.isEmpty()) "함께 탄 사람이 없어 결제할 금액이 없어요."
                else "탑승 QR로 탄 사람이 없어 자동결제를 하지 않았어요. 함께 탄 사람에게 직접 받아 주세요.",
                color = Palette.sub, fontSize = 14.sp,
            )
            CatMeterSession.AutoPay.Charging -> Text("자동결제 중이에요…", color = Palette.sub, fontSize = 15.sp)
            is CatMeterSession.AutoPay.Failed -> {
                Text(autoPay.reason, color = Palette.warn, fontSize = 15.sp)
                OutlinedButton(onClick = session::chargeRiders) { Text("다시 결제하기", fontSize = 16.sp) }
            }
            is CatMeterSession.AutoPay.Done -> autoPay.charges.forEach { ChargeRow(it) }
        }

        Text(
            "앱 판정은 보조 장치예요. 출퇴근 길인지는 탈 때 승객이 휴대폰에서 직접 골랐어요.",
            color = Palette.sub, fontSize = 12.sp,
        )
    }
}

@Composable
private fun ChargeRow(charge: BoardingClient.Charge) {
    val (text, color) = when (charge.status) {
        BoardingClient.Charge.Status.PAID -> "${formatNyang(charge.amount)}원 · ${charge.methodLabel ?: "결제 수단"} 결제됨" to Palette.ok
        BoardingClient.Charge.Status.NO_METHOD -> "${formatNyang(charge.amount)}원 · 결제 수단 미등록 · 직접 받아 주세요" to Palette.warn
        BoardingClient.Charge.Status.NOT_COMMUTING -> "출퇴근 길 아님 · 결제 없이 냥만" to Palette.sub
        BoardingClient.Charge.Status.NOT_BOARDED -> "${formatNyang(charge.amount)}원 · QR로 타지 않음 · 직접 받아 주세요" to Palette.warn
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(charge.name, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text(text, color = color, fontSize = 15.sp)
    }
}
