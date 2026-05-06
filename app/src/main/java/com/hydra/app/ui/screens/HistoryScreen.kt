package com.hydra.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hydra.app.ble.BottleMath
import com.hydra.app.ble.BottleConfig
import com.hydra.app.data.AppPreferencesRepository
import com.hydra.app.data.HydraDatabase
import com.hydra.app.data.SipEntity
import com.hydra.app.data.SipRepository
import com.hydra.app.ui.components.GlassCard
import com.hydra.app.ui.components.HydrationHeatmap
import com.hydra.app.ui.components.WeeklyBarChart
import com.hydra.app.ui.components.quantizeIntensity
import com.hydra.app.ui.theme.Cyan
import com.hydra.app.ui.theme.InkOnAccent
import com.hydra.app.ui.theme.LocalHydraColors
import com.hydra.app.ui.theme.Violet
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private val Range = listOf("Day", "Week", "Month")

@Composable
fun HistoryScreen(modifier: Modifier = Modifier) {
    val colors = LocalHydraColors.current
    val context = LocalContext.current
    val sipRepo = remember(context) { SipRepository(HydraDatabase.get(context).sipDao()) }
    val scope = rememberCoroutineScope()
    val prefs = remember(context) { AppPreferencesRepository.get(context) }

    val sips by sipRepo.observeAll().collectAsState(initial = emptyList())
    val dailyGoal by prefs.dailyGoalMl.collectAsState(initial = AppPreferencesRepository.DEFAULT_DAILY_GOAL_ML)

    val today = BottleMath.currentLogicalDate()
    val bottleSize = BottleConfig.BOTTLE_SIZE_ML

    val weekly = remember(sips, today) { BottleMath.lastNDayIntakes(sips, bottleSize, n = 7) }
    val avgMl = remember(weekly) { weekly.map { it.second }.average().takeIf { it.isFinite() } ?: 0.0 }
    val goalsHit = remember(weekly, dailyGoal) {
        weekly.count { dailyGoal > 0 && it.second >= dailyGoal }
    }

    val heatmapMaxMl = remember(weekly) {
        weekly.flatMap { (date, _) ->
            val arr = BottleMath.hourlyIntakeMl(sips, bottleSize, date)
            arr.toList().subList(6, 22)
        }.maxOrNull() ?: 0.0
    }
    val heatmap = remember(weekly, heatmapMaxMl) {
        weekly.map { (date, _) ->
            val arr = BottleMath.hourlyIntakeMl(sips, bottleSize, date)
            (6 until 22).map { h -> quantizeIntensity(arr[h], heatmapMaxMl) }
        }
    }

    val todaySips = remember(sips, today) { sipsForDay(sips, bottleSize, today) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Spacer(Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text("History", color = colors.ink, style = MaterialTheme.typography.headlineMedium)
                Text(
                    weekRangeLabel(today),
                    color = colors.inkSoft,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            RangePicker()
        }

        GlassCard(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
            contentPadding = 16.dp,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Daily average", color = colors.inkSoft, style = MaterialTheme.typography.bodySmall)
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                "%,d".format(avgMl.roundToInt()),
                                color = colors.ink,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 36.sp,
                            )
                            Text(
                                " ml",
                                color = colors.inkSoft,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(bottom = 6.dp),
                            )
                        }
                    }
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Goals hit", color = colors.inkSoft, style = MaterialTheme.typography.bodySmall)
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                goalsHit.toString(),
                                color = Cyan,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 24.sp,
                            )
                            Text(
                                " / ${weekly.size}",
                                color = colors.inkSoft,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(bottom = 4.dp),
                            )
                        }
                    }
                }
                WeeklyBarChart(
                    daily = weekly,
                    goalMl = dailyGoal,
                    today = today,
                )
            }
        }

        GlassCard(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
            contentPadding = 14.dp,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("When you drink", color = colors.ink, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                    HeatmapLegend()
                }
                HydrationHeatmap(intensities = heatmap, cols = 16)
            }
        }

        GlassCard(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
            contentPadding = 14.dp,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "Today's sips",
                    color = colors.ink,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
                if (todaySips.isEmpty()) {
                    Text("No sips logged today.", color = colors.inkDim, style = MaterialTheme.typography.bodySmall)
                } else {
                    todaySips.forEachIndexed { idx, entry ->
                        SipTimelineRow(
                            entry = entry,
                            last = idx == todaySips.lastIndex,
                            onDelete = if (entry.isManual) {
                                { scope.launch { sipRepo.deleteManual(entry.timestampSec) } }
                            } else null,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(80.dp))
    }
}

@Composable
private fun RangePicker() {
    val colors = LocalHydraColors.current
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .clip(shape)
            .border(BorderStroke(1.dp, colors.hair), shape)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Range.forEach { label ->
            val active = label == "Week"
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(9.dp))
                    .background(
                        if (active) Brush.linearGradient(listOf(Cyan, Violet))
                        else Brush.linearGradient(listOf(Color.Transparent, Color.Transparent)),
                    )
                    .padding(horizontal = 12.dp, vertical = 5.dp),
            ) {
                Text(
                    label,
                    color = if (active) InkOnAccent else colors.inkSoft,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun HeatmapLegend() {
    val colors = LocalHydraColors.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Less", color = colors.inkDim, style = MaterialTheme.typography.labelSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            (1..4).forEach { v ->
                Box(
                    modifier = Modifier
                        .size(9.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Cyan.copy(alpha = (0.10f + v * 0.20f).coerceAtMost(1f))),
                )
            }
        }
        Text("More", color = colors.inkDim, style = MaterialTheme.typography.labelSmall)
    }
}

private data class SipRow(
    val timestampSec: Long,
    val timeLabel: String,
    val ml: Int,
    val refill: Boolean,
    val isManual: Boolean = false,
)

@Composable
private fun SipTimelineRow(entry: SipRow, last: Boolean, onDelete: (() -> Unit)? = null) {
    val colors = LocalHydraColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            entry.timeLabel,
            color = colors.inkSoft,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.width(64.dp),
        )
        Text(
            if (entry.refill) "Refill" else "Sip",
            color = if (entry.refill) Violet else colors.ink,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
        Text(
            buildString {
                if (entry.refill) append("+")
                append(entry.ml)
                append(" ml")
            },
            color = if (entry.refill) Violet else colors.ink,
            fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp,
        )
        if (onDelete != null) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onDelete),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "×",
                    color = colors.inkDim,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                )
            }
        }
    }
    if (!last) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(colors.hair),
        )
    }
}

private fun weekRangeLabel(today: LocalDate): String {
    val start = today.minusDays(6)
    val fmt = DateTimeFormatter.ofPattern("MMM d", Locale.getDefault())
    return "${start.format(fmt)} — ${today.format(fmt)}"
}

private fun sipsForDay(
    entries: List<SipEntity>,
    bottleSizeMl: Int,
    date: LocalDate,
): List<SipRow> {
    val zone = ZoneId.systemDefault()
    val sortedAsc = entries.sortedBy { it.timestampSec }
    val bleOnly = sortedAsc.filter { it.manualVolumeMl == null }
    val rows = mutableListOf<SipRow>()
    val timeFmt = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())

    // Pass 1: BLE-derived sips and refills via polynomial deltas. Manual entries are excluded
    // because their distanceMm = 0 produces a junk polynomial value that breaks neighbor deltas.
    for (i in 1 until bleOnly.size) {
        val curr = bleOnly[i]
        val instant = Instant.ofEpochSecond(curr.timestampSec)
        if (BottleMath.logicalDateFor(instant, zone) != date) continue
        val prevVol = BottleMath.volumeMl(bleOnly[i - 1].distanceMm, bottleSizeMl) ?: continue
        val currVol = BottleMath.volumeMl(curr.distanceMm, bottleSizeMl) ?: continue
        val drank = prevVol - currVol
        val refilled = currVol - prevVol
        val label = instant.atZone(zone).format(timeFmt).lowercase()
        when {
            drank > 5.0 -> rows.add(SipRow(curr.timestampSec, label, drank.roundToInt(), refill = false))
            refilled > BottleMath.REFILL_THRESHOLD_ML ->
                rows.add(SipRow(curr.timestampSec, label, refilled.roundToInt(), refill = true))
        }
    }

    // Pass 2: manually-logged sips contribute their manualVolumeMl directly. Always rendered
    // as "Sip" rows — a manual entry can't be a refill. `isManual = true` makes them
    // deletable in the UI.
    for (entry in sortedAsc) {
        val manual = entry.manualVolumeMl ?: continue
        val instant = Instant.ofEpochSecond(entry.timestampSec)
        if (BottleMath.logicalDateFor(instant, zone) != date) continue
        val label = instant.atZone(zone).format(timeFmt).lowercase()
        rows.add(SipRow(entry.timestampSec, label, manual.toInt(), refill = false, isManual = true))
    }

    return rows.sortedByDescending { it.timestampSec }
}
