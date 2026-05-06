package com.hydra.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hydra.app.ui.theme.Cyan
import com.hydra.app.ui.theme.LocalHydraColors

/**
 * Hydra wordmark — a hollow drop with a cyan inner dot, paired with the "Hydra" word.
 * Used in the top-left corner of every main screen.
 */
@Composable
fun HydraLogo(
    modifier: Modifier = Modifier,
    iconSize: Dp = 16.dp,
    color: Color = LocalHydraColors.current.ink,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Canvas(modifier = Modifier.size(width = iconSize * 0.85f, height = iconSize * 0.95f)) {
            val s = size
            val outline = Path().apply {
                moveTo(s.width * 0.5f, s.height * 0.06f)
                lineTo(s.width * 0.13f, s.height * 0.47f)
                cubicTo(
                    s.width * -0.05f, s.height * 0.7f,
                    s.width * 1.05f, s.height * 0.7f,
                    s.width * 0.87f, s.height * 0.47f,
                )
                close()
            }
            drawPath(
                outline,
                color = Cyan,
                style = Stroke(width = 1.6f),
            )
            drawCircle(
                color = Cyan,
                radius = s.width * 0.13f,
                center = Offset(s.width * 0.5f, s.height * 0.61f),
            )
        }
        Text(
            "Hydra",
            color = color,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.titleMedium,
        )
    }
}
