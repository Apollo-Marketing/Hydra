package com.hydra.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hydra.app.ui.theme.Cyan
import com.hydra.app.ui.theme.LocalHydraColors

data class GlassNavItem(
    val label: String,
    val icon: HydraIconName,
)

/**
 * Floating bottom tab bar — a translucent pill with rounded inset chips for each tab.
 * Active chip glows cyan with a subtle background tint; inactive chips are dim.
 */
@Composable
fun GlassNavigationBar(
    items: List<GlassNavItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalHydraColors.current
    val barShape = RoundedCornerShape(22.dp)
    // rgba(20,30,50,0.7) on dark, rgba(255,255,255,0.85) on light per LTTabBar.
    val barBg = if (colors.isDark) Color(0xB3141E32) else Color(0xD9FFFFFF)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .clip(barShape)
            .background(barBg)
            .border(BorderStroke(1.dp, colors.hair), barShape)
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEachIndexed { idx, item ->
            NavTab(
                item = item,
                selected = idx == selectedIndex,
                onClick = { onSelect(idx) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun NavTab(
    item: GlassNavItem,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalHydraColors.current
    val tabShape = RoundedCornerShape(16.dp)
    val tint = if (selected) Cyan else colors.inkDim
    // Active-chip wash: cyanGlow on dark (0x40), a slightly darker 0x2E on light per LT spec.
    val activeBg = if (colors.isDark) Color(0x405CD6FF) else Color(0x2E5CD6FF)
    Column(
        modifier = modifier
            .clip(tabShape)
            .clickable(onClick = onClick)
            .background(if (selected) activeBg else Color.Transparent)
            .padding(vertical = 10.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        HydraIcon(name = item.icon, size = 18.dp, color = tint)
        Text(
            text = item.label,
            color = tint,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}
