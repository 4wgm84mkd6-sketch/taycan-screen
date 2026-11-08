package com.taycanscreen.app.obd

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.taycanscreen.app.log.AppLog
import com.taycanscreen.app.obd.IsoTp.toHex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Latest decoded value of one signal. [value] null = the car answered but with no usable data. */
data class Reading(val value: Double?, val raw: String, val at: Long)

enum class LinkState { Stopped, NeedsPermission, BluetoothOff, ChooseAdapter, Connecting, Initializing, Live, Retrying }

data class ObdStatus(
    val state: LinkState = LinkState.Stopped,
    val detail: String = "",
    val adapterName: String? = null,
    val pairedDevices: List<Pair<String, String>> = emptyList(), // name to address
)

class ObdViewModel(app: Application) : AndroidViewModel(app) {
    companion object {
        private const val TAG = "obd"
        private const val PREFS = "obd"
        private const val KEY_ADAPTER = "adapter_address"
        /** Values older than this are shown as "—". */
        const val STALE_MS = 5_000L
        /** Re-enter the extended session if the module hasn't been asked anything for this long. */
        private const val SESSION_REFRESH_MS = 3_000L
    }

    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _status = MutableStateFlow(ObdStatus())
    val status: StateFlow<ObdStatus> = _status.asStateFlow()
    private val _readings = MutableStateFlow<Map<String, Reading>>(emptyMap())
    val readings: StateFlow<Map<String, Reading>> = _readings.asStateFlow()

    private var job: Job? = null

    fun hasPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            getApplication<Application>().checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    /** Starts (or restarts) the connect-and-poll loop. Safe to call repeatedly. */
    fun start() {
        if (job?.isActive == true) return
        if (!hasPermission()) {
            _status.value = _status.value.copy(state = LinkState.NeedsPermission, detail = "Bluetooth permission needed")
            return
        }
        job = viewModelScope.launch(Dispatchers.IO) { runLoop() }
    }

    fun stop() {
        job?.cancel()
        job = null
        _readings.value = emptyMap()
        _status.value = _status.value.copy(state = LinkState.Stopped, detail = "Stopped")
        AppLog.info(TAG, "Stopped by user")
    }

    fun chooseAdapter(address: String) {
        prefs.edit().putString(KEY_ADAPTER, address).apply()
        AppLog.info(TAG, "Adapter chosen: $address")
        stop()
        start()
    }

    @SuppressLint("MissingPermission")
    private fun findAdapter(): BluetoothDevice? {
        val bt = getApplication<Application>().getSystemService(BluetoothManager::class.java)?.adapter
        if (bt == null || !bt.isEnabled) {
            _status.value = _status.value.copy(state = LinkState.BluetoothOff, detail = "Turn on Bluetooth on the box")
            return null
        }
        val bonded = bt.bondedDevices.orEmpty().toList()
        _status.value = _status.value.copy(pairedDevices = bonded.map { (it.name ?: "?") to it.address })
        val saved = prefs.getString(KEY_ADAPTER, null)
        val pick = bonded.firstOrNull { it.address == saved }
            ?: bonded.firstOrNull { (it.name ?: "").contains("OBDLink", ignoreCase = true) }
        if (pick == null) {
            _status.value = _status.value.copy(
                state = LinkState.ChooseAdapter,
                detail = "Pick the OBD adapter below (pair it in the box's Bluetooth settings first)",
            )
        }
        return pick
    }

    private suspend fun runLoop() {
        var backoffMs = 2_000L
        while (currentCoroutineContext().isActive) {
            val device = findAdapter()
            if (device == null) {
                delay(3_000)
                continue
            }
            @SuppressLint("MissingPermission")
            val name = device.name ?: device.address
            var conn: ElmConnection? = null
            try {
                _status.value = _status.value.copy(state = LinkState.Connecting, detail = "Connecting to $name…", adapterName = name)
                conn = ElmConnection.open(device)
                _status.value = _status.value.copy(state = LinkState.Initializing, detail = "Setting up adapter…")
                initAdapter(conn)
                AppLog.info("boot", "Car link live ${android.os.SystemClock.elapsedRealtime() / 1000} s after Android started")
                _status.value = _status.value.copy(state = LinkState.Live, detail = "Reading the car")
                backoffMs = 2_000L
                pollForever(conn)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.error(TAG, "Link dropped", e)
                val couldntConnect = _status.value.state == LinkState.Connecting
                _status.value = _status.value.copy(
                    state = LinkState.Retrying,
                    detail = if (couldntConnect) {
                        // Bluetooth connect refused: almost always the adapter is busy or asleep.
                        "Can't reach the OBDLink. Close Car Scanner on your iPhone (or turn its Bluetooth off) " +
                            "and switch the car on. Retrying in ${backoffMs / 1000} s"
                    } else {
                        "Lost connection (${e.message}); retrying in ${backoffMs / 1000} s"
                    },
                )
            } finally {
                conn?.close()
            }
            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(15_000L)
        }
    }

    private fun initAdapter(c: ElmConnection) {
        c.send("ATZ", 5_000)       // reset the adapter (adapter only, not the car)
        c.send("ATE0")             // echo off
        c.send("ATL0")             // no linefeeds
        c.send("ATS0")             // no spaces
        c.send("ATH1")             // show CAN ids, so we know which module answered
        c.send("ATSP6")            // ISO 15765-4 CAN, 11-bit, 500 kbaud, set explicitly (no 0100 search)
        c.send("ATAT1")            // adaptive timing
        c.send("ATST64")           // reply timeout 0x64 x 4 ms = 400 ms
        c.send("ATI")              // log adapter id
        c.send("STI")              // log STN firmware id
        c.send("ATRV")             // log 12 V at the OBD port
    }

    private suspend fun pollForever(c: ElmConnection) {
        var header: String? = null
        var customFlowControl = false
        var cycle = 0L
        val lastSessionAt = HashMap<String, Long>()
        while (currentCoroutineContext().isActive) {
            for (sig in Taycan.POLLED) {
                if (cycle % sig.pollEvery != 0L) continue
                val cmd = sig.command
                if (header != cmd.hdr) {
                    c.send("ATSH${cmd.hdr}")
                    c.send("ATCRA${cmd.rax}")
                    if (cmd.flowControl) {
                        // Flow control frames for multi-frame replies go to this module's own id.
                        c.send("ATFCSH${cmd.hdr}")
                        c.send("ATFCSD300000")
                        c.send("ATFCSM1")
                    } else if (customFlowControl) {
                        c.send("ATFCSM0")
                    }
                    customFlowControl = cmd.flowControl
                    header = cmd.hdr
                }
                if (cmd.extendedSession) {
                    val last = lastSessionAt[cmd.hdr] ?: 0L
                    if (System.currentTimeMillis() - last > SESSION_REFRESH_MS) {
                        enterSession(c, cmd)
                    }
                }
                var result = request(c, cmd)
                // Session lapsed or not accepted yet: enter it once more and retry this request.
                if (cmd.extendedSession && result is IsoTp.Result.Payload && isSessionNrc(result.bytes)) {
                    enterSession(c, cmd)
                    result = request(c, cmd)
                }
                if (cmd.extendedSession && result is IsoTp.Result.Payload && result.bytes.firstOrNull() == 0x62.toByte()) {
                    lastSessionAt[cmd.hdr] = System.currentTimeMillis()
                }
                record(sig, result)
            }
            cycle++
            delay(150)
        }
    }

    private fun enterSession(c: ElmConnection, cmd: ObdCommand) {
        val r = IsoTp.parse(c.send("1003"), cmd.rax)
        if (r is IsoTp.Result.Payload && r.bytes.firstOrNull() == 0x50.toByte()) {
            AppLog.info(TAG, "${cmd.hdr}: extended session active")
        } else {
            AppLog.warn(TAG, "${cmd.hdr}: extended session not confirmed (${describe(r)})")
        }
    }

    private fun request(c: ElmConnection, cmd: ObdCommand): IsoTp.Result =
        IsoTp.parse(c.send("22${cmd.did}"), cmd.rax)

    /** 7F 22 7F (not supported in active session) or 7F 22 31/33 often mean "enter the session first". */
    private fun isSessionNrc(p: ByteArray): Boolean =
        p.size >= 3 && p[0] == 0x7F.toByte() && p[1] == 0x22.toByte() &&
            (p[2] == 0x7F.toByte() || p[2] == 0x31.toByte() || p[2] == 0x33.toByte())

    private fun describe(r: IsoTp.Result): String = when (r) {
        is IsoTp.Result.Payload -> r.bytes.toHex()
        is IsoTp.Result.Failure -> r.reason
    }

    private fun record(sig: Signal, r: IsoTp.Result) {
        val now = System.currentTimeMillis()
        val didBytes = IsoTp.hexToBytes(sig.command.did)!!
        val reading = when {
            r is IsoTp.Result.Payload && r.bytes.size > 3 && r.bytes[0] == 0x62.toByte() &&
                r.bytes[1] == didBytes[0] && r.bytes[2] == didBytes[1] -> {
                val data = r.bytes.copyOfRange(3, r.bytes.size)
                Reading(sig.decode(data), "${sig.command.rax}: ${r.bytes.toHex()}", now)
            }
            else -> Reading(null, "${sig.command.rax}: ${describe(r)}", now)
        }
        _readings.value = _readings.value + (sig.id to reading)
        AppLog.info("value", "${sig.id} = ${reading.value?.let { "%.2f %s".format(it, sig.unit) } ?: "—"} (${reading.raw})")
    }

    override fun onCleared() {
        job?.cancel()
        super.onCleared()
    }
}
