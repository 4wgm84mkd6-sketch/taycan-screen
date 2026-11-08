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
) {
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
    private val BMS_CURR = ObdCommand("7E5", "7ED", "1802", extendedSession = true)
    private val BMS_T_MAX = ObdCommand("7E5", "7ED", "1E0E", extendedSession = true)
    private val BMS_T_MIN = ObdCommand("7E5", "7ED", "1E0F", extendedSession = true)

    val SOC_DISPLAY = Signal("TAYCAN_HVBAT_SOC_DISP", "HV battery charge (displayed)", ENG_SOC_DISP,
        len = 16, div = 100.0, unit = "%")
    val SPEED = Signal("TAYCAN_VSS", "Vehicle speed", ENG_VSS,
        len = 8, nullMax = 255.0, unit = "km/h")
    val HV_VOLTAGE = Signal("TAYCAN_HVBAT_VOLT", "HV battery voltage", BMS_VOLT,
        len = 16, div = 10.0, unit = "V")
    /** OBDb's 2025 recordings show 1801 answering 0 while 1818 gives the real pack voltage. */
    val HV_VOLTAGE_1818 = Signal("TAYCAN_HVBAT_V_1818", "HV battery voltage, measurement 4", BMS_VOLT_1818,
        len = 16, div = 10.0, unit = "V")
    val HV_CURRENT = Signal("TAYCAN_BMS_I", "HV battery current", BMS_CURR,
        len = 16, mul = 2.56, add = -1500.0, nullMin = -1500.0, unit = "A")
    val PACK_T_MAX = Signal("TAYCAN_BMS_T_MAX", "HV battery temperature, maximum", BMS_T_MAX,
        len = 8, add = -100.0, unit = "°C")
    val PACK_T_MIN = Signal("TAYCAN_BMS_T_MIN", "HV battery temperature, minimum", BMS_T_MIN,
        len = 8, add = -100.0, unit = "°C")

    /** Polled in this order; grouped by module so the header changes as little as possible. */
    val POLLED = listOf(SOC_DISPLAY, SPEED, HV_VOLTAGE, HV_VOLTAGE_1818, HV_CURRENT, PACK_T_MAX, PACK_T_MIN)
}
