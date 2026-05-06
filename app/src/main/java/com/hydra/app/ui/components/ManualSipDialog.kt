package com.hydra.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hydra.app.ui.theme.Cyan
import com.hydra.app.ui.theme.InkOnAccent
import com.hydra.app.ui.theme.LocalHydraColors
import com.hydra.app.ui.theme.Violet
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val PRESETS_ML = listOf(100, 250, 500, 750)
private const val MIN_ML = 50
private const val MAX_ML = 2000
private const val STEP_ML = 50

private val TIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())

/**
 * Modal dialog for logging a sip by hand. The caller persists the chosen volume + timestamp
 * via `SipRepository.insertManual(volumeMl, atSec)`. The "When" row defaults to the current
 * local time and lets the user pick any earlier time *today* via a Material3 [TimePicker].
 * If the picked time is in the future (e.g. user picks 11pm at 3pm), it's silently clamped
 * back to "now" on save.
 */
@Composable
fun ManualSipDialog(
    initialMl: Int = 250,
    onSave: (volumeMl: Int, atSec: Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalHydraColors.current
    var ml by rememberSaveable { mutableIntStateOf(initialMl.coerceIn(MIN_ML, MAX_ML)) }
    var hour by rememberSaveable { mutableIntStateOf(LocalTime.now().hour) }
    var minute by rememberSaveable { mutableIntStateOf(LocalTime.now().minute) }
    var timePickerOpen by rememberSaveable { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surfaceSolid,
        titleContentColor = colors.ink,
        textContentColor = colors.inkSoft,
        title = { Text("Log a sip") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Text(
                        "%,d".format(ml),
                        style = TextStyle(
                            brush = Brush.linearGradient(listOf(Cyan, Violet)),
                            fontSize = 56.sp,
                            fontWeight = FontWeight.SemiBold,
                        ),
                    )
                    Text(
                        " ml",
                        color = colors.inkSoft,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(bottom = 10.dp),
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PRESETS_ML.forEach { preset ->
                        val active = preset == ml
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (active) Brush.linearGradient(listOf(Cyan, Violet))
                                    else Brush.linearGradient(listOf(colors.hair, colors.hair)),
                                )
                                .clickable { ml = preset }
                                .padding(vertical = 8.dp),
                        ) {
                            Text(
                                "$preset",
                                color = if (active) InkOnAccent else colors.inkSoft,
                                fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Custom",
                        color = colors.inkSoft,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Stepper(
                            label = "−",
                            enabled = ml > MIN_ML,
                            onClick = { ml = (ml - STEP_ML).coerceAtLeast(MIN_ML) },
                        )
                        Stepper(
                            label = "+",
                            enabled = ml < MAX_ML,
                            onClick = { ml = (ml + STEP_ML).coerceAtMost(MAX_ML) },
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(colors.hair)
                        .clickable { timePickerOpen = true }
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "When",
                        color = colors.inkSoft,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            LocalTime.of(hour, minute).format(TIME_FORMATTER),
                            color = colors.ink,
                            fontWeight = FontWeight.Medium,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "›",
                            color = colors.inkDim,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(ml, computeAtSec(hour, minute)) }) {
                Text("Save", color = Cyan)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = colors.inkSoft) }
        },
    )

    if (timePickerOpen) {
        SipTimePickerDialog(
            initialHour = hour,
            initialMinute = minute,
            onConfirm = { h, m ->
                hour = h
                minute = m
                timePickerOpen = false
            },
            onDismiss = { timePickerOpen = false },
        )
    }
}

/**
 * Combine the picked local hour/minute with today's local date and return epoch seconds.
 * Clamped to "not in the future" — if the user picked a time later than now (e.g. they
 * tapped 11pm at 3pm), use the current instant instead.
 */
private fun computeAtSec(hour: Int, minute: Int): Long {
    val zone = ZoneId.systemDefault()
    val now = Instant.now()
    val picked = LocalDate.now(zone).atTime(hour, minute).atZone(zone).toInstant()
    return (if (picked.isAfter(now)) now else picked).epochSecond
}

@Composable
private fun Stepper(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalHydraColors.current
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(colors.hair)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (enabled) colors.ink else colors.inkDim,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SipTimePickerDialog(
    initialHour: Int,
    initialMinute: Int,
    onConfirm: (hour: Int, minute: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalHydraColors.current
    val state = rememberTimePickerState(
        initialHour = initialHour,
        initialMinute = initialMinute,
        is24Hour = false,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surfaceSolid,
        titleContentColor = colors.ink,
        textContentColor = colors.inkSoft,
        title = { Text("When did you sip?") },
        text = {
            TimePicker(
                state = state,
                colors = TimePickerDefaults.colors(
                    clockDialColor = colors.hair,
                    clockDialSelectedContentColor = InkOnAccent,
                    clockDialUnselectedContentColor = colors.ink,
                    selectorColor = Cyan,
                    containerColor = colors.surfaceSolid,
                    periodSelectorBorderColor = colors.hair,
                    periodSelectorSelectedContainerColor = Cyan,
                    periodSelectorUnselectedContainerColor = Color.Transparent,
                    periodSelectorSelectedContentColor = InkOnAccent,
                    periodSelectorUnselectedContentColor = colors.inkSoft,
                    timeSelectorSelectedContainerColor = Cyan,
                    timeSelectorUnselectedContainerColor = colors.hair,
                    timeSelectorSelectedContentColor = InkOnAccent,
                    timeSelectorUnselectedContentColor = colors.ink,
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(state.hour, state.minute) }) {
                Text("OK", color = Cyan)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = colors.inkSoft)
            }
        },
    )
}
