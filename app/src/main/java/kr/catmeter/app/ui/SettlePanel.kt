package kr.catmeter.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kr.catmeter.app.CatMeterSession
import kr.catmeter.app.trip.TripRecord

/**
 * 도착 후 정산 영역 (영수증 이미지에는 들어가지 않는다).
 * - 출퇴근 함께 타기로 판정된 이동: 동승자 출퇴근 확인 → 1인당 금액 QR → 모의 결제 완료.
 * - 그 밖의 이동: 결제 없이 냥만 표시하고 이유를 알려 준다.
 * 결제는 공모전용 모의 결제다. QR 에는 실제 결제 정보가 없다.
 */
@Composable
fun SettlePanel(session: CatMeterSession, record: TripRecord) {
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

        val perPerson = record.bill.perPassenger(record.passengers.size)
        Text("${commute.window?.label} 함께 타기 정산", fontSize = 17.sp, fontWeight = FontWeight.Bold)
        when {
            record.settledAtMs != null -> {
                Text("정산을 마쳤어요 (모의 결제)", color = Palette.ok, fontSize = 15.sp)
            }
            perPerson == null -> {
                Text("함께 탄 사람이 없어 정산할 금액이 없어요.", color = Palette.sub, fontSize = 14.sp)
            }
            else -> {
                var confirmed by rememberSaveable(record.id) { mutableStateOf(false) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = confirmed, onCheckedChange = { confirmed = it })
                    Text("함께 탄 사람도 모두 출퇴근 길이에요", fontSize = 15.sp)
                }
                if (confirmed) {
                    Text("1인당 ${formatNyang(perPerson)}원 (1냥 = 1원)", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    val qr = remember(record.id, perPerson) {
                        qrBitmap("catmeter://settle?trip=${record.id}&amount=$perPerson&currency=KRW&mock=1")
                    }
                    Image(qr, contentDescription = "정산 QR", modifier = Modifier.size(180.dp).align(Alignment.CenterHorizontally))
                    Text("휴대폰으로 스캔해 주세요 (모의 결제)", color = Palette.sub, fontSize = 13.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
                    Button(onClick = session::markSettled, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                        Text("결제 완료 (모의)", fontSize = 17.sp)
                    }
                }
            }
        }
        Text(
            "앱 판정은 보조 장치예요. 출퇴근 함께 타기에 해당하는지는 운전자와 동승자가 확인해 주세요.",
            color = Palette.sub, fontSize = 12.sp,
        )
    }
}

private fun qrBitmap(content: String, sizePx: Int = 512): ImageBitmap {
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, mapOf(EncodeHintType.MARGIN to 1))
    val pixels = IntArray(sizePx * sizePx) { i ->
        if (matrix[i % sizePx, i / sizePx]) Color.Black.toArgbInt() else Color.White.toArgbInt()
    }
    return Bitmap.createBitmap(pixels, sizePx, sizePx, Bitmap.Config.ARGB_8888).asImageBitmap()
}

private fun Color.toArgbInt(): Int = android.graphics.Color.argb(
    (alpha * 255).toInt(), (red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt(),
)
