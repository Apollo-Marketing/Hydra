package com.hydra.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hydra.app.ui.theme.Cyan
import com.hydra.app.ui.theme.LocalHydraColors

/**
 * Translucent bottle silhouette with the water level rendered as a cyan gradient inside.
 *
 * The shape is the prototype's `LTBottle` SVG ported to a Compose [Path]: a 70×120 viewBox
 * scaled into the requested width. Water fills from the bottom; surface line is a thin
 * cyan ellipse at the meniscus.
 *
 * @param fillFraction 0..1 — how full the bottle is. Clamped.
 */
@Composable
fun LiquidBottle(
    fillFraction: Float,
    modifier: Modifier = Modifier,
    width: Dp = 70.dp,
) {
    val frac = fillFraction.coerceIn(0f, 1f)
    val isDark = LocalHydraColors.current.isDark
    // Glass values lifted from LTBottle: dark uses near-white wash + cool stroke,
    // light uses a slightly opaque white with a darker stroke for contrast on the bg.
    val bodyFill = if (isDark) Color(0x0AFFFFFF) else Color(0x80FFFFFF)
    val stroke = if (isDark) Color(0x668CB4FF) else Color(0x660A1424)
    Canvas(modifier = modifier.size(width = width, height = width * (120f / 70f))) {
        val sx = size.width / 70f
        val sy = size.height / 120f
        val body = Path().apply {
            moveTo(24f * sx, 8f * sy)
            lineTo(46f * sx, 8f * sy)
            lineTo(46f * sx, 14f * sy)
            cubicTo(45f * sx, 16f * sy, 45f * sx, 18f * sy, 45f * sx, 22f * sy)
            lineTo(45f * sx, 104f * sy)
            cubicTo(45f * sx, 108f * sy, 41f * sx, 110f * sy, 39f * sx, 110f * sy)
            lineTo(31f * sx, 110f * sy)
            cubicTo(29f * sx, 110f * sy, 25f * sx, 108f * sy, 25f * sx, 104f * sy)
            lineTo(25f * sx, 22f * sy)
            cubicTo(25f * sx, 18f * sy, 25f * sx, 16f * sy, 24f * sx, 14f * sy)
            close()
        }
        val cap = Path().apply {
            addRect(Rect(28f * sx, 2f * sy, 42f * sx, 8f * sy))
        }

        drawPath(body, color = bodyFill)
        drawPath(body, color = stroke, style = Stroke(width = 1.4f * sx))
        drawPath(cap, color = stroke, style = Stroke(width = 1.4f * sx))

        if (frac > 0f) {
            val waterTop = (110f - frac * 102f) * sy
            clipPath(body, clipOp = ClipOp.Intersect) {
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(Cyan.copy(alpha = 0.95f), Cyan.copy(alpha = 0.55f)),
                        startY = waterTop,
                        endY = size.height,
                    ),
                    topLeft = Offset(18f * sx, waterTop),
                    size = Size(50f * sx, size.height - waterTop),
                )
                drawOval(
                    color = Cyan.copy(alpha = 0.9f),
                    topLeft = Offset(15f * sx, waterTop - 3f * sy),
                    size = Size(40f * sx, 6f * sy),
                )
            }
        }
    }
}
