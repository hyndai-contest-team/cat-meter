package kr.catmeter.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Palette {
    val bg = Color(0xFF14171C)
    val surface = Color(0xFF1E232B)
    val surfaceHigh = Color(0xFF2A313B)
    val ground = Color(0xFF3A4452)
    val text = Color(0xFFF2F4F7)
    val sub = Color(0xFF9AA4B2)
    val accent = Color(0xFFF4B860)
    val ok = Color(0xFF7FD1A8)
    val warn = Color(0xFFF2A65A)
    val paper = Color(0xFFFFFBF2)
    val paperInk = Color(0xFF2B2B2B)
    val paperSub = Color(0xFF7A746A)
}

@Composable
fun CatMeterTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.accent,
            onPrimary = Color(0xFF2B1D07),
            background = Palette.bg,
            surface = Palette.surface,
            surfaceVariant = Palette.surfaceHigh,
            onBackground = Palette.text,
            onSurface = Palette.text,
            onSurfaceVariant = Palette.sub,
            secondaryContainer = Palette.surfaceHigh,
            onSecondaryContainer = Palette.text,
        ),
        content = content,
    )
}

fun formatKm(km: Float): String = String.format(Locale.KOREA, "%.1f", km.coerceAtLeast(0f))

/** 1,250 */
fun formatNyang(nyang: Int): String = String.format(Locale.KOREA, "%,d", nyang)

/** 영수증용 1시간 5분 / 12분 / 40초 */
fun formatDuration(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    return when {
        h > 0 -> "${h}시간 ${m}분"
        m > 0 -> "${m}분"
        else -> "${s}초"
    }
}

fun formatTime(ms: Long): String = SimpleDateFormat("HH:mm", Locale.KOREA).format(Date(ms))
fun formatDate(ms: Long): String = SimpleDateFormat("yyyy.MM.dd (E)", Locale.KOREA).format(Date(ms))

/** 받침에 따라 '와/과' */
fun withParticle(name: String): String {
    val last = name.lastOrNull() ?: return name
    val hasFinal = last in '가'..'힣' && (last - '가') % 28 != 0
    return name + if (hasFinal) "과" else "와"
}
