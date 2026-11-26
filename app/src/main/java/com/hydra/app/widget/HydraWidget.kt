package com.hydra.app.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.hydra.app.MainActivity
import com.hydra.app.R
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.flow.MutableStateFlow

private const val TAG = "HydraWidget"

/**
 * The widget's colours. Passed down as a parameter (not read from a global) so that every part
 * of the widget is redrawn when the theme changes; otherwise unchanged parts keep old colours.
 */
private data class Palette(
    val card: androidx.glance.unit.ColorProvider,
    val ink: androidx.glance.unit.ColorProvider,
    val soft: androidx.glance.unit.ColorProvider,
    val pillBg: androidx.glance.unit.ColorProvider,
    val pillInk: androidx.glance.unit.ColorProvider,
    val warnBg: androidx.glance.unit.ColorProvider,
    val warnInk: androidx.glance.unit.ColorProvider,
    val goodBg: androidx.glance.unit.ColorProvider,
    val goodInk: androidx.glance.unit.ColorProvider,
)

/**
 * [forceDark] is the app's own theme choice (Settings → Theme): null follows the system, so the
 * launcher switches between each day / night pair; otherwise both sides get the same colour.
 *
 * Glass card: a see-through tint the wallpaper shows through, with a soft sheen and a bright
 * rim drawn on top (see glassBitmap). Android can't blur what is behind a widget, so the tint
 * stays opaque enough for the text to remain readable on a busy wallpaper. Pills are
 * translucent too, tinted amber or green when they carry a status.
 */
private fun paletteFor(forceDark: Boolean?): Palette {
    fun pick(day: Long, night: Long) = when (forceDark) {
        true -> ColorProvider(day = Color(night), night = Color(night))
        false -> ColorProvider(day = Color(day), night = Color(day))
        null -> ColorProvider(day = Color(day), night = Color(night))
    }
    return Palette(
        card = pick(0xA6FFFFFF, 0x8C0B1322),
        ink = pick(0xFF0F1A2B, 0xFFF2F6FC),
        soft = pick(0xFF4A5A73, 0xFFC3CFE2),
        pillBg = pick(0x1A0F1A2B, 0x2EFFFFFF),
        pillInk = pick(0xFF26344A, 0xFFF2F6FC),
        warnBg = pick(0x38E08A00, 0x52FFB03A),
        warnInk = pick(0xFF6B3D00, 0xFFFFDCA0),
        goodBg = pick(0x2E18A56B, 0x4D4FE0A0),
        goodInk = pick(0xFF075C3C, 0xFFB4F7D8),
    )
}

/**
 * Home-screen widget. Small (2×2): the ring with today's total and the pace line.
 * Wide (4×2): the ring with the percentage, the total, and pills for pace, bottle battery
 * and filter. It only shows what the app has already stored — it never talks to the bottle —
 * so it is as fresh as the last sync; [refresh] is called whenever that data changes.
 */
open class HydraWidget : GlanceAppWidget() {

    // Exact: the layout is scaled to the real size, so it needs to know it.
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        runCatching { latest.value = WidgetData.load(context) }
            .onFailure { Log.w(TAG, "load failed: ${it.message}") }
        provideContent {
            val data by latest.collectAsState()
            val c = paletteFor(data?.forceDark)
            val size = LocalSize.current
            val wide = size.width >= WIDE_MIN_WIDTH && size.width.value >= size.height.value * 1.4f
            val density = LocalContext.current.resources.displayMetrics.density
            Box(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .background(c.card)
                    .cornerRadius(CORNER_DP.dp)
                    .clickable(actionStartActivity<MainActivity>()),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    provider = ImageProvider(glassBitmap(size, density)),
                    contentDescription = null,
                    modifier = GlanceModifier.fillMaxSize(),
                    contentScale = ContentScale.FillBounds,
                )
                val d = data
                when {
                    d == null -> Text("Open Hydra", style = TextStyle(color = c.soft, fontSize = 13.sp))
                    wide -> Wide(d, scaleFor(size, 720f), c)
                    else -> Small(d, scaleFor(size, 340f), c)
                }
            }
        }
    }

    companion object {
        private val WIDE_MIN_WIDTH = 250.dp

        /** Latest data, held here so a widget that is already on screen redraws from it. */
        private val latest = MutableStateFlow<WidgetData?>(null)

        /** Reload the stored data and redraw every placed widget. Safe to call from anywhere. */
        suspend fun refresh(context: Context) {
            runCatching {
                latest.value = WidgetData.load(context)
                HydraWidget().updateAll(context.applicationContext)
                HydraWideWidget().updateAll(context.applicationContext)
            }.onFailure { Log.w(TAG, "refresh failed: ${it.message}") }
        }
    }
}

class HydraWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = HydraWidget()
}

/** Identical widget listed separately in the picker so it starts out 4 cells wide. */
class HydraWideWidget : HydraWidget()

class HydraWideWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = HydraWideWidget()
}

// Both layouts are drawn in the mockup's own units (a 340-unit-tall card, 340 wide when small
// and 720 wide when wide) and scaled to whatever size the launcher gives the widget, so the
// proportions hold on any grid. Small text has a floor so it stays readable.
private fun scaleFor(size: DpSize, mockWidth: Float): Float =
    minOf(size.width.value / mockWidth, size.height.value / 340f)

@Composable
private fun Small(d: WidgetData, k: Float, c: Palette) {
    Column(
        modifier = GlanceModifier.fillMaxSize().padding((20 * k).dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = GlanceModifier.size((246 * k).dp), contentAlignment = Alignment.Center) {
            Ring(d)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "%,d".format(d.intakeMl),
                    style = TextStyle(color = c.ink, fontSize = (46 * k).sp, fontWeight = FontWeight.Bold),
                    maxLines = 1,
                )
                Text(
                    "of ${"%,d".format(d.goalMl)} ml",
                    style = TextStyle(color = c.soft, fontSize = maxOf(10f, 17 * k).sp),
                    maxLines = 1,
                )
            }
        }
        d.pace?.let {
            Spacer(GlanceModifier.height((10 * k).dp))
            Text(
                if (it.behind) "${it.text} pace" else it.text,
                style = TextStyle(
                    color = if (it.behind) c.warnInk else c.goodInk,
                    fontSize = maxOf(12f, 19 * k).sp,
                    fontWeight = FontWeight.Medium,
                ),
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun Wide(d: WidgetData, k: Float, c: Palette) {
    val pillFont = maxOf(12f, 19 * k)
    Row(
        modifier = GlanceModifier.fillMaxSize().padding(horizontal = (42 * k).dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(modifier = GlanceModifier.size((250 * k).dp), contentAlignment = Alignment.Center) {
            Ring(d)
            Text(
                "${d.percent}%",
                style = TextStyle(color = c.ink, fontSize = (50 * k).sp, fontWeight = FontWeight.Bold),
                maxLines = 1,
            )
        }
        Spacer(GlanceModifier.width((44 * k).dp))
        Column(modifier = GlanceModifier.defaultWeight()) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    "%,d".format(d.intakeMl),
                    style = TextStyle(color = c.ink, fontSize = (78 * k).sp, fontWeight = FontWeight.Bold),
                    maxLines = 1,
                )
                Text(" ml", style = TextStyle(color = c.soft, fontSize = (28 * k).sp), maxLines = 1)
            }
            Spacer(GlanceModifier.height((14 * k).dp))
            d.pace?.let { Pill(it.text, pillFont, k, c, warn = it.behind, good = !it.behind) }
            if (d.batteryPercent != null || d.filterPercent != null) {
                Spacer(GlanceModifier.height((14 * k).dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    d.batteryPercent?.let {
                        Pill("$it%", pillFont, k, c, warn = d.batteryLow, icon = R.drawable.ic_widget_battery, iconLabel = "Bottle battery")
                        Spacer(GlanceModifier.width((12 * k).dp))
                    }
                    d.filterPercent?.let {
                        Pill("$it%", pillFont, k, c, warn = d.filterLow, icon = R.drawable.ic_widget_filter, iconLabel = "Filter")
                    }
                }
            }
            // How fresh the data is: last line, lined up with the pills above it.
            WidgetData.ageText(d.lastSyncMs)?.let {
                Spacer(GlanceModifier.height((12 * k).dp))
                Text(
                    it,
                    modifier = GlanceModifier.padding(start = (pillFont * 0.4f).dp),
                    style = TextStyle(color = c.soft, fontSize = maxOf(11f, 16 * k).sp),
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun Pill(
    text: String,
    font: Float,
    k: Float,
    c: Palette,
    warn: Boolean,
    good: Boolean = false,
    icon: Int? = null,
    iconLabel: String? = null,
) {
    val bg = when { warn -> c.warnBg; good -> c.goodBg; else -> c.pillBg }
    val ink = when { warn -> c.warnInk; good -> c.goodInk; else -> c.pillInk }
    Row(
        modifier = GlanceModifier
            .background(bg)
            .cornerRadius((font * 1.3f).dp)
            .padding(horizontal = (font * 0.9f).dp, vertical = (font * 0.5f).dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            // The battery glyph is wider than tall; the filter glyph is square.
            val battery = icon == R.drawable.ic_widget_battery
            val h = if (battery) font * 0.95f else font * 1.25f
            val w = if (battery) h * 1.8f else h
            Image(
                provider = ImageProvider(icon),
                contentDescription = iconLabel,
                modifier = GlanceModifier.width(w.dp).height(h.dp),
                colorFilter = ColorFilter.tint(ink),
            )
            Spacer(GlanceModifier.width((font * 0.45f).dp))
        }
        Text(text, style = TextStyle(color = ink, fontSize = font.sp, fontWeight = FontWeight.Medium), maxLines = 1)
    }
}

@Composable
private fun Ring(d: WidgetData) {
    Image(
        provider = ImageProvider(ringBitmap(d.progress, if (d.progress < 1f) d.paceFraction else 0f)),
        contentDescription = "${d.percent}% of today's goal",
        modifier = GlanceModifier.fillMaxSize(),
        contentScale = ContentScale.Fit,
    )
}

private const val CORNER_DP = 26f

/**
 * The glass finish laid over the card's tint: a diagonal sheen that is brightest at the top
 * left, and a thin rim that is bright along the top edge and fades down the sides, as light
 * catches the edge of a pane. White only, so it suits the light and the dark card alike.
 */
private fun glassBitmap(size: DpSize, density: Float): Bitmap {
    val scale = density * 0.75f // a little under full resolution keeps the bitmap small
    val w = (size.width.value * scale).toInt().coerceIn(64, 1400)
    val h = (size.height.value * scale).toInt().coerceIn(64, 1400)
    val radius = CORNER_DP * scale
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val full = RectF(0f, 0f, w.toFloat(), h.toFloat())

    val sheen = Paint(Paint.ANTI_ALIAS_FLAG)
    sheen.shader = LinearGradient(
        0f, 0f, w * 0.75f, h.toFloat(),
        intArrayOf(0x38FFFFFF, 0x0FFFFFFF, 0x00FFFFFF), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP,
    )
    canvas.drawRoundRect(full, radius, radius, sheen)

    val rimWidth = 1.4f * scale
    val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = rimWidth
        shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf(0xB3FFFFFF.toInt(), 0x33FFFFFF, 0x1FFFFFFF), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP,
        )
    }
    val inset = rimWidth / 2f
    canvas.drawRoundRect(
        RectF(inset, inset, w - inset, h - inset), radius - inset, radius - inset, rim,
    )
    return bitmap
}

/**
 * The progress ring, drawn to a bitmap because widgets can't draw shapes. Its colours are
 * chosen to work on both the light and the dark card, so it needn't be redrawn on a theme change.
 * [pace] in (0, 1) adds the pace dot at that point round the ring.
 */
private fun ringBitmap(progress: Float, pace: Float): Bitmap {
    val size = 360
    val stroke = 30f
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val inset = stroke / 2f + 12f
    val rect = RectF(inset, inset, size - inset, size - inset)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        strokeCap = Paint.Cap.ROUND
    }
    paint.color = 0x4D8291AD
    canvas.drawArc(rect, 0f, 360f, false, paint)
    if (progress > 0f) {
        paint.color = 0xFFFFFFFF.toInt()
        paint.shader = LinearGradient(
            0f, 0f, size.toFloat(), size.toFloat(),
            // Green once the goal is reached, matching the app.
            if (progress >= 1f) 0xFF34C985.toInt() else 0xFF3BB8EE.toInt(),
            if (progress >= 1f) 0xFF12A7A0.toInt() else 0xFF8A7AF5.toInt(),
            Shader.TileMode.CLAMP,
        )
        canvas.drawArc(rect, -90f, 360f * progress.coerceIn(0f, 1f), false, paint)
        paint.shader = null
    }
    if (pace > 0f && pace < 1f) {
        val angle = Math.toRadians((360.0 * pace) - 90.0)
        val r = rect.width() / 2f
        val cx = rect.centerX() + (r * cos(angle)).toFloat()
        val cy = rect.centerY() + (r * sin(angle)).toFloat()
        val dot = Paint(Paint.ANTI_ALIAS_FLAG)
        dot.color = 0xFF0E1626.toInt()
        canvas.drawCircle(cx, cy, 15f, dot)
        dot.color = 0xFFFFFFFF.toInt()
        canvas.drawCircle(cx, cy, 10f, dot)
    }
    return bitmap
}
