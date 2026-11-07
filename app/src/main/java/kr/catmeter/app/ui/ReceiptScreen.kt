package kr.catmeter.app.ui

import kr.catmeter.app.ui.cat3d.Cat3D
import kr.catmeter.app.ui.cat3d.CatPose
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kr.catmeter.app.CatMeterSession
import kr.catmeter.app.trip.TripRecord
import kr.catmeter.app.trip.TripTracker
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 도착 후: 오늘의 고양이와 이동 기록을 담은 고양 영수증. 저장·공유는 정차 중에만. */
@Composable
fun ReceiptScreen(session: CatMeterSession, state: CatMeterSession.State, car: CarView, wide: Boolean) {
    val record = state.record ?: return
    val layer = rememberGraphicsLayer()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    val fileName = remember(record.id) {
        "cat_receipt_" + SimpleDateFormat("yyyyMMdd_HHmm", Locale.KOREA).format(Date(record.endedAtMs))
    }

    fun export(share: Boolean) = scope.launch {
        message = runCatching {
            val bmp = layer.toImageBitmap().asAndroidBitmap()
            if (share) {
                if (ReceiptExporter.share(context, bmp, fileName)) null else "공유할 수 있는 앱이 없어요. 이미지 저장을 이용해 주세요."
            } else {
                "저장했어요 (${ReceiptExporter.save(context, bmp, fileName)})"
            }
        }.getOrElse { "실패했어요: ${it.message}" }
    }

    val actions = @Composable {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (car.restricted) {
                Text("주행 중에는 저장·공유할 수 없어요.", color = Palette.warn, fontSize = 15.sp)
            } else {
                SettlePanel(session, record)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { export(share = false) }, modifier = Modifier.weight(1f).height(52.dp)) {
                        Text("이미지 저장", fontSize = 17.sp)
                    }
                    OutlinedButton(onClick = { export(share = true) }, modifier = Modifier.weight(1f).height(52.dp)) {
                        Text("공유", fontSize = 17.sp)
                    }
                }
                OutlinedButton(onClick = session::backToSetup, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text("새 드라이브 준비", fontSize = 17.sp)
                }
            }
            message?.let { Text(it, color = Palette.sub, fontSize = 14.sp) }
        }
    }

    if (wide) {
        Row(Modifier.fillMaxSize().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Box(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
                ReceiptCard(record, layer)
            }
            Column(Modifier.weight(0.8f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.Center) {
                Text("도착했어요!", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text("${withParticle(record.cat.name)} 함께한 기록이에요.", color = Palette.sub, fontSize = 16.sp)
                Spacer(Modifier.height(20.dp))
                actions()
            }
        }
    } else {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ReceiptCard(record, layer)
            actions()
        }
    }
}

/** 이미지로 저장되는 영수증 본문. 화면에 그리는 동시에 GraphicsLayer 에 기록한다. */
@Composable
private fun ReceiptCard(record: TripRecord, layer: GraphicsLayer) {
    val mono = FontFamily.Monospace
    Column(
        Modifier
            .widthIn(max = 420.dp)
            .fillMaxWidth()
            .drawWithContent {
                layer.record { this@drawWithContent.drawContent() }
                drawLayer(layer)
            }
            .clip(RoundedCornerShape(6.dp))
            .background(Palette.paper)
            .padding(horizontal = 22.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("고양 영수증", color = Palette.paperInk, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(formatDate(record.startedAtMs), color = Palette.paperSub, fontSize = 14.sp, fontFamily = mono)
        Dashed()
        Cat3D(
            cat = record.cat,
            pose = CatPose.RECEIPT,
            modifier = Modifier.fillMaxWidth().height(190.dp),
        )
        Text(
            "${withParticle(record.cat.name)} 함께한 ${record.rideNumber}번째 드라이브",
            color = Palette.paperInk, fontSize = 17.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
        )
        Dashed()
        record.destinationName?.let { Line("목적지", it) }
        Line("출발", formatTime(record.startedAtMs))
        Line("도착", formatTime(record.endedAtMs))
        Line("함께 달린 거리", "${formatKm(record.distanceKm)} km")
        Line("함께한 시간", formatDuration(record.durationMs))
        Line("달린 시간", formatDuration(record.movingMs))
        Line("쉬어 간 횟수", "${record.stopCount}번")
        if (record.cat.accessories.isNotEmpty()) {
            Line("오늘의 소품", record.cat.accessories.joinToString(", ") { it.label })
        }
        Dashed()
        val bill = record.bill
        Text("냥수증", color = Palette.paperInk, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Line("거리 요금", "${formatNyang(bill.meter.distanceNyang)}냥")
        Line("시간 요금", "${formatNyang(bill.meter.timeNyang)}냥")
        Line(bill.energy.kind.label, "${formatNyang(bill.energyNyang)}냥")
        Text(
            "${energyBasis(bill)} · ${formatNyang(bill.energy.wonPerUnit)}원/${bill.energy.kind.unit}",
            color = Palette.paperSub, fontSize = 11.sp, fontFamily = mono, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.End,
        )
        Line("합계", "${formatNyang(bill.totalNyang)}냥")
        bill.perPassenger(record.passengers.size)?.let { Line("동승자 1인당", "${formatNyang(it)}냥") }
        Text(
            when {
                record.settledAtMs != null -> "${record.commute.window?.label} 함께 타기 · 정산 완료(모의)"
                record.commute.eligible -> "${record.commute.window?.label} 함께 타기 · 정산 가능"
                else -> "결제 없는 냥 기록"
            },
            color = Palette.paperSub, fontSize = 12.sp, fontFamily = mono,
        )
        if (record.passengers.isNotEmpty()) {
            Dashed()
            Text("함께 탄 집사들", color = Palette.paperSub, fontSize = 13.sp)
            Text(record.passengers.joinToString("  "), color = Palette.paperInk, fontSize = 17.sp, textAlign = TextAlign.Center)
        }
        Dashed()
        Text(
            if (record.distanceSource == TripTracker.DistanceSource.ODOMETER) "거리: 계기판 누적 거리 기준" else "거리: 차속으로 추정한 값",
            color = Palette.paperSub, fontSize = 11.sp, fontFamily = mono,
        )
        Text("CAT METER · 고양 미터기", color = Palette.paperSub, fontSize = 11.sp, fontFamily = mono)
    }
}

@Composable
private fun Line(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, color = Palette.paperSub, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Text(value, color = Palette.paperInk, fontSize = 15.sp, fontWeight = FontWeight.Medium, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun Dashed() {
    HorizontalDivider(Modifier.padding(vertical = 10.dp), color = Palette.paperSub.copy(alpha = 0.35f))
}
