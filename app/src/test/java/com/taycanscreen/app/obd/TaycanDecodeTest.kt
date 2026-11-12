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

    @Test fun speedDirectional() {
        check(Taycan.SPEED_DIR, "7E80562115B0000", 0.0)          // 2025
        check(Taycan.SPEED_DIR, "7E80562115B10FF", 67.98438)     // 2025
        check(Taycan.SPEED_DIR, "7E80562115BFF1D", -3.54688)     // 2025, reverse
        check(Taycan.SPEED_DIR, "7E80562115B24AC", 146.6875)     // 2024
    }

    @Test fun outsideTemperature() {
        check(Taycan.OUTSIDE, "7B005622609005F", 9.5)   // 2025
        check(Taycan.OUTSIDE, "7B00562260900B4", 18.0)  // 2025
    }

    private fun payloadData(sig: Signal, response: String): ByteArray {
        val r = IsoTp.parse(response, sig.command.rax)
        if (r !is IsoTp.Result.Payload) fail("parse failed: $r")
        return (r as IsoTp.Result.Payload).bytes.copyOfRange(3, r.bytes.size)
    }

    // OBDb 2025 recordings, tests/test_cases/2025/commands/70B.775.2218A0–A3.
    @Test fun tires() {
        check(Taycan.TIRE_FR, "775100C6218A0268EE7\r77521DC028D369036CC", 3.525)
        val fr = payloadData(Taycan.TIRE_FR, "775100C6218A0268EE7\r77521DC028D369036CC")
        assertEquals(14.0, Taycan.TIRE_FR.siblings[0].decode(fr)!!, 0.01)   // temperature
        assertEquals(3.6, Taycan.TIRE_FR.siblings[1].decode(fr)!!, 0.01)    // set point
        check(Taycan.TIRE_FL, "775100C6218A1268EE7\r7752189028B359036CC", 3.475)
        check(Taycan.TIRE_RL, "775100C6218A2268EE7\r77521AC0289369436CC", 3.425)
        check(Taycan.TIRE_RR, "775100C6218A3268EE7\r77521ED02913B9436CC", 3.625)
    }

    @Test fun batteryHealth() {
        check(Taycan.HV_SOH, "7ED056251E0390F", 91.2)   // 2025
        check(Taycan.HV_SOH, "7ED056251E03967", 100.0)  // 2025
    }

    @Test fun motors() {
        check(Taycan.EM_RPM_1, "7E8056211510742", 929.0)
        check(Taycan.EM_TORQ_1, "7E80562115301F0", 15.5)
        check(Taycan.EM_TORQ_1, "7E805621153FF10", -7.5)
        check(Taycan.EM_TORQ_2, "7E80562115400F0", 7.5)
        check(Taycan.EM_TORQ_2, "7E805621154FF00", -8.0)
        assertNull(decode(Taycan.EM_RPM_1, "7E8056211517FFF")) // "not available"
    }

    @Test fun carTripConsumption() {
        check(
            Taycan.TRIP_USE_SHORT,
            "77E1047620530001404\r77E213B0946A1896246\r77E2222200180000000\r77E230C12003A5F8000\r" +
                "77E2430000000688C7B\r77E25C041DEBA5E8808\r77E26B6030000000688\r77E27C7BC03F7FF65B6\r" +
                "77E289A71AC02C60000\r77E2900000000000000\r77E2A0000AAAAAAAAAA",
            31.68,
        )
    }

    @Test fun cellModule() {
        val sig = Taycan.CELL_MODULES[0]
        val resp = "7ED102E621850837EA4\r7ED2111000000007E90\r7ED2211000000007F08\r7ED2311000000007F08\r" +
            "7ED2411000000007EE0\r7ED2511000000007EF4\r7ED2611000000000002"
        check(sig, resp, 3.242)
        val (v, soc) = Cells.decodeModule(payloadData(sig, resp))
        assertEquals(listOf(3.242, 3.24, 3.252, 3.252, 3.248, 3.25), v.map { Math.round(it * 1000) / 1000.0 })
        assertEquals(17.0, soc[0], 0.0)
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

    @Test fun driveMode() {
        // Replies recorded in the owner's car, 2026-11-11 (gateway 710 answers as 77A).
        assertEquals("NORMAL", Taycan.driveMode(decode(Taycan.DRIVE_MODE, "77A05622A9A0009")))
        assertEquals("RANGE", Taycan.driveMode(decode(Taycan.DRIVE_MODE, "77A05622A9A0015")))
        assertEquals("SPORT", Taycan.driveMode(decode(Taycan.DRIVE_MODE, "77A05622A9A000D")))
        assertNull(Taycan.driveMode(decode(Taycan.DRIVE_MODE, "77A05622A9A0000")))
        assertNull(Taycan.driveMode(null))
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
        assertFalse(ElmConnection.isAllowed("STSLCS"))  // adapter sleep settings: read once in build 185–194, not sent any more
        assertFalse(ElmConnection.isAllowed("STSLU"))   // changing them is not allowed
        assertFalse(ElmConnection.isAllowed("STSLEEP"))
        assertFalse(ElmConnection.isAllowed("1101"))    // ECU reset
        assertFalse(ElmConnection.isAllowed("1002"))    // programming session
        assertFalse(ElmConnection.isAllowed("2E0101"))  // write data
        assertFalse(ElmConnection.isAllowed("3101"))    // routine control
        assertFalse(ElmConnection.isAllowed("14FF"))    // clear codes
        assertFalse(ElmConnection.isAllowed("0100"))
        assertFalse(ElmConnection.isAllowed("2F0101"))  // actuator control
    }
}
