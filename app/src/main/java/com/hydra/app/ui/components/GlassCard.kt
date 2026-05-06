package com.hydra.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hydra.app.ui.theme.LocalHydraColors

/**
 * Glassmorphic surface — a low-opacity wash + hairline border, sitting on the page ground.
 * The visual primitive used for every grouped card in the new design.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 20.dp,
    contentPadding: Dp = 16.dp,
    onClick: (() -> Unit)? = null,
    accent: Brush? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = LocalHydraColors.current
    val shape = RoundedCornerShape(cornerRadius)
    val borderBrush = accent ?: Brush.linearGradient(listOf(colors.hair, colors.hair))
    Box(
        modifier = modifier
            .clip(shape)
            .background(colors.surface)
            .border(BorderStroke(1.dp, borderBrush), shape)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(contentPadding),
        content = content,
    )
}

/** Solid-fill variant for inset cards inside other glass cards. */
@Composable
fun InsetCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 12.dp,
    contentPadding: Dp = 12.dp,
    fill: Color? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = LocalHydraColors.current
    // Dark theme uses a deeper inset over the glass; light theme uses a faint tinted surface.
    val resolvedFill = fill ?: if (colors.isDark) Color(0x33000000) else Color(0x080A1424)
    val shape = RoundedCornerShape(cornerRadius)
    Box(
        modifier = modifier
            .clip(shape)
            .background(resolvedFill)
            .border(BorderStroke(1.dp, colors.hair), shape)
            .padding(contentPadding),
        content = content,
    )
}
