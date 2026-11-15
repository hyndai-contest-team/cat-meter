package kr.catmeter.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
 * - 그 밖의 이동: 결제 없이 기록만 남기고 이유를 알려 준다.
 * 결제는 공모전용 테스트 결제다. 실제 출금은 없다.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettlePanel(session: CatMeterSession, record: TripRecord, autoPay: CatMeterSession.AutoPay) {
    Card(Modifier.fillMaxWidth()) {
        val commute = record.commute
        if (!commute.eligible) {
            Text("결제 없이 기록만 남겨요", fontSize = 17.sp, fontWeight = FontWeight.Bold)
            commute.reasons.forEach { Text("· $it", color = Palette.sub, fontSize = 14.sp) }
            Text("출퇴근 시간대에 집 ↔ 회사를 함께 탄 경우에만 정산할 수 있어요.", color = Palette.sub, fontSize = 13.sp)
            return@Card
        }

        // 제목과 칩이 한 줄에 안 들어가면 칩이 다음 줄로 내려간다
        FlowRow(verticalArrangement = Arrangement.spacedBy(6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${commute.window?.label} 함께 타기 자동결제", fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Chip("테스트 · 실제 출금 없음", Palette.warn, background = Palette.surfaceHigh)
        }
        // 실비(주유비·충전비)만, 운전자까지 포함해 나눈다. 냥은 결제에 쓰지 않는다
        val bill = record.bill
        val split = record.split
        val sharing = record.passengers.size - record.notSharing.size
        Text(
            "${bill.energy.kind.label} ${formatNyang(bill.energyWon)}원" +
                (if (bill.tollWon > 0) "과 통행료 ${formatNyang(bill.tollWon)}원" else "") + "을 " + when {
                record.joins.isNotEmpty() || record.leaves.isNotEmpty() ->
                    "각자 탄 구간만큼, 운전자도 포함해 나눠요." +
                        (if (bill.tollWon > 0) " 통행료는 요금소를 지날 때 타고 있던 사람끼리 나눠요." else "") +
                        " 먼저 내린 사람은 내릴 때 결제했어요"
                sharing > 0 -> "운전자까지 ${sharing + 1}명이 똑같이 나눠요"
                else -> "나눌 사람이 없어 운전자가 내요"
            },
            color = Palette.sub, fontSize = 14.sp,
        )
        if (record.passengers.isNotEmpty()) {
            Row(Modifier.fillMaxWidth()) {
                Text("운전자 몫", fontSize = 15.sp, modifier = Modifier.weight(1f))
                Text("${formatNyang(split.driverWon)}원", fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
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
                OutlinePill("다시 결제하기", session::chargeRiders)
            }
            is CatMeterSession.AutoPay.Done -> autoPay.charges.forEachIndexed { i, c -> ChargeRow(c, i, record) }
        }

        Text(
            "앱 판정은 보조 장치예요. 출퇴근 길인지는 탈 때 승객이 휴대폰에서 직접 골랐어요.",
            color = Palette.sub, fontSize = 12.sp,
        )
    }
}

/** 한 사람의 결제 결과: 탄 구간, 금액, 상태 */
@Composable
private fun ChargeRow(charge: BoardingClient.Charge, index: Int, record: TripRecord) {
    val (status, color) = when (charge.status) {
        BoardingClient.Charge.Status.PAID -> "${charge.methodLabel ?: "결제 수단"} 결제됨" to Palette.ok
        BoardingClient.Charge.Status.NO_METHOD -> "결제 수단 미등록 · 직접 받아 주세요" to Palette.warn
        BoardingClient.Charge.Status.NOT_COMMUTING -> "출퇴근 길 아님 · 나누는 인원에서 뺐어요" to Palette.sub
        BoardingClient.Charge.Status.NOT_BOARDED -> "QR로 타지 않음 · 직접 받아 주세요" to Palette.warn
    }
    val from = record.joins[charge.name]?.distanceKm ?: 0f
    val leave = record.leaves[charge.name]
    val to = leave?.distanceKm ?: record.distanceKm
    val section = "${formatKm(from)} → ${formatKm(to)} km" +
        (if (charge.name in record.joins) " · 중간 탑승" else "") +
        (if (leave != null) " · 먼저 내림" else "")
    Column(Modifier.fillMaxWidth().background(Palette.surfaceHigh, RowShape)) {
        PersonRow(charge.name, index, detail = section, modifier = Modifier.fillMaxWidth(), background = Color.Transparent) {
            if (charge.status != BoardingClient.Charge.Status.NOT_COMMUTING) {
                BigValue(formatNyang(charge.amount), "원", 22.sp, unitColor = Palette.sub)
            }
        }
        Text(status, color = color, fontSize = 13.sp, modifier = Modifier.padding(start = 60.dp, end = 12.dp, bottom = 10.dp))
    }
}
