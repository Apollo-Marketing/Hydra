package com.hydra.app.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

// ── Hydra "Liquid Tech" — shared accents ───────────────────────────────────────
// Theme-invariant tokens. Cyan, violet, warn, and the signature gradient look the
// same in dark and light modes — they ride on top of the variant tokens defined
// in [HydraColors]. Read variant tokens via LocalHydraColors.current.

val Cyan = Color(0xFF5CD6FF)
val CyanGlow = Color(0x405CD6FF)             // 25% — for shadows/halos
val Violet = Color(0xFF9B8CFF)
val Warn = Color(0xFFFF9C5C)
val Ok = Color(0xFF7BE4A8)
/** A day that reached its goal: the fill for calendar cells and chart bars, in both themes. */
val Goal = Color(0xFF34C985)
/** Second stop for the goal-reached ring, so it keeps a gradient like the everyday ring. */
val GoalDeep = Color(0xFF12A7A0)

/** Goal green for text and small marks, dark enough to read on the light theme. */
val HydraColors.goalInk: Color get() = if (isDark) Ok else Color(0xFF0E8A52)

val InkOnAccent = Color(0xFF001220)          // dark text on cyan/gradient buttons

/** Signature linear-gradient(135deg, cyan, violet) — used on CTAs, ring, big numbers. */
val HydraGradient = Brush.linearGradient(colors = listOf(Cyan, Violet))

// ── Dark-only ground stops ─────────────────────────────────────────────────────
// These three stops drive the deep navy radial wash painted by GradientBackground
// in dark mode. Light mode paints a flat HydraColors.Light.bg, so these values
// only matter on the dark theme.

val BgRadialTop = Color(0xFF1A2238)
val BgRadialMid = Color(0xFF0A0E1A)
val BgRadialBottom = Color(0xFF060912)
