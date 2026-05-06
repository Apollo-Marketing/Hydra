package com.hydra.app.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Theme-variant color tokens. Shared accents (cyan / violet / warn / ok / gradient) live as
 * top-level vals in [Color] — they don't change between themes. Everything that does change —
 * page ground, glass surfaces, ink, hairlines — flows through here so a single CompositionLocal
 * swap reflows the whole UI.
 *
 * Token values lift directly from the design handoff (`LT` const in `screens-liquid.jsx`):
 * dark mirrors today's palette; light mirrors the spec's light-mode tokens.
 */
data class HydraColors(
    val bg: Color,
    val surface: Color,
    val surfaceSolid: Color,
    val ink: Color,
    val inkSoft: Color,
    val inkDim: Color,
    val hair: Color,
) {
    val isDark: Boolean get() = this === Dark

    companion object {
        val Dark = HydraColors(
            bg = Color(0xFF080D18),
            surface = Color(0x0AFFFFFF),       // rgba(255,255,255,0.04)
            surfaceSolid = Color(0xFF10172A),
            ink = Color(0xFFEAF3FF),
            inkSoft = Color(0x9EEAF3FF),       // 62%
            inkDim = Color(0x59EAF3FF),        // 35%
            hair = Color(0x1F8CB4FF),          // rgba(140,180,255,0.12)
        )

        val Light = HydraColors(
            bg = Color(0xFFF4F7FB),
            surface = Color(0xFFFFFFFF),
            surfaceSolid = Color(0xFFFFFFFF),
            ink = Color(0xFF0A1424),
            inkSoft = Color(0xFF5A6478),
            inkDim = Color(0xFF9CA5B8),
            hair = Color(0x140A1424),          // rgba(10,20,36,0.08)
        )
    }
}

val LocalHydraColors = staticCompositionLocalOf { HydraColors.Dark }
