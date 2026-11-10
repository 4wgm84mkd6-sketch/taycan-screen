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
        // 30 July 2025, the date the box starts with before it syncs.
        val msg = Friendly.net(e, "fallback", nowMs = 1_753_843_416_000L)
        assertTrue(msg, msg.startsWith("The box's clock is wrong (it says "))
        assertTrue(msg, "2025" in msg && "try again by itself" in msg)
        assertTrue(Friendly.clockIsWrong(1_753_843_416_000L))
    }

    @Test
    fun secureFailureWithTheRightClockDoesNotBlameTheClock() {
        val e = javax.net.ssl.SSLHandshakeException("Chain validation failed")
        assertEquals("Couldn't make a secure connection. Try again in a minute.", Friendly.net(e, "fallback"))
        assertTrue(!Friendly.clockIsWrong())
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
