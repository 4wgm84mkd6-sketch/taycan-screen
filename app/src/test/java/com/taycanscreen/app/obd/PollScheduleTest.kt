package com.taycanscreen.app.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The order and rhythm in which the car is asked for values (no car needed). */
class PollScheduleTest {
    private val always = Taycan.POLLED.filter { it.group == null }

    @Test
    fun everyValueIsAskedForRightAfterConnecting() {
        always.forEach { assertTrue(it.id, it.due(0)) }
    }

    @Test
    fun everyValueComesRoundAgain() {
        always.forEach { sig ->
            assertTrue("${sig.id}: phase ${sig.phase} must be below pollEvery ${sig.pollEvery}", sig.phase in 0 until sig.pollEvery)
            assertTrue(sig.id, (1L..sig.pollEvery).any { sig.due(it) })
        }
    }

    @Test
    fun speedAndPowerAreReadEveryCycleAndOnlyOnce() {
        for (cycle in 1L..40L) {
            val due = always.filter { it.due(cycle) }
            assertEquals(1, due.count { it == Taycan.SPEED_DIR })
            assertEquals(1, due.count { it == Taycan.HV_VOLTAGE_1818 })
            assertEquals(1, due.count { it == Taycan.HV_CURRENT })
        }
    }

    @Test
    fun noCycleIsCrowded() {
        // Slow values are spread out. When they all fell on the same cycle, the speed froze for over a
        // second. At most 6 requests and 3 modules in any one cycle.
        for (cycle in 1L..240L) {
            val due = always.filter { it.due(cycle) }
            assertTrue("cycle $cycle asks for ${due.size}: ${due.map { it.id }}", due.size <= 6)
            assertTrue("cycle $cycle talks to ${due.map { it.command.hdr }.distinct()}", due.map { it.command.hdr }.distinct().size <= 3)
        }
    }

    @Test
    fun onlyReadRequestsAreEverBuilt() {
        // Read-only rule: every polled value is a 22 xxxx read on a module from OBDb's list.
        Taycan.POLLED.forEach { sig ->
            assertTrue(sig.id, Regex("^[0-9A-F]{4}$").matches(sig.command.did))
            assertTrue(sig.id, ElmConnection.isAllowed("22${sig.command.did}"))
        }
        assertTrue(!ElmConnection.isAllowed("2E1234AA"))
        assertTrue(!ElmConnection.isAllowed("1101"))
        assertTrue(!ElmConnection.isAllowed("3101FF00"))
    }
}
