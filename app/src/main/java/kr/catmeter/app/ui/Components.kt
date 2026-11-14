package kr.catmeter.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 화면 공통 부품. 디자인 캔버스(냥미터기)의 카드·알약 버튼·이름 동그라미를 옮긴 것이다. */

val CardShape = RoundedCornerShape(22.dp)
val RowShape = RoundedCornerShape(14.dp)
val PillShape = RoundedCornerShape(50)
val ButtonHeight = 54.dp

/** 어두운 바탕 위의 둥근 카드 */
@Composable
fun Card(
    modifier: Modifier = Modifier,
    padding: Dp = 18.dp,
    gap: Dp = 8.dp,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier.background(Palette.surface, CardShape).padding(padding),
        verticalArrangement = Arrangement.spacedBy(gap),
        horizontalAlignment = horizontalAlignment,
        content = content,
    )
}

/** 주 동작 버튼 (강조색 알약) */
@Composable
fun PrimaryPill(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(onClick = onClick, enabled = enabled, shape = PillShape, modifier = modifier) {
        Text(text, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/** 보조 동작 버튼 (테두리 알약) */
@Composable
fun OutlinePill(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = PillShape,
        // 테두리가 바탕과 구분되도록 보조 글자색을 쓴다 (바탕 대비 3:1 이상)
        border = BorderStroke(1.dp, if (enabled) Palette.sub else Palette.ground),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Palette.text),
        modifier = modifier,
    ) {
        Text(text, fontSize = 17.sp, maxLines = 1)
    }
}

/** 이름 첫 글자를 넣은 동그라미 */
@Composable
fun Avatar(name: String, color: Color, size: Dp = 36.dp) {
    Box(Modifier.size(size).background(color, CircleShape), contentAlignment = Alignment.Center) {
        Text(firstLetter(name), color = Palette.bg, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}

/** 이름의 첫 글자. 이모지처럼 두 칸짜리 글자도 깨지지 않게 코드 포인트 단위로 자른다. */
fun firstLetter(name: String): String =
    if (name.isEmpty()) "" else name.substring(0, name.offsetByCodePoints(0, 1))

/** 사람마다 다른 동그라미 색 (순서대로 돌려 쓴다) */
fun avatarColor(index: Int): Color = when (index % 3) {
    0 -> Palette.accent
    1 -> Palette.ok
    else -> Palette.sub
}

/** 큰 숫자 + 단위 */
@Composable
fun BigValue(value: String, unit: String, size: TextUnit, color: Color = Palette.text, unitColor: Color = color) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(value, color = color, fontSize = size, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(" $unit", color = unitColor, fontSize = (size.value * 0.34f).coerceAtLeast(14f).sp, fontWeight = FontWeight.Bold, maxLines = 1,
            modifier = Modifier.padding(bottom = (size.value * 0.14f).dp))
    }
}

/** 탑승자 한 줄: 동그라미, 이름과 설명, 오른쪽 상태 */
@Composable
fun PersonRow(
    name: String,
    index: Int,
    detail: String?,
    modifier: Modifier = Modifier,
    detailColor: Color = Palette.sub,
    background: Color = Palette.surfaceHigh,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        modifier.background(background, RowShape).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Avatar(name, avatarColor(index))
        Column(Modifier.weight(1f)) {
            Text(name, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            if (detail != null) Text(detail, color = detailColor, fontSize = 13.sp, maxLines = 2)
        }
        trailing()
    }
}
