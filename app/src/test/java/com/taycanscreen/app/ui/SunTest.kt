package com.taycanscreen.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class SunTest {
    private val lat = 21.3069
    private val lon = -157.8583 // Honolulu
    private fun alt(utc: String) = Sun.altitudeDeg(lat, lon, Instant.parse(utc).toEpochMilli())

    @Test fun honoluluNovember() {
        assertEquals(25.0, alt("2026-11-08T18:40:00Z"), 1.0)   // 8:40 am HST: day
        assertEquals(0.7, alt("2026-11-09T03:45:00Z"), 1.0)    // ~sunset 5:50 pm HST
        assertEquals(-29.9, alt("2026-11-09T06:00:00Z"), 1.0)  // 8 pm HST: night
        assertEquals(-0.6, alt("2026-11-09T16:40:00Z"), 1.0)   // ~sunrise 6:43 am HST
    }
}
