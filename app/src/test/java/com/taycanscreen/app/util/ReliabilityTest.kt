package com.taycanscreen.app.util

import com.taycanscreen.app.obd.Reading
import com.taycanscreen.app.obd.Taycan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReliabilityTest {
    @Test
    fun wrongClockIsExplainedInPlainWords() {
        val e = javax.net.ssl.SSLHandshakeException("Chain validation failed")
        assertEquals("The box's clock isn't set yet. Try again in a minute.", Friendly.net(e, "fallback"))
    }

    @Test
    fun noInternetIsExplainedInPlainWords() {
        val e = java.net.UnknownHostException("Unable to resolve host \"api.github.com\": No address associated with hostname")
        assertEquals("No internet right now. Try again when the box is online.", Friendly.net(e, "fallback"))
    }

    @Test
    fun otherFailuresNeverShowTheirTechnicalText() {
        val e = java.io.IOException("GitHub answered HTTP 403 {\"message\":\"rate limit\"}")
        assertEquals("Couldn't check for updates. Try again later.", Friendly.net(e, "Couldn't check for updates. Try again later."))
    }

    @Test
    fun speedIsHiddenThreeSecondsAfterTheLastAnswer() {
        assertEquals(3_000L, Taycan.SPEED_DIR.staleMs)
        val r = mapOf(Taycan.SPEED_DIR.id to Reading(48.0, "", at = 10_000L))
        assertEquals(48.0, Taycan.speedKmh(r, now = 12_900L)!!, 0.001)
        assertNull(Taycan.speedKmh(r, now = 13_100L))
    }

    @Test
    fun reverseSpeedIsShownAsPositive() {
        val r = mapOf(Taycan.SPEED_DIR.id to Reading(-6.5, "", at = 0L))
        assertEquals(6.5, Taycan.speedKmh(r, now = 100L)!!, 0.001)
    }

    @Test
    fun monotonicClockNeverGoesBackwards() {
        val a = Mono.ms()
        val b = Mono.ms()
        assertTrue(b >= a)
    }
}
