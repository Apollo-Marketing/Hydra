package com.hydra.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.hydra.app.ui.theme.Cyan
import com.hydra.app.ui.theme.LocalHydraColors

private val WeekdayLabels = listOf("M", "T", "W", "T", "F", "S", "S")

/**
 * "When you drink" heatmap — a 7-row × cols grid coloured by relative intake. Each cell
 * is one hour bucket; cyan opacity ramps with intake, empty cells fall back to hairline.
 *
 * @param intensities row-major matrix [7 × cols]. 0 = empty, 4 = highest.
 */
@Composable
fun HydrationHeatmap(
    intensities: List<List<Int>>,
    modifier: Modifier = Modifier,
    cols: Int = 16,
) {
    val colors = LocalHydraColors.current
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(
                modifier = Modifier.width(20.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                WeekdayLabels.take(intensities.size.coerceAtLeast(1)).forEach { d ->
                    Text(
                        d,
                        color = colors.inkDim,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                intensities.forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        for (i in 0 until cols) {
                            val v = row.getOrNull(i) ?: 0
                            val color = if (v == 0) colors.hair else Cyan.copy(alpha = (0.10f + v * 0.20f).coerceAtMost(1f))
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(color),
                            )
                        }
                    }
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth().padding(start = 24.dp)) {
            listOf("6a", "10a", "2p", "6p", "10p").forEach { tick ->
                Text(
                    tick,
                    color = colors.inkDim,
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** Quantize a raw mL value into a 0..4 intensity bucket relative to the supplied max. */
fun quantizeIntensity(ml: Double, max: Double): Int {
    if (max <= 0.0 || ml <= 0.0) return 0
    val frac = (ml / max).coerceIn(0.0, 1.0)
    return when {
        frac < 0.25 -> 1
        frac < 0.5 -> 2
        frac < 0.75 -> 3
        else -> 4
    }
}
