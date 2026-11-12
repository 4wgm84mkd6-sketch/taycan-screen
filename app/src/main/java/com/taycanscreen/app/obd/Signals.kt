package com.taycanscreen.app.obd

/**
 * Taycan signal definitions, copied exactly from OBDb (reference/obdb/porsche-taycan-default.json,
 * https://github.com/OBDb/Porsche-Taycan, CC BY-SA 4.0). Do not edit formulas by hand; if one
 * looks wrong, compare with Car Scanner and record the finding in docs/HANDOFF.md first.
 */

/** One UDS ReadDataByIdentifier request: header [hdr], reply from [rax], DID [did]. */
data class ObdCommand(
    val hdr: String,
    val rax: String,
    val did: String,
    /** OBDb `din: "03"`: module must be in the extended diagnostic session (10 03) first. */
    val extendedSession: Boolean,
    /** OBDb `fcm1` / `fc=1`: multi-frame replies need flow control sent to [hdr] (ATFCSH/ATFCSD/ATFCSM1). */
    val flowControl: Boolean = false,
)

/** OBDb `fmt`: [len] bits at bit [bix] of the data after the DID, value = raw * mul / div + add. */
data class Signal(
    val id: String,
    val name: String,
    val command: ObdCommand,
    val bix: Int = 0,
    val len: Int,
    val mul: Double = 1.0,
    val div: Double = 1.0,
    val add: Double = 0.0,
    val signed: Boolean = false,
    val nullMin: Double? = null,
    val nullMax: Double? = null,
    val unit: String,
    /** Poll on every Nth cycle (slow-changing values). */
    val pollEvery: Int = 1,
    /**
     * Which of those N cycles (0 until [pollEvery]). Slow values are spread over different cycles so no
     * cycle is much longer than another: when they all landed on the same one, speed froze for over a
     * second every 20th cycle.
     */
    val phase: Int = 0,
    /** Only polled while a screen asks for this group (e.g. "power", "cells"); null = always. */
    val group: String? = null,
    /** Spread a set of signals over cycles: polled when cycle % [slots] == [slot]. */
    val slot: Int = 0,
    val slots: Int = 1,
    /** Other OBDb signals decoded from the same reply (one request, several values). */
    val siblings: List<Signal> = emptyList(),
    /** Hide the value sooner than the default (speed and gear must never linger). */
    val staleAfterMs: Long? = null,
) {
    fun due(cycle: Long): Boolean =
        // Everything once right after connecting (cycle 0), so the screen fills at once.
        (cycle == 0L || cycle % pollEvery == phase.toLong()) && cycle % slots == slot.toLong()

    /** A reading older than this is shown as "—". */
    val staleMs: Long get() = staleAfterMs ?: (5_000L * pollEvery)

    /** Decodes [data] (bytes after 62 + DID). Null if too short or a "no data" value. */
    fun decode(data: ByteArray): Double? {
        if (bix + len > data.size * 8) return null
        var raw = 0L
        for (bit in bix until bix + len) {
            val byte = data[bit / 8].toInt() and 0xFF
            val b = (byte shr (7 - bit % 8)) and 1
            raw = (raw shl 1) or b.toLong()
        }
        if (signed && (raw shr (len - 1)) and 1L == 1L) raw -= (1L shl len)
        val value = raw * mul / div + add
        if (nullMin != null && value <= nullMin) return null
        if (nullMax != null && value >= nullMax) return null
        return value
    }
}

object Taycan {
    private val ENG_SOC_DISP = ObdCommand("7E0", "7E8", "08D2", extendedSession = false)
    private val ENG_VSS = ObdCommand("7E0", "7E8", "F40D", extendedSession = false)
    private val BMS_VOLT = ObdCommand("7E5", "7ED", "1801", extendedSession = false)
    private val BMS_VOLT_1818 = ObdCommand("7E5", "7ED", "1818", extendedSession = false)
    /** Found by the owner-approved discovery test (2026-11-08), not in OBDb: gear selector. */
    private val ENG_GEAR = ObdCommand("7E0", "7E8", "0884", extendedSession = false)
    private val ENG_AAT = ObdCommand("7E0", "7E8", "1004", extendedSession = false)
    private val ENG_VSS_DIR = ObdCommand("7E0", "7E8", "115B", extendedSession = false)
    private val HVAC_OUTSIDE = ObdCommand("746", "7B0", "2609", extendedSession = false, flowControl = true)
    private val GW_RANGE = ObdCommand("710", "77A", "2AB6", extendedSession = false, flowControl = true)
    /**
     * Found by the owner-approved drive-mode test (2026-11-12), not in OBDb; owner approved reading it
     * the same day. A single-frame reply (62 2A 9A 00 xx).
     */
    private val GW_MODE = ObdCommand("710", "77A", "2A9A", extendedSession = false)
    private val GW_12V_CAP = ObdCommand("710", "77A", "2A06", extendedSession = false, flowControl = true)
    private val GW_12V_VOLT = ObdCommand("710", "77A", "2A07", extendedSession = false, flowControl = true)
    private val GW_12V_SOC = ObdCommand("710", "77A", "2A0C", extendedSession = false)
    private val GW_12V_R = ObdCommand("710", "77A", "2A0E", extendedSession = false)
    private val GW_12V_AH = ObdCommand("710", "77A", "2A0F", extendedSession = false)
    private val BMS_SOH = ObdCommand("7E5", "7ED", "51E0", extendedSession = false)
    private val CL_TRIP_USE = ObdCommand("714", "77E", "0530", extendedSession = false, flowControl = true)
    private val ENG_EM_RPM_1 = ObdCommand("7E0", "7E8", "1151", extendedSession = false)
    private val ENG_EM_TORQ_1 = ObdCommand("7E0", "7E8", "1153", extendedSession = false)
    private val ENG_EM_TORQ_2 = ObdCommand("7E0", "7E8", "1154", extendedSession = false)
    private val BMS_CURR = ObdCommand("7E5", "7ED", "1802", extendedSession = true)
    private val BMS_T_MAX = ObdCommand("7E5", "7ED", "1E0E", extendedSession = true)
    private val BMS_T_MIN = ObdCommand("7E5", "7ED", "1E0F", extendedSession = true)

    val SOC_DISPLAY = Signal("TAYCAN_HVBAT_SOC_DISP", "HV battery charge (displayed)", ENG_SOC_DISP,
        len = 16, div = 100.0, unit = "%", pollEvery = 5, phase = 3)
    val SPEED = Signal("TAYCAN_VSS", "Vehicle speed", ENG_VSS,
        len = 8, nullMax = 255.0, unit = "km/h", pollEvery = 4, phase = 1, staleAfterMs = 3_000L)
    /**
     * OBDb "Vehicle speed (directional)": 1/64 km/h resolution (F40D is whole km/h only), negative in
     * reverse. Shown on the Drive screen and polled twice per cycle so the number keeps up.
     */
    val SPEED_DIR = Signal("TAYCAN_VSS_DIR", "Vehicle speed (directional)", ENG_VSS_DIR,
        len = 16, div = 64.0, signed = true, unit = "km/h", staleAfterMs = 3_000L)

    val HV_VOLTAGE = Signal("TAYCAN_HVBAT_VOLT", "HV battery voltage", BMS_VOLT,
        len = 16, div = 10.0, unit = "V", pollEvery = 20, phase = 7)
    /** OBDb's 2025 recordings show 1801 answering 0 while 1818 gives the real pack voltage. */
    val HV_VOLTAGE_1818 = Signal("TAYCAN_HVBAT_V_1818", "HV battery voltage, measurement 4", BMS_VOLT_1818,
        len = 16, div = 10.0, unit = "V")
    val HV_CURRENT = Signal("TAYCAN_BMS_I", "HV battery current", BMS_CURR,
        len = 16, mul = 2.56, add = -1500.0, nullMin = -1500.0, unit = "A")
    val PACK_T_MAX = Signal("TAYCAN_BMS_T_MAX", "HV battery temperature, maximum", BMS_T_MAX,
        len = 8, add = -100.0, unit = "°C", pollEvery = 10, phase = 1)
    val PACK_T_MIN = Signal("TAYCAN_BMS_T_MIN", "HV battery temperature, minimum", BMS_T_MIN,
        len = 8, add = -100.0, unit = "°C", pollEvery = 10, phase = 6)

    /**
     * Gear selector position, from discovery (not OBDb): 5 = P, 6 = R, 7 = N, 8 = D. Seen steady in each
     * gear and changing exactly at each shift; 7E0 22 0898 mirrors it as 8/7/6/5.
     */
    val GEAR = Signal("DISC_GEAR", "Gear selector (discovered)", ENG_GEAR, len = 8, unit = "", pollEvery = 2, staleAfterMs = 4_000L)

    fun gearLetter(raw: Double?): String? = when (raw?.toInt()) {
        5 -> "P"
        6 -> "R"
        7 -> "N"
        8 -> "D"
        else -> null
    }

    /**
     * Drive mode, from discovery (not OBDb): 9 = Normal, 21 = Range, 13 = Sport and also Sport Plus (the
     * car gives the same value for both). Steady in each mode over two passes, back to 9 on returning to
     * Normal, and 9 in every gear in the 2026-11-08 recordings. What the value really is isn't known, so
     * the screen only shows a label until it has held up over several drives. Read about every 3 s, on
     * a cycle with room for it (see PollScheduleTest).
     */
    val DRIVE_MODE = Signal("DISC_DRIVE_MODE", "Drive mode (discovered)", GW_MODE,
        len = 16, unit = "", pollEvery = 8, phase = 1, staleAfterMs = 10_000L)

    fun driveMode(raw: Double?): String? = when (raw?.toInt()) {
        9 -> "NORMAL"
        21 -> "RANGE"
        13 -> "SPORT"
        else -> null
    }

    /**
     * Outside temperature from the climate module (OBDb TAYCAN_HVAC_OUTSIDE_T). Shown on the Drive
     * screen. 7E0 1004 (AMBIENT below) answers 0 on every Taycan in OBDb's recordings and on ours.
     */
    val OUTSIDE = Signal("TAYCAN_HVAC_OUTSIDE_T", "Outside temperature (climate)", HVAC_OUTSIDE,
        len = 16, div = 10.0, signed = true, unit = "°C", pollEvery = 20, phase = 15)
    val AMBIENT = Signal("TAYCAN_AAT", "Ambient temperature", ENG_AAT,
        len = 16, div = 128.0, signed = true, unit = "°C", pollEvery = 10)
    /** OBDb unit: miles (range remaining as the car displays it). */
    val RANGE = Signal("TAYCAN_RANGE", "Range remaining", GW_RANGE,
        len = 16, unit = "mi", pollEvery = 10, phase = 2)

    // 12 V battery (gateway 710, OBDb "Battery" path). OBDb's 12 V current (2A09) is left out: its
    // 2025 recordings all decode to 0 (OBDb clamps negatives), so it can't show charge/discharge.
    val LV_SOC = Signal("TAYCAN_SSB_SOC", "12V battery state of charge", GW_12V_SOC,
        len = 8, unit = "%", pollEvery = 20, phase = 4)
    val LV_VOLTAGE = Signal("TAYCAN_BAT_V_2A07", "Battery voltage (12 V)", GW_12V_VOLT,
        len = 16, div = 1000.0, add = 4.0, unit = "V", pollEvery = 10, phase = 8)
    val LV_CHARGE = Signal("TAYCAN_SSB_AH", "12V battery charge", GW_12V_AH,
        len = 8, unit = "Ah", pollEvery = 20, phase = 12)
    val LV_CAPACITY = Signal("TAYCAN_12V_BAT_CAP", "12V battery capacity", GW_12V_CAP,
        bix = 16, len = 8, unit = "Ah", pollEvery = 40, phase = 14)
    val LV_RESISTANCE = Signal("TAYCAN_SSB_R", "12V battery resistance", GW_12V_R,
        len = 8, mul = 2.0, div = 10.0, unit = "mΩ", pollEvery = 40, phase = 34)

    // Tires (OBDb 70B/775, fc): pressure, temperature and set point from one reply per wheel.
    private fun tire(corner: String, did: String, phase: Int): Signal {
        val cmd = ObdCommand("70B", "775", did, extendedSession = false, flowControl = true)
        return Signal(
            "TAYCAN_TP_$corner", "Tire pressure $corner", cmd, bix = 40, len = 8, div = 40.0, nullMax = 6.37, unit = "bar", pollEvery = 40, phase = phase,
            siblings = listOf(
                Signal("TAYCAN_TT_$corner", "Tire temperature $corner", cmd, bix = 48, len = 8, add = -40.0, nullMax = 127.0, unit = "°C", pollEvery = 40),
                Signal("TAYCAN_TP_SET_$corner", "Tire pressure set point $corner", cmd, bix = 56, len = 8, div = 40.0, unit = "bar", pollEvery = 40),
            ),
        )
    }
    val TIRE_FL = tire("FL", "18A1", 11)
    val TIRE_FR = tire("FR", "18A0", 21)
    val TIRE_RL = tire("RL", "18A2", 31)
    val TIRE_RR = tire("RR", "18A3", 39)
    val TIRES = listOf(TIRE_FL, TIRE_FR, TIRE_RL, TIRE_RR)

    /** HV battery state of health, OBDb's 2025+ formula (TAYCAN_HVBAT_SOH_J12; 2025 recordings 91.2 / 100 %). */
    val HV_SOH = Signal("TAYCAN_HVBAT_SOH_J12", "HV battery health", BMS_SOH,
        len = 16, div = 10.0, add = -1369.5, nullMin = 0.0, unit = "%", pollEvery = 120, phase = 17)

    /** The car's own short-trip consumption (cluster, kWh/100 mi). */
    val TRIP_USE_SHORT = Signal("TAYCAN_TRIP_USE_SHORT", "Trip consumption (short)", CL_TRIP_USE,
        bix = 238, len = 18, div = 1000.0, signed = true, unit = "kWh/100mi", pollEvery = 40, phase = 26)

    // Motors (only while the Power page is open). OBDb: motor 2's rpm reads "not available" (7FFF) on
    // 2024–2025 cars, so per-motor power can't be computed; torque is available for both.
    val EM_TORQ_1 = Signal("TAYCAN_EM_TORQ_1", "Electric motor 1 torque", ENG_EM_TORQ_1,
        len = 16, div = 32.0, signed = true, unit = "Nm", group = "power")
    val EM_TORQ_2 = Signal("TAYCAN_EM_TORQ_2", "Electric motor 2 torque", ENG_EM_TORQ_2,
        len = 16, div = 32.0, signed = true, unit = "Nm", group = "power")
    val EM_RPM_1 = Signal("TAYCAN_EM_RPM_1", "Electric motor 1 rpm", ENG_EM_RPM_1,
        len = 16, div = 2.0, signed = true, nullMax = 16383.0, unit = "rpm", group = "power")

    /**
     * HV cells (OBDb 7E5 1850–1870, one reply per module: id, then 6 × [voltage /10000, SoC]). Only while
     * the Battery page is open, 3 modules per cycle (11 cycles for all 33). The value is cell 1's voltage;
     * [Cells] keeps every cell.
     */
    val CELL_MODULES: List<Signal> = (0 until 33).map { i ->
        val did = "%04X".format(0x1850 + i)
        Signal("CELLS_M%02d".format(i + 1), "HV battery module ${i + 1} cells", ObdCommand("7E5", "7ED", did, extendedSession = false),
            bix = 8, len = 16, div = 10000.0, unit = "V", group = "cells", slot = i % 11, slots = 11)
    }

    /**
     * Polled in this order, grouped by module so the adapter is re-pointed as little as possible.
     * A normal cycle is short: speed, gear (every other cycle), pack voltage and current (for power),
     * and at most one or two of the slow values (their [Signal.phase] spreads them out). Speed is read
     * once per cycle so its readings are evenly spaced, 3 to 4 a second instead of 2 in uneven pairs,
     * without asking the car for anything new.
     */
    val POLLED = listOf(
        SPEED_DIR, GEAR, SOC_DISPLAY, SPEED, EM_TORQ_1, EM_TORQ_2, EM_RPM_1,
        HV_VOLTAGE_1818, HV_CURRENT, HV_VOLTAGE, PACK_T_MAX, PACK_T_MIN, HV_SOH,
    ) + CELL_MODULES + listOf(
        DRIVE_MODE, RANGE, LV_VOLTAGE, LV_SOC, LV_CHARGE, LV_CAPACITY, LV_RESISTANCE,
        OUTSIDE, TRIP_USE_SHORT,
    ) + TIRES

    /** Speed for display (km/h, ≥ 0): directional speed if fresh, else F40D; null if neither is. */
    fun speedKmh(readings: Map<String, Reading>, now: Long = com.taycanscreen.app.util.Mono.ms()): Double? {
        for (sig in listOf(SPEED_DIR, SPEED)) {
            val r = readings[sig.id] ?: continue
            val v = r.value ?: continue
            if (now - r.at <= sig.staleMs) return kotlin.math.abs(v)
        }
        return null
    }
}
