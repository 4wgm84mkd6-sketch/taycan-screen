package com.taycanscreen.app.obd

import com.taycanscreen.app.log.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * HV battery cells from OBDb's per-module replies (7E5 22 1850–1870): byte 0 module id, then for each of
 * 6 cells: voltage (16 bit, /10000 V) and state of charge (8 bit, %), 7 bytes per cell.
 */
object Cells {
    const val MODULES = 33
    const val PER_MODULE = 6

    data class Summary(
        val modulesSeen: Int,
        val vMin: Double?,
        val vMax: Double?,
        val vMinCell: String?,
        val vMaxCell: String?,
        val socMin: Double?,
        val socMax: Double?,
    ) {
        val spreadMv: Double? get() = if (vMin != null && vMax != null) (vMax - vMin) * 1000 else null
    }

    private val volts = Array(MODULES) { DoubleArray(PER_MODULE) { Double.NaN } }
    private val socs = Array(MODULES) { DoubleArray(PER_MODULE) { Double.NaN } }
    private val seen = BooleanArray(MODULES)
    private val _summary = MutableStateFlow(Summary(0, null, null, null, null, null, null))
    val summary: StateFlow<Summary> = _summary.asStateFlow()

    /** Decodes one module reply ([data] = bytes after 62 + DID). Returns the 6 voltages (NaN = n/a). */
    fun decodeModule(data: ByteArray): Pair<DoubleArray, DoubleArray> {
        val v = DoubleArray(PER_MODULE) { Double.NaN }
        val soc = DoubleArray(PER_MODULE) { Double.NaN }
        for (j in 0 until PER_MODULE) {
            val o = 1 + 7 * j
            if (o + 2 >= data.size) break
            val raw = ((data[o].toInt() and 0xFF) shl 8) or (data[o + 1].toInt() and 0xFF)
            val volt = raw / 10000.0
            if (volt in 2.0..4.5) v[j] = volt
            val s = (data[o + 2].toInt() and 0xFF).toDouble()
            if (s <= 100.0) soc[j] = s
        }
        return v to soc
    }

    fun onModule(id: String, data: ByteArray) {
        val m = id.removePrefix("CELLS_M").toIntOrNull()?.minus(1) ?: return
        if (m !in 0 until MODULES) return
        val (v, s) = decodeModule(data)
        volts[m] = v
        socs[m] = s
        val first = !seen[m]
        seen[m] = true
        publish()
        if (first && seen.all { it }) {
            val sm = _summary.value
            AppLog.info("cells", "All 33 modules: %.3f–%.3f V (spread %.0f mV, low %s, high %s), SoC %s–%s %%".format(
                sm.vMin ?: 0.0, sm.vMax ?: 0.0, sm.spreadMv ?: 0.0, sm.vMinCell, sm.vMaxCell, sm.socMin, sm.socMax))
        }
    }

    private fun publish() {
        var vMin = Double.NaN; var vMax = Double.NaN; var minAt: String? = null; var maxAt: String? = null
        var sMin = Double.NaN; var sMax = Double.NaN
        for (m in 0 until MODULES) for (j in 0 until PER_MODULE) {
            val v = volts[m][j]
            if (!v.isNaN()) {
                if (vMin.isNaN() || v < vMin) { vMin = v; minAt = "M${m + 1}·C${j + 1}" }
                if (vMax.isNaN() || v > vMax) { vMax = v; maxAt = "M${m + 1}·C${j + 1}" }
            }
            val s = socs[m][j]
            if (!s.isNaN()) {
                if (sMin.isNaN() || s < sMin) sMin = s
                if (sMax.isNaN() || s > sMax) sMax = s
            }
        }
        _summary.value = Summary(
            seen.count { it },
            vMin.takeUnless { it.isNaN() }, vMax.takeUnless { it.isNaN() }, minAt, maxAt,
            sMin.takeUnless { it.isNaN() }, sMax.takeUnless { it.isNaN() },
        )
    }
}
