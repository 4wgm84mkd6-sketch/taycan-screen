package com.taycanscreen.app.obd

import android.content.Context
import com.taycanscreen.app.log.AppLog
import com.taycanscreen.app.obd.IsoTp.toHex

/**
 * Read-only discovery of signals that OBDb doesn't have (turn signals, gear), owner-approved
 * 2026-11-08. Only modules already known from OBDb are asked, only with 22 xxxx (read data by
 * identifier), in the default session. Nothing is written.
 *
 * Step 1 (scan): ask each module for every identifier in its range and note which ones answer.
 * Step 2 (record): with the car in a known state (blinker left/right, gear P/R/N/D…), read all the
 * answering identifiers a few times and log the raw values. Comparing states in the uploaded log
 * shows which identifier carries which signal.
 */
object Discovery {
    data class Module(val name: String, val hdr: String, val rax: String, val flowControl: Boolean, val dids: IntRange)

    /** Modules from OBDb's Taycan ECU list. */
    val MODULES = listOf(
        Module("Instrument cluster", "714", "77E", flowControl = true, dids = 0x0100..0x2FFF),
        Module("Drive control", "7E0", "7E8", flowControl = false, dids = 0x0100..0x2FFF),
        Module("Gateway", "710", "77A", flowControl = true, dids = 0x2A00..0x2BFF),
    )

    /** Guided states, in order. */
    val STEPS = listOf(
        "In P, blinkers OFF",
        "LEFT blinker ON",
        "RIGHT blinker ON",
        "Hazard lights ON",
        "Foot on brake, shift to R",
        "Foot on brake, shift to N",
        "Foot on brake, shift to D",
        "Back to P, blinkers OFF",
    )

    const val ROUNDS_PER_STEP = 3

    sealed interface State {
        data object Idle : State
        data class Scanning(val module: String, val done: Int, val total: Int, val found: Int) : State
        data class Ready(val found: Int, val recorded: Set<String>, val scanComplete: Boolean) : State
        data class Recording(val step: String, val round: Int, val done: Int, val total: Int) : State
    }

    sealed interface Request {
        /** Scan modules not finished yet (continues an interrupted scan). */
        data object Scan : Request
        /** Forget earlier results and scan every module again. */
        data object RestartScan : Request
        data class Record(val step: String) : Request
    }

    private const val PREFS = "discovery"

    /** Identifiers that answered, per module header, saved so recording survives an app restart. */
    fun loadFound(context: Context): Map<String, List<Int>> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("found", "") ?: ""
        return raw.split(';').filter { it.contains(':') }.associate { part ->
            val (hdr, list) = part.split(':', limit = 2)
            hdr to list.split(',').filter { it.isNotBlank() }.map { it.toInt(16) }
        }
    }

    fun saveFound(context: Context, found: Map<String, List<Int>>) {
        val raw = found.entries.joinToString(";") { (hdr, dids) -> "$hdr:" + dids.joinToString(",") { "%04X".format(it) } }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("found", raw).apply()
    }

    /** Where an interrupted scan stopped: module header and the next identifier to ask. */
    fun loadPartial(context: Context): Pair<String?, Int> {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return p.getString("partial_hdr", null) to p.getInt("partial_next", 0)
    }

    fun savePartial(context: Context, hdr: String, nextDid: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("partial_hdr", hdr).putInt("partial_next", nextDid).apply()
    }

    fun clearPartial(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove("partial_hdr").remove("partial_next").apply()
    }

    fun scanComplete(context: Context): Boolean {
        val found = loadFound(context)
        return loadPartial(context).first == null && MODULES.all { found.containsKey(it.hdr) }
    }

    fun clearAll(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    fun recorded(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet("recorded", emptySet()) ?: emptySet()

    fun markRecorded(context: Context, step: String) {
        val now = recorded(context) + step
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet("recorded", now).apply()
    }

    fun clearRecorded(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("recorded").apply()
    }

    /** Selects a module on the adapter (header, receive filter, flow control). */
    fun select(c: ElmConnection, m: Module) {
        c.send("ATSH${m.hdr}")
        c.send("ATCRA${m.rax}")
        if (m.flowControl) {
            c.send("ATFCSH${m.hdr}")
            c.send("ATFCSD300000")
            c.send("ATFCSM1")
        } else {
            c.send("ATFCSM0")
        }
    }

    /** Reads one identifier; returns the data bytes (after 62 + DID) or null with a reason in [why]. */
    fun read(c: ElmConnection, m: Module, did: Int, why: StringBuilder? = null): ByteArray? {
        val r = IsoTp.parse(c.send("22%04X".format(did)), m.rax)
        if (r is IsoTp.Result.Payload) {
            val p = r.bytes
            if (p.size >= 3 && p[0] == 0x62.toByte() && (p[1].toInt() and 0xFF) == did shr 8 && (p[2].toInt() and 0xFF) == did and 0xFF) {
                return p.copyOfRange(3, p.size)
            }
            why?.append(p.toHex())
        } else if (r is IsoTp.Result.Failure) {
            why?.append(r.reason)
        }
        return null
    }

    fun logFound(m: Module, did: Int, data: ByteArray) =
        AppLog.info("disc", "found ${m.hdr} 22%04X = %s".format(did, data.toHex()))
}
