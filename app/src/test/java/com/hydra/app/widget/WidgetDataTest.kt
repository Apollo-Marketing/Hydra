package com.hydra.app.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WidgetDataTest {
    @Test fun `behind, ahead and on pace`() {
        assertEquals(PaceStatus("150 ml behind", true), WidgetData.paceStatus(1250, 2000, 0.7f))
        assertEquals(PaceStatus("250 ml ahead", false), WidgetData.paceStatus(1250, 2000, 0.5f))
        assertEquals(PaceStatus("On pace", false), WidgetData.paceStatus(1050, 2000, 0.5f))
    }

    @Test fun `goal reached wins, and nothing before the day starts or without a goal`() {
        assertEquals(PaceStatus("Goal reached", false), WidgetData.paceStatus(2100, 2000, 0.5f))
        assertNull(WidgetData.paceStatus(0, 2000, 0f))
        assertNull(WidgetData.paceStatus(500, 0, 0.5f))
    }

    @Test fun `age text`() {
        val now = 10_000_000_000L
        assertNull(WidgetData.ageText(0L, now))
        assertEquals("just now", WidgetData.ageText(now - 20_000L, now))
        assertEquals("20 min ago", WidgetData.ageText(now - 20 * 60_000L, now))
        assertEquals("3 h ago", WidgetData.ageText(now - 200 * 60_000L, now))
        assertEquals("2 d ago", WidgetData.ageText(now - 50 * 3_600_000L, now))
    }
}
