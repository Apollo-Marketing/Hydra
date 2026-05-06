package com.hydra.app.ui.components

import kotlin.math.ceil

/**
 * Round a raw axis value up to the nearest "nice" tick boundary so chart Y-axes look tidy.
 * The granularity of the boundary scales with magnitude — a 78 mL max snaps to 100, while
 * a 2,100 mL max snaps to 2,500.
 */
internal fun niceCeil(value: Double): Int {
    if (value <= 0.0) return 100
    val rounded = when {
        value < 100 -> ceil(value / 25.0) * 25
        value < 250 -> ceil(value / 50.0) * 50
        value < 500 -> ceil(value / 100.0) * 100
        value < 1000 -> ceil(value / 250.0) * 250
        value < 2000 -> ceil(value / 500.0) * 500
        else -> ceil(value / 1000.0) * 1000
    }
    return rounded.toInt()
}

/** Format an mL value compactly for axis labels: 250, 1.2k, 2k. */
internal fun formatTick(ml: Int): String =
    if (ml >= 1000) {
        if (ml % 1000 == 0) "${ml / 1000}k" else "%.1fk".format(ml / 1000.0)
    } else {
        ml.toString()
    }
