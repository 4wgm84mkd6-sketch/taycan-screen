package com.taycanscreen.app.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Real Taycan responses and expected values from OBDb's recorded test cases
 * (https://github.com/OBDb/Porsche-Taycan, tests/test_cases/<year>/commands).
 */
class TaycanDecodeTest {

    private fun decode(sig: Signal, response: String): Double? {
        val r = IsoTp.parse(response, sig.command.rax)
        if (r !is IsoTp.Result.Payload) fail("parse failed: $r")
        val p = (r as IsoTp.Result.Payload).bytes
        assertEquals(0x62, p[0].toInt() and 0xFF)
        return sig.decode(p.copyOfRange(3, p.size))
    }

    private fun check(sig: Signal, response: String, expected: Double) {
        val v = decode(sig, response)
        if (v == null) {
            fail("${sig.id}: no value from $response")
            return
        }
        assertEquals("${sig.id} from $response", expected, v, 0.006)
    }

    @Test fun socDisplay() {
        check(Taycan.SOC_DISPLAY, "7E8076208D2080C080C", 20.6)      // 2020
        check(Taycan.SOC_DISPLAY, "7E8076208D214FF14FF", 53.75)     // 2023
        check(Taycan.SOC_DISPLAY, "7E8076208D218C418C4", 63.4)      // 2025
        check(Taycan.SOC_DISPLAY, "7E8076208D222932293", 88.51)     // 2025
    }

    // 12 V battery, OBDb 2025 recordings (tests/test_cases/2025/commands/710.77A.222A0x).
    @Test fun lowVoltageBattery() {
        check(Taycan.LV_SOC, "77A04622A0C5C", 92.0)
        check(Taycan.LV_SOC, "77A04622A0C61", 97.0)
        check(Taycan.LV_CHARGE, "77A04622A0F1C", 28.0)
        check(Taycan.LV_CAPACITY, "77A07622A0607002806", 40.0)
        check(Taycan.LV_RESISTANCE, "77A04622A0E0E", 2.8)
        check(Taycan.LV_VOLTAGE, "77A05622A07247C", 13.34)
        check(Taycan.LV_VOLTAGE, "77A05622A0724A1", 13.377)
    }

    @Test fun speed() {
        check(Taycan.SPEED, "7E80462F40D00", 0.0)
        check(Taycan.SPEED, "7E80462F40DB9", 185.0)                 // 2025
    }

    @Test fun hvVoltage() {
        check(Taycan.HV_VOLTAGE, "7ED056218011D04", 742.8)          // 2023
        check(Taycan.HV_VOLTAGE, "7ED056218010000", 0.0)            // 2025 answers 0 here
        check(Taycan.HV_VOLTAGE_1818, "7ED056218181DF9", 767.3)     // 2025
    }

    @Test fun hvCurrent() {
        check(Taycan.HV_CURRENT, "7ED066218020247E6", -7.52)        // 2023
        check(Taycan.HV_CURRENT, "7ED06621802015BB1", -611.68)      // 2025
        check(Taycan.HV_CURRENT, "7ED0662180202001D", -189.28)      // 2025
    }

    @Test fun packTemps() {
        check(Taycan.PACK_T_MAX, "7ED05621E0E6E09", 10.0)           // 2025
        check(Taycan.PACK_T_MAX, "7ED05621E0E7C13", 24.0)           // 2023
        check(Taycan.PACK_T_MIN, "7ED05621E0F6D02", 9.0)            // 2025
        check(Taycan.PACK_T_MIN, "7ED05621E0F7A0B", 22.0)           // 2023
    }

    @Test fun rangeMultiFrame() {
        check(Taycan.RANGE, "77A100A622AB6002300\r77A212307FD00AAAAAA", 35.0)   // 2025
        check(Taycan.RANGE, "77A100A622AB6002F00\r77A212F07FD00AAAAAA", 47.0)   // 2025
    }

    @Test fun gear() {
        // Replies seen during the 2026-11-08 discovery test.
        assertEquals("P", Taycan.gearLetter(decode(Taycan.GEAR, "7E804620884 05".replace(" ", ""))))
        assertEquals("R", Taycan.gearLetter(decode(Taycan.GEAR, "7E80462088406")))
        assertEquals("N", Taycan.gearLetter(decode(Taycan.GEAR, "7E80462088407")))
        assertEquals("D", Taycan.gearLetter(decode(Taycan.GEAR, "7E80462088408")))
        assertNull(Taycan.gearLetter(3.0))
    }

    @Test fun ambient() {
        check(Taycan.AMBIENT, "7E8056210040000", 0.0)               // 2025
    }

    @Test fun elmErrorsAndOtherModules() {
        assertTrue(IsoTp.parse("NO DATA", "7ED") is IsoTp.Result.Failure)
        assertTrue(IsoTp.parse("CAN ERROR", "7ED") is IsoTp.Result.Failure)
        assertTrue(IsoTp.parse("7E8076208D2080C080C", "7ED") is IsoTp.Result.Failure)
        // Response-pending frame followed by the real answer.
        val r = IsoTp.parse("7ED037F2278\r7ED056218011D04", "7ED")
        assertTrue(r is IsoTp.Result.Payload)
    }

    @Test fun multiFrame() {
        val r = IsoTp.parse("7ED100962180102030405\r7ED2106070809", "7ED")
        assertTrue(r is IsoTp.Result.Payload)
        assertEquals(9, (r as IsoTp.Result.Payload).bytes.size)
    }

    @Test fun nullValues() {
        assertNull(Taycan.SPEED.decode(byteArrayOf(0xFF.toByte())))
        assertNull(Taycan.HV_CURRENT.decode(byteArrayOf(0, 0)))
    }

    @Test fun allowlistIsReadOnly() {
        assertTrue(ElmConnection.isAllowed("ATZ"))
        assertTrue(ElmConnection.isAllowed("ATSH7E5"))
        assertTrue(ElmConnection.isAllowed("2208D2"))
        assertTrue(ElmConnection.isAllowed("1003"))
        assertTrue(ElmConnection.isAllowed("ATFCSD300000"))
        assertFalse(ElmConnection.isAllowed("1101"))    // ECU reset
        assertFalse(ElmConnection.isAllowed("1002"))    // programming session
        assertFalse(ElmConnection.isAllowed("2E0101"))  // write data
        assertFalse(ElmConnection.isAllowed("3101"))    // routine control
        assertFalse(ElmConnection.isAllowed("14FF"))    // clear codes
        assertFalse(ElmConnection.isAllowed("0100"))
        assertFalse(ElmConnection.isAllowed("2F0101"))  // actuator control
    }
}
