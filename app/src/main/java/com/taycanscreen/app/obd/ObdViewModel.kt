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
import com.taycanscreen.app.log.AppLog
import com.taycanscreen.app.obd.IsoTp.toHex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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

enum class LinkState { Stopped, NeedsPermission, BluetoothOff, ChooseAdapter, Connecting, Initializing, Live, CarAsleep, Retrying }

data class ObdStatus(
    val state: LinkState = LinkState.Stopped,
    val detail: String = "",
    val adapterName: String? = null,
    val pairedDevices: List<Pair<String, String>> = emptyList(), // name to address
)

/**
 * The one OBD connection for the whole app process. It must never exist twice: two loops fight over
 * the adapter's single Bluetooth link and knock each other off (seen 2026-11-08 when Android created a
 * second copy of the home screen). Screens use it through [ObdViewModel].
 */
object ObdEngine {
        private const val TAG = "obd"
        private const val PREFS = "obd"
        private const val KEY_ADAPTER = "adapter_address"
        private const val KEY_CHANNEL1 = "connect_channel1"
        /** Re-enter the extended session if the module hasn't been asked anything for this long. */
        private const val SESSION_REFRESH_MS = 3_000L
        /** Full polling cycles with no answer from the car before we stop talking to it. */
        private const val QUIET_CYCLES_TO_SLEEP = 5
        /** While the car is off, check only the adapter's 12 V reading (no CAN traffic) this often. */
        private const val ASLEEP_CHECK_MS = 30_000L
        /** 12 V at or above this means the car's DC-DC converter is charging, i.e. the car is on. */
        private const val CAR_ON_VOLTS = 13.0

    private lateinit var app: Application
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun attach(application: Application) {
        if (!::app.isInitialized) app = application
    }

    private val prefs by lazy { app.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    private val _status = MutableStateFlow(ObdStatus())
    val status: StateFlow<ObdStatus> = _status.asStateFlow()
    private val _readings = MutableStateFlow<Map<String, Reading>>(emptyMap())
    val readings: StateFlow<Map<String, Reading>> = _readings.asStateFlow()

    private var job: Job? = null

    // ---- Discovery (read-only search for blinker and gear signals) ----
    private val _discovery = MutableStateFlow<Discovery.State>(Discovery.State.Idle)
    val discovery: StateFlow<Discovery.State> = _discovery.asStateFlow()
    @Volatile private var discoveryRequest: Discovery.Request? = null
    @Volatile private var discoveryCancel = false
    @Volatile private var scanAutoResumes = 0
    @Volatile private var scanPosition: Pair<String, Int>? = null

    fun refreshDiscovery(force: Boolean = false) {
        val found = Discovery.loadFound(app)
        if (force || _discovery.value is Discovery.State.Idle || _discovery.value is Discovery.State.Ready) {
            _discovery.value = if (found.isEmpty()) Discovery.State.Idle
            else Discovery.State.Ready(found.values.sumOf { it.size }, Discovery.recorded(app), Discovery.scanComplete(app))
        }
    }

    fun requestDiscovery(r: Discovery.Request) {
        discoveryCancel = false
        discoveryRequest = r
        AppLog.info("disc", "Requested: $r")
    }

    fun cancelDiscovery() {
        discoveryCancel = true
        discoveryRequest = null
    }

    fun hasPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            app.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    /** Starts (or restarts) the connect-and-poll loop. Safe to call repeatedly. */
    @Synchronized
    fun start() {
        if (job?.isActive == true) return
        if (!hasPermission()) {
            _status.value = _status.value.copy(state = LinkState.NeedsPermission, detail = "Bluetooth permission needed")
            return
        }
        AppLog.info(TAG, "Starting the OBD connection loop")
        job = scope.launch { runLoop() }
    }

    @Synchronized
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
        val bt = app.getSystemService(BluetoothManager::class.java)?.adapter
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
        var backoffMs = 1_000L
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
                conn = ElmConnection.open(device, prefs.getBoolean(KEY_CHANNEL1, true)) { ch1 ->
                    prefs.edit().putBoolean(KEY_CHANNEL1, ch1).apply()
                }
                _status.value = _status.value.copy(state = LinkState.Initializing, detail = "Setting up adapter…")
                initAdapter(conn)
                AppLog.info("boot", "Car link live ${android.os.SystemClock.elapsedRealtime() / 1000} s after Android started")
                _status.value = _status.value.copy(state = LinkState.Live, detail = "Reading the car")
                backoffMs = 1_000L
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
        c.send("ATD")              // adapter settings back to defaults (adapter only; faster than a full ATZ reset)
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
        var quietCycles = 0
        var asleepBaseline: Double? = null
        while (currentCoroutineContext().isActive) {
            if (quietCycles >= QUIET_CYCLES_TO_SLEEP) {
                // Car is off: send nothing onto the car's network, so its modules can sleep and the
                // 12 V battery isn't drained. Only read the adapter's own voltage measurement.
                val volts = adapterVolts(c)
                if (asleepBaseline == null) asleepBaseline = volts
                val base = asleepBaseline
                if (volts != null && (volts >= CAR_ON_VOLTS || (base != null && volts - base >= 0.4))) {
                    AppLog.info(TAG, "12 V at %.1f V: car looks switched on, resuming".format(volts))
                    quietCycles = 0
                    asleepBaseline = null
                    _status.value = _status.value.copy(state = LinkState.Live, detail = "Reading the car")
                    continue
                }
                delay(ASLEEP_CHECK_MS)
                continue
            }
            val req = discoveryRequest
            if (req != null) {
                discoveryRequest = null
                try {
                    runDiscovery(c, req)
                } catch (e: java.io.IOException) {
                    // Link dropped mid-scan: carry on automatically after reconnecting (a few times at most).
                    if (req != Discovery.Request.Scan && req != Discovery.Request.RestartScan) throw e
                    // Skip the identifier that was being read, so a module that stalls on it can't
                    // knock the scan over again and again.
                    scanPosition?.let { (hdr, did) ->
                        Discovery.savePartial(app, hdr, did + 1)
                        AppLog.warn("disc", "Skipping $hdr 22%04X after the link dropped on it".format(did))
                    }
                    if (scanAutoResumes++ < 5) {
                        AppLog.warn("disc", "Scan interrupted (${e.message}); will continue after reconnecting")
                        discoveryRequest = Discovery.Request.Scan
                    }
                    throw e
                } finally {
                    runCatching { c.send("ATST64") }
                    header = null            // force the normal header/flow control to be set again
                    customFlowControl = true
                    refreshDiscovery(force = true)
                }
                continue
            }
            var answered = false
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
                if (result is IsoTp.Result.Payload && result.bytes.firstOrNull() == 0x62.toByte()) answered = true
            }
            if (answered) {
                quietCycles = 0
            } else if (++quietCycles == QUIET_CYCLES_TO_SLEEP) {
                AppLog.info(TAG, "Car not answering for $QUIET_CYCLES_TO_SLEEP cycles: pausing requests (car off?)")
                _readings.value = emptyMap()
                _status.value = _status.value.copy(
                    state = LinkState.CarAsleep,
                    detail = "The car isn't answering (switched off?). Paused so the car can sleep; resumes when it's on.",
                )
            }
            cycle++
            delay(150)
        }
    }

    /** The adapter's measurement of the 12 V supply at the OBD port ("ATRV" → "12.6V"). Adapter-only. */
    private fun adapterVolts(c: ElmConnection): Double? =
        Regex("""(\d+(?:\.\d+)?)\s*V""").find(c.send("ATRV"))?.groupValues?.get(1)?.toDoubleOrNull()

    private suspend fun runDiscovery(c: ElmConnection, req: Discovery.Request) {
        when (req) {
            Discovery.Request.Scan, Discovery.Request.RestartScan -> {
                if (req == Discovery.Request.RestartScan) Discovery.clearAll(app)
                AppLog.info("disc", "Scan ${if (req == Discovery.Request.RestartScan) "started from scratch" else "started/continued"}")
                Discovery.clearRecorded(app) // new identifiers need every state recorded again
                c.send("ATST19") // 100 ms reply timeout: unsupported identifiers fail fast
                val found = LinkedHashMap(Discovery.loadFound(app))
                val (partialHdr, partialNext) = Discovery.loadPartial(app)
                for (m in Discovery.MODULES) {
                    val resuming = partialHdr == m.hdr
                    if (found.containsKey(m.hdr) && !resuming) continue // module finished earlier
                    Discovery.select(c, m)
                    val hits = if (resuming) ArrayList(found[m.hdr].orEmpty()) else ArrayList()
                    val start = if (resuming) partialNext.coerceIn(m.dids.first, m.dids.last) else m.dids.first
                    val count = m.dids.count()
                    if (resuming) AppLog.info("disc", "Resuming ${m.name} at 22%04X".format(start))
                    for (did in start..m.dids.last) {
                        if (discoveryCancel || !currentCoroutineContext().isActive) break
                        scanPosition = m.hdr to did
                        Discovery.read(c, m, did)?.let { data ->
                            hits.add(did)
                            Discovery.logFound(m, did, data)
                        }
                        if ((did - start) % 25 == 0) {
                            // Save progress so a Bluetooth drop only costs the last few identifiers.
                            found[m.hdr] = ArrayList(hits)
                            Discovery.saveFound(app, found)
                            Discovery.savePartial(app, m.hdr, did)
                            _discovery.value = Discovery.State.Scanning(
                                m.name, did - m.dids.first, count, found.values.sumOf { it.size },
                            )
                        }
                    }
                    found[m.hdr] = hits
                    Discovery.saveFound(app, found)
                    if (discoveryCancel) break
                    Discovery.clearPartial(app)
                    AppLog.info("disc", "${m.name} (${m.hdr}): ${hits.size} identifiers answer")
                }
                scanAutoResumes = 0
                AppLog.info("disc", "Scan ${if (discoveryCancel) "stopped" else "finished"}: ${found.values.sumOf { it.size }} identifiers")
            }
            is Discovery.Request.Record -> {
                val found = Discovery.loadFound(app)
                val total = found.values.sumOf { it.size } * Discovery.ROUNDS_PER_STEP
                var done = 0
                AppLog.info("disc", "Recording state '${req.step}'")
                for (round in 1..Discovery.ROUNDS_PER_STEP) {
                    for (m in Discovery.MODULES) {
                        val dids = found[m.hdr].orEmpty()
                        if (dids.isEmpty()) continue
                        Discovery.select(c, m)
                        for (did in dids) {
                            if (discoveryCancel || !currentCoroutineContext().isActive) return
                            val why = StringBuilder()
                            val data = Discovery.read(c, m, did, why)
                            AppLog.info(
                                "disc",
                                "state=${req.step} round=$round ${m.hdr} 22%04X = %s".format(
                                    did, data?.toHex() ?: "($why)",
                                ),
                            )
                            done++
                            if (done % 10 == 0) _discovery.value = Discovery.State.Recording(req.step, round, done, total)
                        }
                    }
                }
                Discovery.markRecorded(app, req.step)
                AppLog.info("disc", "Recorded state '${req.step}'")
            }
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
        if (sig == Taycan.SPEED) com.taycanscreen.app.audio.EqEngine.onSpeed(reading.value?.let { it * 0.621371 })
        AppLog.info("value", "${sig.id} = ${reading.value?.let { "%.2f %s".format(it, sig.unit) } ?: "—"} (${reading.raw})")
    }

}

/** Screen-side handle to the single [ObdEngine]; creating more of these never starts a second loop. */
class ObdViewModel(app: Application) : AndroidViewModel(app) {
    init {
        ObdEngine.attach(app)
    }

    val status: StateFlow<ObdStatus> get() = ObdEngine.status
    val readings: StateFlow<Map<String, Reading>> get() = ObdEngine.readings
    fun hasPermission(): Boolean = ObdEngine.hasPermission()
    fun start() = ObdEngine.start()
    fun stop() = ObdEngine.stop()
    fun chooseAdapter(address: String) = ObdEngine.chooseAdapter(address)
    val discovery: StateFlow<Discovery.State> get() = ObdEngine.discovery
    fun refreshDiscovery() = ObdEngine.refreshDiscovery()
    fun requestDiscovery(r: Discovery.Request) = ObdEngine.requestDiscovery(r)
    fun cancelDiscovery() = ObdEngine.cancelDiscovery()

    companion object {
        const val STALE_MS = 5_000L
    }
}
