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
import com.taycanscreen.app.util.Mono
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

/**
 * Latest decoded value of one signal. [value] null = the car answered but with no usable data.
 * [at] is [Mono.ms] (monotonic), never the date clock.
 */
data class Reading(val value: Double?, val raw: String, val at: Long)

enum class LinkState { Stopped, NeedsPermission, BluetoothOff, ChooseAdapter, Connecting, Initializing, Live, CarAsleep, Retrying }

data class ObdStatus(
    val state: LinkState = LinkState.Stopped,
    /** Longer explanation for the Vehicle and Setup screens. The Drive screen shows its own short text. */
    val detail: String = "",
    /** [Mono.ms] when the link last stopped being live (0 while live): how long we've been trying. */
    val downSince: Long = 0L,
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
        /** Ask a module for the extended session at most this often, and only after it refused a read. */
        private const val SESSION_RETRY_MS = 30_000L
        /** After a failed connect: retry quickly at first (a failed try already takes ~5 s), then ease off. */
        private const val RETRY_FAST_MS = 500L
        private const val RETRY_SLOW_MS = 5_000L
        private const val RETRY_FAST_FOR_MS = 120_000L
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
    /** 12 V supply measured by the adapter itself at the OBD port (ATRV; no car request). */
    private val _lowVolts = MutableStateFlow<Reading?>(null)
    val lowVolts: StateFlow<Reading?> = _lowVolts.asStateFlow()

    /** Screenshot tests only: puts fixed values on screen without a car. */
    internal fun preview(readings: Map<String, Reading>, status: ObdStatus) {
        _readings.value = readings
        _status.value = status
    }

    private var job: Job? = null
    /** A loop that was told to stop but may still be inside a Bluetooth read; the next one waits for it. */
    private var stopping: Job? = null
    /** The open adapter link, so [stop] can close it and unblock the loop at once. */
    @Volatile private var current: ElmConnection? = null

    /** Signal groups a screen has asked for ("power", "cells"); other groups aren't polled. */
    @Volatile var activeGroups: Set<String> = emptySet()
        private set

    fun setGroup(group: String, on: Boolean) {
        activeGroups = if (on) activeGroups + group else activeGroups - group
        AppLog.info(TAG, "Polling ${if (on) "on" else "off"} for $group")
    }

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
        val previous = stopping
        stopping = null
        job = scope.launch {
            // Never two loops on the adapter: wait until the old one has really ended.
            previous?.join()
            runLoop()
        }
    }

    @Synchronized
    fun stop() {
        job?.let {
            it.cancel()
            stopping = it
        }
        job = null
        current?.close()
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
        var useChannel1 = prefs.getBoolean(KEY_CHANNEL1, true)
        var downSince = Mono.ms()
        while (currentCoroutineContext().isActive) {
            val device = findAdapter()
            if (device == null) {
                delay(3_000)
                continue
            }
            @SuppressLint("MissingPermission")
            val name = device.name ?: device.address
            var conn: ElmConnection? = null
            var connected = false
            var live = false
            try {
                _status.value = _status.value.copy(state = LinkState.Connecting, detail = "Connecting to $name…", adapterName = name, downSince = downSince)
                runCatching { app.getSystemService(BluetoothManager::class.java)?.adapter?.cancelDiscovery() }
                conn = ElmConnection.open(device, useChannel1)
                current = conn
                connected = true
                if (prefs.getBoolean(KEY_CHANNEL1, true) != useChannel1) prefs.edit().putBoolean(KEY_CHANNEL1, useChannel1).apply()
                _status.value = _status.value.copy(state = LinkState.Initializing, detail = "Setting up adapter…")
                initAdapter(conn)
                AppLog.info("boot", "Car link live ${android.os.SystemClock.elapsedRealtime() / 1000} s after Android started (${(Mono.ms() - downSince) / 1000} s of trying)")
                _status.value = _status.value.copy(state = LinkState.Live, detail = "Reading the car", downSince = 0L)
                live = true
                pollForever(conn)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // stop() closes the link to unblock us: that's not a fault.
                if (!currentCoroutineContext().isActive) throw kotlinx.coroutines.CancellationException("stopped")
                AppLog.error(TAG, if (connected) "Link dropped" else "Couldn't connect", e)
                if (live) downSince = Mono.ms()
                if (!connected) useChannel1 = !useChannel1 // try the other way next
                // Never leave old numbers on screen while the link is down.
                _readings.value = emptyMap()
                _status.value = _status.value.copy(
                    state = LinkState.Retrying,
                    downSince = downSince,
                    detail = if (!connected) {
                        // Bluetooth connect refused: almost always the adapter is busy or asleep.
                        "Can't reach the OBDLink yet. If this goes on, close Car Scanner on your iPhone " +
                            "(or turn its Bluetooth off) and check the car is switched on. Still trying."
                    } else {
                        "Lost the link to the car. Reconnecting."
                    },
                )
            } finally {
                current = null
                conn?.close()
            }
            delay(if (Mono.ms() - downSince < RETRY_FAST_FOR_MS) RETRY_FAST_MS else RETRY_SLOW_MS)
        }
    }

    /**
     * Sends an adapter setting and checks it was accepted. After an interrupted request the adapter can
     * answer STOPPED instead of OK; ignoring that left the old module selected and every reply in the
     * wrong place (seen 2026-11-09 17:23:13).
     */
    private fun setting(c: ElmConnection, cmd: String): Boolean {
        if ("OK" in c.send(cmd)) return true
        AppLog.warn(TAG, "$cmd not confirmed; sending it again")
        return "OK" in c.send(cmd)
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
        // Print the adapter's sleep settings into the log (adapter only; reads, changes nothing). The
        // adapter stays powered in a locked car, and the owner suspects it of setting off the alarm.
        c.send("STSLCS")
        recordVolts(c.send("ATRV")) // 12 V at the OBD port (adapter measurement)
    }

    private fun recordVolts(reply: String): Double? {
        val v = Regex("""(\d+(?:\.\d+)?)\s*V""").find(reply)?.groupValues?.get(1)?.toDoubleOrNull()
        if (v != null) _lowVolts.value = Reading(v, reply.trim(), Mono.ms())
        return v
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
            // A module that doesn't answer its first request this cycle isn't asked again until the next
            // one: each unanswered request costs ~0.45 s, which used to starve the speed reading.
            var silentModule: String? = null
            var askedModule: String? = null
            for (sig in Taycan.POLLED) {
                if (!sig.due(cycle)) continue
                if (sig.group != null && sig.group !in activeGroups) continue
                val cmd = sig.command
                if (cmd.hdr == silentModule) continue
                val firstForModule = askedModule != cmd.hdr
                if (header != cmd.hdr || cmd.flowControl != customFlowControl) {
                    // Also re-run when flow control differs on the same module (e.g. 710 2AB6 fc vs 2A0C).
                    var ok = true
                    if (header != cmd.hdr) {
                        ok = setting(c, "ATSH${cmd.hdr}") && setting(c, "ATCRA${cmd.rax}")
                    }
                    if (ok && cmd.flowControl) {
                        // Flow control frames for multi-frame replies go to this module's own id.
                        ok = setting(c, "ATFCSH${cmd.hdr}") && setting(c, "ATFCSD300000") && setting(c, "ATFCSM1")
                    } else if (ok && customFlowControl) {
                        ok = setting(c, "ATFCSM0")
                    }
                    if (!ok) {
                        // Don't ask the car anything while the adapter may be pointed at another module.
                        AppLog.warn(TAG, "Adapter didn't take the settings for ${cmd.hdr}; skipping ${sig.id} this round")
                        header = null
                        customFlowControl = true
                        continue
                    }
                    customFlowControl = cmd.flowControl
                    header = cmd.hdr
                }
                var result = request(c, cmd)
                // 10 03 (extended session) is no longer sent up front: on this car the battery module
                // never answers it (NO DATA 26 times out of 26, ~0.43 s each) and its readings work
                // without it. It is only tried if the module actually refuses a read for that reason.
                if (cmd.extendedSession && result is IsoTp.Result.Payload && isSessionNrc(result.bytes) &&
                    Mono.ms() - (lastSessionAt[cmd.hdr] ?: -SESSION_RETRY_MS) >= SESSION_RETRY_MS
                ) {
                    lastSessionAt[cmd.hdr] = Mono.ms()
                    enterSession(c, cmd)
                    result = request(c, cmd)
                }
                askedModule = cmd.hdr
                if (firstForModule && result is IsoTp.Result.Failure && result.reason == "NO DATA") silentModule = cmd.hdr
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
            if (cycle % 100 == 50L) adapterVolts(c) // refresh the 12 V readout now and then (adapter only)
            delay(20)
        }
    }

    /** The adapter's measurement of the 12 V supply at the OBD port ("ATRV" → "12.6V"). Adapter-only. */
    private fun adapterVolts(c: ElmConnection): Double? = recordVolts(c.send("ATRV"))

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
                val rounds = Discovery.roundsFor(req.step)
                val total = found.values.sumOf { it.size } * rounds
                var done = 0
                AppLog.info("disc", "Recording state '${req.step}'")
                for (round in 1..rounds) {
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
        val now = Mono.ms()
        val didBytes = IsoTp.hexToBytes(sig.command.did)!!
        val ok = r is IsoTp.Result.Payload && r.bytes.size > 3 && r.bytes[0] == 0x62.toByte() &&
            r.bytes[1] == didBytes[0] && r.bytes[2] == didBytes[1]
        val data = if (ok) (r as IsoTp.Result.Payload).bytes.copyOfRange(3, r.bytes.size) else null
        val raw = "${sig.command.rax}: " + if (r is IsoTp.Result.Payload) r.bytes.toHex() else describe(r)
        val reading = Reading(data?.let { sig.decode(it) }, raw, now)
        // Siblings come from the same reply (e.g. a tire's temperature and set point with its pressure).
        val all = listOf(sig.id to reading) + sig.siblings.map { sib ->
            val v = data?.let { sib.decode(it) }
            AppLog.info("value", "${sib.id} = ${v?.let { "%.2f %s".format(it, sib.unit) } ?: "—"}")
            sib.id to Reading(v, raw, now)
        }
        _readings.value = _readings.value + all
        // Extras must never be able to knock the car link over.
        runCatching {
            if (data != null && sig.group == "cells") Cells.onModule(sig.id, data)
            if (reading.value != null) com.taycanscreen.app.ui.Boot.mark("First car data")
            if (sig == Taycan.SPEED_DIR) com.taycanscreen.app.audio.EqEngine.onSpeed(reading.value?.let { kotlin.math.abs(it) * 0.621371 })
        }.onFailure { AppLog.warn(TAG, "After-reading step failed: ${it.javaClass.simpleName}: ${it.message}") }
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
