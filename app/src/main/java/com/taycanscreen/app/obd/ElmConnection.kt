package com.taycanscreen.app.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import com.taycanscreen.app.log.AppLog
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Serial link to the OBDLink MX+ (ELM327/STN command set) over Bluetooth Classic SPP.
 * Every byte sent and received is logged with a timestamp (AppLog TX/RX).
 *
 * SAFETY: [send] refuses anything that is not on the read-only allowlist below. AT and STI commands
 * configure the adapter only; the only messages that reach the car are 22 xxxx (read data by
 * identifier) and 10 03 (extended diagnostic session, owner-approved). Nothing that writes,
 * resets, codes or actuates can be sent.
 */
class ElmConnection private constructor(
    private val socket: BluetoothSocket,
    private val input: InputStream,
    private val output: OutputStream,
) {
    companion object {
        private const val TAG = "obd"
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private val ALLOWED = listOf(
            Regex("^AT[A-Z0-9]*$"),      // adapter configuration
            Regex("^STI$"),              // STN firmware id
            Regex("^STSLCS$"),           // adapter's own sleep settings, printed (adapter only, changes nothing)
            Regex("^1003$"),             // extended diagnostic session (read access)
            Regex("^22[0-9A-F]{4}$"),    // read data by identifier
        )

        fun isAllowed(cmd: String): Boolean = ALLOWED.any { it.matches(cmd) }

        /**
         * Connects to the adapter with ONE method: RFCOMM channel 1 directly ([channel1]) or the
         * standard service lookup. The caller alternates between them after a failure; trying both in
         * one go made every failed attempt cost 10–15 s. A socket that fails to connect is closed
         * (they used to be left open, two per failed attempt).
         */
        @SuppressLint("MissingPermission")
        fun open(device: BluetoothDevice, channel1: Boolean): ElmConnection {
            AppLog.info(TAG, "Connecting to ${device.name} (${device.address}) via ${if (channel1) "channel 1" else "the standard lookup"}")
            val socket: BluetoothSocket = if (channel1) {
                // Common route for SPP adapters whose SDP record isn't found.
                val m = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                m.invoke(device, 1) as BluetoothSocket
            } else {
                device.createRfcommSocketToServiceRecord(SPP_UUID)
            }
            try {
                socket.connect()
            } catch (e: Exception) {
                runCatching { socket.close() }
                throw e
            }
            AppLog.info(TAG, "Bluetooth connected")
            return ElmConnection(socket, socket.inputStream, socket.outputStream)
        }
    }

    /** Sends one command and returns everything up to the '>' prompt. */
    fun send(cmd: String, timeoutMs: Long = 2500): String {
        val c = cmd.uppercase().replace(" ", "")
        if (!isAllowed(c)) {
            AppLog.error(TAG, "BLOCKED non-allowlisted command '$c'")
            throw IllegalArgumentException("Blocked command $c")
        }
        // Drop anything left over from an earlier exchange.
        while (input.available() > 0) {
            val stale = ByteArray(input.available())
            val n = input.read(stale)
            if (n > 0) AppLog.rx(TAG, "(stale) " + String(stale, 0, n, Charsets.ISO_8859_1))
        }
        AppLog.tx(TAG, "$c\r")
        output.write("$c\r".toByteArray(Charsets.ISO_8859_1))
        output.flush()
        val reply = try {
            readUntilPrompt(timeoutMs)
        } catch (slow: PromptTimeout) {
            // The adapter is still waiting on the car (e.g. a module answered 7F 22 78 "response
            // pending" and never finished). That's not a dead link: interrupt it and carry on with
            // whatever arrived. A CR stops the current request; if the adapter was actually idle it
            // repeats the last command, which already passed the allowlist above.
            AppLog.warn(TAG, "No prompt after $timeoutMs ms; interrupting the adapter")
            AppLog.tx(TAG, "\r")
            output.write("\r".toByteArray(Charsets.ISO_8859_1))
            output.flush()
            val rest = readUntilPrompt(2_000) // still nothing: the link really is gone
            AppLog.rx(TAG, rest + ">")
            slow.partial + "\r" + rest
        }
        AppLog.rx(TAG, reply + ">")
        return reply
    }

    /** No '>' prompt in time; [partial] is what arrived before giving up. */
    class PromptTimeout(val partial: String, timeoutMs: Long) : IOException("No prompt from adapter within $timeoutMs ms")

    private fun readUntilPrompt(timeoutMs: Long): String {
        val sb = StringBuilder()
        val buf = ByteArray(256)
        // Monotonic clock: the box's date clock jumps when it syncs, which used to end this wait at once.
        val deadline = com.taycanscreen.app.util.Mono.ms() + timeoutMs
        while (com.taycanscreen.app.util.Mono.ms() < deadline) {
            val avail = input.available()
            if (avail > 0) {
                val n = input.read(buf, 0, minOf(avail, buf.size))
                if (n < 0) throw IOException("Adapter closed the connection")
                for (i in 0 until n) {
                    val ch = (buf[i].toInt() and 0xFF).toChar()
                    if (ch == '>') return sb.toString()
                    sb.append(ch)
                }
            } else {
                Thread.sleep(5)
            }
        }
        AppLog.rx(TAG, "(timeout after ${timeoutMs} ms) $sb")
        throw PromptTimeout(sb.toString(), timeoutMs)
    }

    fun close() {
        try {
            socket.close()
        } catch (_: IOException) {
        }
    }
}
