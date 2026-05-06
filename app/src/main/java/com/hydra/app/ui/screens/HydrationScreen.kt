package com.hydra.app.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.Brush
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hydra.app.ble.BottleMath
import com.hydra.app.ble.BottleConfig
import com.hydra.app.ble.BottleConnection
import com.hydra.app.ble.ConnectionState
import com.hydra.app.ble.SyncState
import com.hydra.app.data.AppPreferencesRepository
import com.hydra.app.data.HydraDatabase
import com.hydra.app.data.SipRepository
import com.hydra.app.ui.components.ConnectionBadge
import com.hydra.app.ui.components.GlassCard
import com.hydra.app.ui.components.HourlyBarChart
import com.hydra.app.ui.components.HydraIcon
import com.hydra.app.ui.components.HydraIconName
import com.hydra.app.ui.components.HydraLogo
import com.hydra.app.ui.components.HydrationRing
import com.hydra.app.ui.components.HydrationRingCenter
import com.hydra.app.ui.components.LiquidBottle
import com.hydra.app.ui.components.ManualSipDialog
import com.hydra.app.ui.components.rememberRelativeTime
import com.hydra.app.ui.theme.Cyan
import com.hydra.app.ui.theme.InkOnAccent
import com.hydra.app.ui.theme.LocalHydraColors
import com.hydra.app.ui.theme.Violet
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun HydrationScreen(
    connection: BottleConnection,
    onReconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalHydraColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember(context) { AppPreferencesRepository.get(context) }
    val sipRepo = remember(context) { SipRepository(HydraDatabase.get(context).sipDao()) }

    val sips by sipRepo.observeAll().collectAsState(initial = emptyList())
    val dailyGoal by prefs.dailyGoalMl.collectAsState(initial = AppPreferencesRepository.DEFAULT_DAILY_GOAL_ML)
    val lastSyncMs by prefs.lastSyncMs.collectAsState(initial = 0L)
    val showStreak by prefs.showStreak.collectAsState(initial = true)

    val connState by connection.state.collectAsState()
    val syncState by connection.syncState.collectAsState()

    var today by remember { mutableStateOf(BottleMath.currentLogicalDate()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000L)
            val now = BottleMath.currentLogicalDate()
            if (now != today) today = now
        }
    }
    val bottleSize = BottleConfig.BOTTLE_SIZE_ML
    val todayMl = remember(sips, today) { BottleMath.intakeOnDateMl(sips, bottleSize, today) }
    val currentVolumeMl = remember(sips) { BottleMath.latestVolumeMl(sips, bottleSize) }
    val hourly = remember(sips, today) { BottleMath.hourlyIntakeMl(sips, bottleSize, today) }
    val streak = remember(sips, dailyGoal, today) { BottleMath.streakDays(sips, bottleSize, dailyGoal) }

    val percent = if (dailyGoal > 0) ((todayMl / dailyGoal) * 100).roundToInt().coerceAtLeast(0) else 0
    val ringProgress = if (dailyGoal > 0) (todayMl / dailyGoal).toFloat() else 0f

    var manualSipDialogOpen by rememberSaveable { mutableStateOf(false) }

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
            HydraLogo(iconSize = 16.dp)
            ConnectionBadge(state = connState, onRetry = onReconnect)
        }

        Column(modifier = Modifier.padding(horizontal = 22.dp)) {
            Text(
                today.format(DateTimeFormatter.ofPattern("EEEE, MMM d", Locale.getDefault())),
                color = colors.inkSoft,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                greeting(if (showStreak) streak else 0),
                color = colors.ink,
                fontWeight = FontWeight.SemiBold,
                fontSize = 24.sp,
            )
        }

        Box(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            HydrationRing(progress = ringProgress) {
                HydrationRingCenter(
                    eyebrow = "Today",
                    percent = percent,
                    footer = "${"%,d".format(todayMl.roundToInt())} of ${"%,d".format(dailyGoal)} ml",
                )
            }
        }

        BottleStatusCard(
            currentVolumeMl = currentVolumeMl,
            bottleSizeMl = bottleSize,
            lastSyncMs = lastSyncMs,
            syncing = syncState is SyncState.Syncing,
            canSync = connState == ConnectionState.Ready && syncState !is SyncState.Syncing,
            onSyncClick = { connection.syncSipLog() },
        )

        GlassCard(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
            contentPadding = 16.dp,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Text("Today", color = colors.ink, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                    Text(bestHourLabel(hourly) ?: "—", color = colors.inkSoft, style = MaterialTheme.typography.bodySmall)
                }
                HourlyBarChart(intakePerHour = hourly)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (showStreak) {
                StreakCard(streak = streak, modifier = Modifier.weight(1f))
            }
            LastSyncCard(lastSyncMs = lastSyncMs, modifier = Modifier.weight(1f))
        }

        LogSipButton(
            onClick = { manualSipDialogOpen = true },
            modifier = Modifier.padding(horizontal = 14.dp),
        )

        Spacer(Modifier.height(80.dp))
    }

    if (manualSipDialogOpen) {
        ManualSipDialog(
            onSave = { ml, atSec ->
                scope.launch { sipRepo.insertManual(volumeMl = ml, atSec = atSec) }
                manualSipDialogOpen = false
            },
            onDismiss = { manualSipDialogOpen = false },
        )
    }
}

@Composable
private fun LogSipButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Brush.linearGradient(listOf(Cyan, Violet)))
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "+ Log a sip",
            color = InkOnAccent,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
private fun BottleStatusCard(
    currentVolumeMl: Double?,
    bottleSizeMl: Int,
    lastSyncMs: Long,
    syncing: Boolean,
    canSync: Boolean,
    onSyncClick: () -> Unit,
) {
    val colors = LocalHydraColors.current
    val syncedAgo = rememberRelativeTime(epochMs = lastSyncMs, neverLabel = "never")
    val frac = currentVolumeMl?.let { (it / bottleSizeMl).toFloat().coerceIn(0f, 1f) } ?: 0f
    val ml = currentVolumeMl?.roundToInt() ?: 0

    GlassCard(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
        contentPadding = 14.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            LiquidBottle(fillFraction = frac, width = 42.dp)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("In your bottle", color = colors.inkSoft, style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        if (currentVolumeMl != null) ml.toString() else "—",
                        color = colors.ink,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 26.sp,
                    )
                    Text(
                        " / $bottleSizeMl ml",
                        color = colors.inkSoft,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(colors.hair),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(frac)
                            .height(4.dp)
                            .background(Cyan),
                    )
                }
                Text(
                    if (syncing) "Syncing…" else "Synced $syncedAgo",
                    color = colors.inkSoft,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            SyncButton(syncing = syncing, enabled = canSync, onClick = onSyncClick)
        }
    }
}

@Composable
private fun SyncButton(syncing: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    val transition = rememberInfiniteTransition(label = "sync_spin")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "sync_spin",
    )
    Column(
        modifier = Modifier
            .clip(shape)
            .background(Color(0x205CD6FF))
            .border(BorderStroke(1.dp, Cyan), shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        HydraIcon(
            name = HydraIconName.Sync,
            size = 16.dp,
            color = Cyan,
            modifier = if (syncing) Modifier.rotate(angle) else Modifier,
        )
        Text("Sync", color = Cyan, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun StreakCard(streak: Int, modifier: Modifier = Modifier) {
    val colors = LocalHydraColors.current
    GlassCard(modifier = modifier, cornerRadius = 18.dp, contentPadding = 14.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Streak", color = colors.inkSoft, style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    streak.toString(),
                    color = colors.ink,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 32.sp,
                )
                Text(
                    " days",
                    color = colors.inkSoft,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                repeat(7) { i ->
                    val on = i < streak.coerceAtMost(7)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(if (on) Cyan else colors.hair),
                    )
                }
            }
        }
    }
}

@Composable
private fun LastSyncCard(lastSyncMs: Long, modifier: Modifier = Modifier) {
    val colors = LocalHydraColors.current
    val syncedAgo = rememberRelativeTime(epochMs = lastSyncMs, neverLabel = "never")
    GlassCard(modifier = modifier, cornerRadius = 18.dp, contentPadding = 14.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Last sync", color = colors.inkSoft, style = MaterialTheme.typography.bodySmall)
            Text(
                if (lastSyncMs == 0L) "—" else syncedAgo,
                color = colors.ink,
                fontWeight = FontWeight.SemiBold,
                fontSize = 22.sp,
            )
            Spacer(Modifier.size(2.dp))
        }
    }
}

private fun bestHourLabel(hourly: DoubleArray): String? {
    val maxIdx = hourly.indices.maxByOrNull { hourly[it] } ?: return null
    if (hourly[maxIdx] <= 0.0) return null
    val ml = hourly[maxIdx].roundToInt()
    val label = when {
        maxIdx == 0 -> "12a"
        maxIdx < 12 -> "${maxIdx}a"
        maxIdx == 12 -> "12p"
        else -> "${maxIdx - 12}p"
    }
    return "Best: $label · $ml ml"
}

private fun greeting(streak: Int): String =
    when {
        streak >= 5 -> "You're on a streak 💧"
        streak >= 1 -> "$streak-day streak — keep going"
        else -> "Let's start sipping"
    }
