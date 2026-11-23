package com.taycanscreen.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GpsSpeedTest {
    @Test fun convertsMetresPerSecondToMph() {
        assertEquals(29.08, GpsSpeed.mph(true, 13.0f, 500)!!, 0.01)
    }

    @Test fun standingStillReadsZero() {
        assertEquals(0.0, GpsSpeed.mph(true, 0.5f, 500)!!, 0.0) // 1.1 mph of GPS wander
    }

    @Test fun oldOrMissingSpeedGivesNothing() {
        assertNull(GpsSpeed.mph(true, 13.0f, 3_500))  // fix too old: "—", not an old number
        assertNull(GpsSpeed.mph(false, 13.0f, 200))   // fix without a speed
        assertNull(GpsSpeed.mph(true, Float.NaN, 200))
    }
}
