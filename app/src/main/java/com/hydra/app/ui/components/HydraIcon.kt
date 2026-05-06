package com.hydra.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hydra.app.ui.theme.LocalHydraColors

/** Icons used across Hydra screens — line glyphs sized to a 24×24 viewBox, rendered to Canvas. */
enum class HydraIconName { Drop, Bottle, Chart, Gear, Sync, Check, Arrow, Back, Plus }

@Composable
fun HydraIcon(
    name: HydraIconName,
    modifier: Modifier = Modifier,
    size: Dp = 18.dp,
    color: Color = LocalHydraColors.current.ink,
) {
    Canvas(modifier = modifier.size(size)) {
        val s = this.size.width
        val sw = (1.6f / 24f) * s
        val stroke = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (name) {
            HydraIconName.Drop -> {
                val p = Path().apply {
                    fillType = PathFillType.NonZero
                    moveTo(12f / 24f * s, 2.5f / 24f * s)
                    cubicTo(
                        12f / 24f * s, 2.5f / 24f * s,
                        5f / 24f * s, 9.9f / 24f * s,
                        5f / 24f * s, 14.7f / 24f * s,
                    )
                    cubicTo(
                        5f / 24f * s, 18.6f / 24f * s,
                        8.1f / 24f * s, 21.7f / 24f * s,
                        12f / 24f * s, 21.7f / 24f * s,
                    )
                    cubicTo(
                        15.9f / 24f * s, 21.7f / 24f * s,
                        19f / 24f * s, 18.6f / 24f * s,
                        19f / 24f * s, 14.7f / 24f * s,
                    )
                    cubicTo(
                        19f / 24f * s, 9.9f / 24f * s,
                        12f / 24f * s, 2.5f / 24f * s,
                        12f / 24f * s, 2.5f / 24f * s,
                    )
                    close()
                }
                drawPath(p, color = color)
            }
            HydraIconName.Bottle -> {
                val p = Path().apply {
                    moveTo(9f / 24f * s, 2f / 24f * s)
                    lineTo(15f / 24f * s, 2f / 24f * s)
                    moveTo(10f / 24f * s, 2f / 24f * s)
                    lineTo(10f / 24f * s, 5f / 24f * s)
                    lineTo(8.5f / 24f * s, 7.5f / 24f * s)
                    lineTo(8.5f / 24f * s, 20f / 24f * s)
                    cubicTo(
                        8.5f / 24f * s, 21.1f / 24f * s,
                        9.4f / 24f * s, 22f / 24f * s,
                        10.5f / 24f * s, 22f / 24f * s,
                    )
                    lineTo(13.5f / 24f * s, 22f / 24f * s)
                    cubicTo(
                        14.6f / 24f * s, 22f / 24f * s,
                        15.5f / 24f * s, 21.1f / 24f * s,
                        15.5f / 24f * s, 20f / 24f * s,
                    )
                    lineTo(15.5f / 24f * s, 7.5f / 24f * s)
                    lineTo(14f / 24f * s, 5f / 24f * s)
                    lineTo(14f / 24f * s, 2f / 24f * s)
                }
                drawPath(p, color = color, style = stroke)
            }
            HydraIconName.Chart -> {
                val p = Path().apply {
                    moveTo(4f / 24f * s, 20f / 24f * s); lineTo(4f / 24f * s, 10f / 24f * s)
                    moveTo(10f / 24f * s, 20f / 24f * s); lineTo(10f / 24f * s, 4f / 24f * s)
                    moveTo(16f / 24f * s, 20f / 24f * s); lineTo(16f / 24f * s, 13f / 24f * s)
                    moveTo(22f / 24f * s, 20f / 24f * s); lineTo(2f / 24f * s, 20f / 24f * s)
                }
                drawPath(p, color = color, style = stroke)
            }
            HydraIconName.Gear -> {
                drawCircle(color = color, radius = (3f / 24f) * s, center = Offset(s / 2f, s / 2f), style = stroke)
                // Eight tick marks around the dial — close enough to the gear teeth.
                val ticks = 8
                for (i in 0 until ticks) {
                    val angle = (i * Math.PI * 2 / ticks).toFloat()
                    val cx = s / 2f
                    val cy = s / 2f
                    val r1 = (6f / 24f) * s
                    val r2 = (9f / 24f) * s
                    drawLine(
                        color = color,
                        start = Offset(cx + kotlin.math.cos(angle) * r1, cy + kotlin.math.sin(angle) * r1),
                        end = Offset(cx + kotlin.math.cos(angle) * r2, cy + kotlin.math.sin(angle) * r2),
                        strokeWidth = sw,
                        cap = StrokeCap.Round,
                    )
                }
            }
            HydraIconName.Sync -> {
                val p = Path().apply {
                    moveTo(21f / 24f * s, 12f / 24f * s)
                    cubicTo(
                        21f / 24f * s, 17f / 24f * s,
                        17f / 24f * s, 21f / 24f * s,
                        12f / 24f * s, 21f / 24f * s,
                    )
                    lineTo(6f / 24f * s, 18.7f / 24f * s)
                    lineTo(3f / 24f * s, 16f / 24f * s)
                    moveTo(3f / 24f * s, 12f / 24f * s)
                    cubicTo(
                        3f / 24f * s, 7f / 24f * s,
                        7f / 24f * s, 3f / 24f * s,
                        12f / 24f * s, 3f / 24f * s,
                    )
                    lineTo(18f / 24f * s, 5.3f / 24f * s)
                    lineTo(21f / 24f * s, 8f / 24f * s)
                    moveTo(21f / 24f * s, 3f / 24f * s); lineTo(21f / 24f * s, 8f / 24f * s); lineTo(16f / 24f * s, 8f / 24f * s)
                    moveTo(3f / 24f * s, 21f / 24f * s); lineTo(3f / 24f * s, 16f / 24f * s); lineTo(8f / 24f * s, 16f / 24f * s)
                }
                drawPath(p, color = color, style = stroke)
            }
            HydraIconName.Check -> {
                val p = Path().apply {
                    moveTo(5f / 24f * s, 13f / 24f * s)
                    lineTo(9f / 24f * s, 17f / 24f * s)
                    lineTo(19f / 24f * s, 7f / 24f * s)
                }
                drawPath(p, color = color, style = Stroke(width = sw * 1.4f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            HydraIconName.Arrow -> {
                val p = Path().apply {
                    moveTo(9f / 24f * s, 6f / 24f * s)
                    lineTo(15f / 24f * s, 12f / 24f * s)
                    lineTo(9f / 24f * s, 18f / 24f * s)
                }
                drawPath(p, color = color, style = stroke)
            }
            HydraIconName.Back -> {
                val p = Path().apply {
                    moveTo(15f / 24f * s, 6f / 24f * s)
                    lineTo(9f / 24f * s, 12f / 24f * s)
                    lineTo(15f / 24f * s, 18f / 24f * s)
                }
                drawPath(p, color = color, style = stroke)
            }
            HydraIconName.Plus -> {
                drawLine(color, Offset(s / 2f, (5f / 24f) * s), Offset(s / 2f, (19f / 24f) * s), strokeWidth = sw, cap = StrokeCap.Round)
                drawLine(color, Offset((5f / 24f) * s, s / 2f), Offset((19f / 24f) * s, s / 2f), strokeWidth = sw, cap = StrokeCap.Round)
            }
        }
    }
}
