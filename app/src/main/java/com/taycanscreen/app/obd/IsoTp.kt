package com.taycanscreen.app.obd

/**
 * Turns ELM327 output (headers on, spaces off, CAN auto-formatting on) into the UDS payload sent by
 * one module. Example single frame from the BMS: "7ED066218020247E6" = id 7ED, PCI 06 (6 bytes),
 * payload 62 18 02 02 47 E6.
 */
object IsoTp {
    sealed interface Result {
        data class Payload(val bytes: ByteArray) : Result
        data class Failure(val reason: String) : Result
    }

    private val ELM_ERRORS = listOf(
        "NO DATA", "CAN ERROR", "BUFFER FULL", "BUS INIT", "BUS BUSY", "DATA ERROR", "FB ERROR",
        "UNABLE TO CONNECT", "STOPPED", "ERROR", "?",
    )

    fun parse(response: String, rxId: String): Result {
        val lines = response.split('\r', '\n').map { it.trim().replace(" ", "") }.filter { it.isNotEmpty() }
        ELM_ERRORS.firstOrNull { err -> lines.any { it.uppercase().startsWith(err.replace(" ", "")) } }
            ?.let { return Result.Failure(it) }
        val frames = lines.filter { it.uppercase().startsWith(rxId.uppercase()) }
            .map { it.substring(rxId.length) }
            .mapNotNull { hexToBytes(it) }
        if (frames.isEmpty()) return Result.Failure("no reply from $rxId")

        // Ignore "response pending" (7F xx 78) frames; take the first real answer.
        val out = ArrayList<Byte>()
        var expected = -1
        for (f in frames) {
            if (f.isEmpty()) continue
            val pci = (f[0].toInt() and 0xF0) shr 4
            when (pci) {
                0 -> {
                    val n = f[0].toInt() and 0x0F
                    if (n > f.size - 1) return Result.Failure("short single frame")
                    val p = f.copyOfRange(1, 1 + n)
                    if (p.size >= 3 && p[0] == 0x7F.toByte() && p[2] == 0x78.toByte()) continue
                    return Result.Payload(p)
                }
                1 -> {
                    if (f.size < 2) return Result.Failure("short first frame")
                    expected = ((f[0].toInt() and 0x0F) shl 8) or (f[1].toInt() and 0xFF)
                    out.clear()
                    for (i in 2 until f.size) out.add(f[i])
                }
                2 -> for (i in 1 until f.size) out.add(f[i])
            }
            if (expected >= 0 && out.size >= expected) {
                return Result.Payload(out.subList(0, expected).toByteArray())
            }
        }
        return Result.Failure(if (expected >= 0) "incomplete multi-frame reply" else "only 'response pending'")
    }

    fun hexToBytes(hex: String): ByteArray? {
        if (hex.length % 2 != 0 || hex.any { it.digitToIntOrNull(16) == null }) return null
        return ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    fun ByteArray.toHex(): String = joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
}
