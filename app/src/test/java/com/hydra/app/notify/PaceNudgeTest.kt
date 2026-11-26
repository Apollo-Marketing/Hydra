package com.hydra.app.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PaceNudgeTest {
    private val paceSec = 14 * 3600 // 9 am wake, 1 am bed, minus the 2 h wind-down

    @Test fun `no nudge in the first two hours after waking`() {
        assertNull(PaceNudge.behindMl(0.0, 2000, 1.5f / 14f, paceSec))
    }

    @Test fun `nudges when well behind mid-day`() {
        // Half the pace window gone: 1000 ml due, 400 drunk.
        assertEquals(600, PaceNudge.behindMl(400.0, 2000, 0.5f, paceSec))
    }

    @Test fun `no nudge when only slightly behind`() {
        assertNull(PaceNudge.behindMl(800.0, 2000, 0.5f, paceSec))
    }

    @Test fun `no nudge when ahead or goal reached`() {
        assertNull(PaceNudge.behindMl(1500.0, 2000, 0.5f, paceSec))
        assertNull(PaceNudge.behindMl(2100.0, 2000, 0.9f, paceSec))
    }

    @Test fun `no nudge in the wind-down before bed or before waking`() {
        assertNull(PaceNudge.behindMl(0.0, 2000, 1f, paceSec))
        assertNull(PaceNudge.behindMl(0.0, 2000, 0f, paceSec))
    }

    @Test fun `no nudge without a goal`() {
        assertNull(PaceNudge.behindMl(0.0, 0, 0.5f, paceSec))
    }
}
