package kr.catmeter.app.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorNode
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.vectorResource
import kr.catmeter.app.R
import kr.catmeter.app.trip.Accessory
import kr.catmeter.app.trip.CatProfile
import kr.catmeter.app.trip.Fur

/**
 * 오늘의 고양이. 벡터 일러스트(art/cat 의 SVG → res/drawable 의 cat_ XML)에 털 색을 입히고 소품을 겹친다.
 *
 * - 원본 일러스트의 털 색 자리는 임시 색으로 칠해져 있고, 여기서 고른 털 색으로 바꿔 칠한다 (art/cat/README.md).
 * - 소품은 고양이와 같은 좌표계에 그려 두어 같은 크기로 겹치기만 하면 위치가 맞는다.
 * - 움직임은 없다. 이동 화면에서는 정차 중에만 보인다(DriveScreen).
 * groundColor 는 예전 Canvas 그림의 바닥선 색이었다. 지금 일러스트에는 바닥선이 없어 쓰지 않는다.
 */
@Composable
fun CatCanvas(
    cat: CatProfile,
    modifier: Modifier = Modifier,
    @Suppress("UNUSED_PARAMETER") groundColor: Color = Palette.ground,
) {
    val base = ImageVector.vectorResource(R.drawable.cat_loaf)
    val tinted = remember(base, cat.fur) { base.recolor(furColors(cat.fur)) }
    Box(modifier, contentAlignment = Alignment.Center) {
        Layer(tinted)
        // 그리는 순서: 목도리 → 리본 → 선글라스 → 모자 (모자가 맨 위)
        ACCESSORY_ORDER.filter { it in cat.accessories }.forEach { a ->
            Layer(ImageVector.vectorResource(accessoryRes(a)))
        }
    }
}

@Composable
private fun BoxScope.Layer(image: ImageVector) {
    Image(
        painter = rememberVectorPainter(image),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier.matchParentSize(),
    )
}

private val ACCESSORY_ORDER = listOf(Accessory.SCARF, Accessory.RIBBON, Accessory.SUNGLASSES, Accessory.HAT)

@DrawableRes
private fun accessoryRes(a: Accessory): Int = when (a) {
    Accessory.HAT -> R.drawable.cat_acc_hat
    Accessory.SUNGLASSES -> R.drawable.cat_acc_sunglasses
    Accessory.RIBBON -> R.drawable.cat_acc_ribbon
    Accessory.SCARF -> R.drawable.cat_acc_scarf
}

/** 원본 일러스트의 임시 색 → 털 색 */
private fun furColors(fur: Fur): Map<Int, Color> = mapOf(
    PLACEHOLDER_MAIN to Color(fur.main),
    PLACEHOLDER_SHADE to Color(fur.shade),
    PLACEHOLDER_LIGHT to Color(fur.light),
    PLACEHOLDER_PATTERN to if (fur.striped) Color(fur.shade) else Color.Transparent,
)

private val PLACEHOLDER_MAIN = Color(0xFFFF00FF).toArgb()
private val PLACEHOLDER_SHADE = Color(0xFF00FF00).toArgb()
private val PLACEHOLDER_LIGHT = Color(0xFF00FFFF).toArgb()
private val PLACEHOLDER_PATTERN = Color(0xFFFFFF00).toArgb()

/** 임시 색으로 칠한 path 의 채움·선 색을 바꾼 새 ImageVector */
private fun ImageVector.recolor(map: Map<Int, Color>): ImageVector {
    fun Brush?.swap(): Brush? {
        val c = (this as? SolidColor)?.value ?: return this
        return map[c.copy(alpha = 1f).toArgb()]?.let { SolidColor(it) } ?: this
    }
    val builder = ImageVector.Builder(name, defaultWidth, defaultHeight, viewportWidth, viewportHeight)
    fun addNode(node: VectorNode) {
        when (node) {
            is VectorGroup -> {
                builder.addGroup(
                    name = node.name, rotate = node.rotation, pivotX = node.pivotX, pivotY = node.pivotY,
                    scaleX = node.scaleX, scaleY = node.scaleY, translationX = node.translationX,
                    translationY = node.translationY, clipPathData = node.clipPathData,
                )
                node.forEach(::addNode)
                builder.clearGroup()
            }
            is VectorPath -> builder.addPath(
                pathData = node.pathData, pathFillType = node.pathFillType, name = node.name,
                fill = node.fill.swap(), fillAlpha = node.fillAlpha,
                stroke = node.stroke.swap(), strokeAlpha = node.strokeAlpha, strokeLineWidth = node.strokeLineWidth,
                strokeLineCap = node.strokeLineCap, strokeLineJoin = node.strokeLineJoin,
                strokeLineMiter = node.strokeLineMiter, trimPathStart = node.trimPathStart,
                trimPathEnd = node.trimPathEnd, trimPathOffset = node.trimPathOffset,
            )
        }
    }
    root.forEach(::addNode)
    return builder.build()
}
