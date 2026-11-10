package com.taycanscreen.app.car

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.taycanscreen.app.log.AppLog
import com.taycanscreen.app.obd.LinkState
import com.taycanscreen.app.obd.ObdEngine
import com.taycanscreen.app.obd.Reading
import com.taycanscreen.app.obd.Signal
import com.taycanscreen.app.obd.Taycan
import com.taycanscreen.app.util.Mono
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Trip computer and charging sessions, from the car's own readings once a second:
 *  - distance from speed, energy used (draw) and recovered (regen) from HV voltage × current;
 *  - a charging session (parked with energy flowing in): energy added, kW history, kWh per % learned,
 *    time to 80 % / 100 %.
 * The running trip is saved every 30 s, because the box loses power with the car (no clean shutdown);
 * at the next start an old saved trip moves to the history (last 20 drives).
 */
object TripComputer {
    private const val TAG = "trip"
    private const val NEW_TRIP_AFTER_MS = 15 * 60_000L
    private const val MIN_SAVED_MI = 0.3

    data class Trip(
        val startedAt: Long,
        val lastAt: Long,
        val distanceMi: Double,
        val usedKWh: Double,
        val recoveredKWh: Double,
        val socStart: Double?,
        val socNow: Double?,
    ) {
        val netKWh: Double get() = usedKWh - recoveredKWh
        val miPerKWh: Double? get() = if (distanceMi >= 0.3 && netKWh > 0.05) distanceMi / netKWh else null
        val minutes: Long get() = (lastAt - startedAt) / 60_000
    }

    data class Charge(
        val startedAt: Long,
        val lastAt: Long,
        val active: Boolean,
        val socStart: Double?,
        val socNow: Double?,
        val energyKWh: Double,
        val kwNow: Double,
        /** (time ms, kW) every 10 s, at most 6 h. */
        val samples: List<Pair<Long, Float>>,
        /** Learned from this session once the charge has risen 2 % or more. */
        val kWhPerPct: Double?,
    ) {
        /** Rough minutes to [target] % at the current rate (charging slows near full, so it's a floor). */
        fun minutesTo(target: Double): Double? {
            val soc = socNow ?: return null
            val per = kWhPerPct ?: return null
            if (soc >= target || kwNow < 1.0) return null
            return (target - soc) * per / kwNow * 60.0
        }
    }

    private val _trip = MutableStateFlow<Trip?>(null)
    val trip: StateFlow<Trip?> = _trip.asStateFlow()
    private val _history = MutableStateFlow<List<Trip>>(emptyList())
    val history: StateFlow<List<Trip>> = _history.asStateFlow()
    private val _charge = MutableStateFlow<Charge?>(null)
    val charge: StateFlow<Charge?> = _charge.asStateFlow()
    private val _charging = MutableStateFlow(false)
    /** Parked with more than 1 kW flowing in (on after ~2 s, off after ~6 s). */
    val charging: StateFlow<Boolean> = _charging.asStateFlow()

    private lateinit var app: Context
    private val gson = Gson()

    fun start(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        val prefs = app.getSharedPreferences("trips", Context.MODE_PRIVATE)
        _history.value = prefs.getString("history", null)?.let {
            runCatching { gson.fromJson<List<Trip>>(it, object : TypeToken<List<Trip>>() {}.type) }.getOrNull()
        } ?: emptyList()
        prefs.getString("current", null)?.let { json ->
            runCatching { gson.fromJson(json, Trip::class.java) }.getOrNull()?.let { t ->
                val gap = System.currentTimeMillis() - t.lastAt
                when {
                    // The box's date is still wrong (it starts in 2025 until it syncs): decide once it's right.
                    gap < 0 -> saved = t
                    gap > NEW_TRIP_AFTER_MS -> finish(t)
                    else -> _trip.value = t
                }
            }
        }
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch { loop() }
    }

    /** The trip saved before the box last lost power, held until the date clock can be trusted. */
    private var saved: Trip? = null

    /** A short stop (under 15 min): the drive before it and the one after are one trip. */
    private fun join(before: Trip, t: Trip?): Trip = if (t == null) before else t.copy(
        startedAt = before.startedAt, distanceMi = before.distanceMi + t.distanceMi,
        usedKWh = before.usedKWh + t.usedKWh, recoveredKWh = before.recoveredKWh + t.recoveredKWh,
        socStart = before.socStart ?: t.socStart,
    )

    private fun fresh(r: Map<String, Reading>, sig: Signal, now: Long): Double? =
        r[sig.id]?.takeIf { now - it.at <= sig.staleMs }?.value

    private suspend fun loop() {
        // Two clocks: [mono] measures elapsed time and the age of readings; [now] is the date, only for
        // the times shown in the ledger. The box's date jumps (2025 → today) when it syncs, mid-drive.
        var lastMono = Mono.ms()
        var lastWall = System.currentTimeMillis()
        var votes = 0
        var lastSave = 0L
        var lastSample = 0L
        while (true) {
            delay(1_000)
            val mono = Mono.ms()
            val now = System.currentTimeMillis()
            val dt = ((mono - lastMono) / 1000.0).coerceIn(0.0, 3.0)
            val jump = (now - lastWall) - (mono - lastMono)
            lastMono = mono
            lastWall = now
            if (abs(jump) > 30_000) {
                // The date was corrected: move the running trip's times with it instead of ending the trip.
                AppLog.info(TAG, "The box's clock moved by ${jump / 1000} s; trip times adjusted")
                _trip.value = _trip.value?.let { it.copy(startedAt = it.startedAt + jump, lastAt = it.lastAt + jump) }
                _charge.value = _charge.value?.let { it.copy(startedAt = it.startedAt + jump, lastAt = it.lastAt + jump) }
            }
            saved?.let { s ->
                val gap = now - s.lastAt
                if (gap >= 0) {
                    saved = null
                    if (gap > NEW_TRIP_AFTER_MS) finish(s, keepCurrent = true)
                    else {
                        AppLog.info(TAG, "Short stop (${gap / 60_000} min): carrying on with the same trip")
                        _trip.value = join(s, _trip.value)
                    }
                }
            }
            val r = ObdEngine.readings.value
            val state = ObdEngine.status.value.state
            val speed = Taycan.speedKmh(r, mono)
            val volts = fresh(r, Taycan.HV_VOLTAGE_1818, mono)?.takeIf { it > 0 } ?: fresh(r, Taycan.HV_VOLTAGE, mono)?.takeIf { it > 0 }
            val amps = fresh(r, Taycan.HV_CURRENT, mono)
            val kw = if (volts != null && amps != null) volts * amps / 1000.0 else null // negative = draw
            val gear = Taycan.gearLetter(fresh(r, Taycan.GEAR, mono))
            val soc = fresh(r, Taycan.SOC_DISPLAY, mono)

            // Charging: parked and energy flowing in.
            val parked = gear == "P" || (gear == null && (speed ?: 99.0) < 0.5)
            val inflow = kw != null && kw > 1.0
            votes = if (parked && inflow) (if (votes < 0) 1 else votes + 1) else (if (votes > 0) -1 else votes - 1)
            if (!_charging.value && votes >= 2) {
                _charging.value = true
                AppLog.info(TAG, "Charging started (%.1f kW, %s %%)".format(kw ?: 0.0, soc?.let { "%.1f".format(it) } ?: "?"))
            } else if (_charging.value && votes <= -6) {
                _charging.value = false
                _charge.value?.let {
                    _charge.value = it.copy(active = false)
                    AppLog.info(TAG, "Charging ended: +%.2f kWh, %s → %s %%".format(it.energyKWh, it.socStart, it.socNow))
                }
            }
            if (_charging.value && kw != null) {
                val c = _charge.value?.takeIf { it.active } ?: Charge(now, now, true, soc, soc, 0.0, kw, emptyList(), null)
                val energy = c.energyKWh + kw.coerceAtLeast(0.0) * dt / 3600.0
                val samples = if (mono - lastSample >= 10_000) {
                    lastSample = mono
                    (c.samples + (mono to kw.toFloat())).takeLast(2160)
                } else c.samples
                val rise = if (soc != null && c.socStart != null) soc - c.socStart else 0.0
                _charge.value = c.copy(
                    lastAt = now, socNow = soc ?: c.socNow, energyKWh = energy, kwNow = kw, samples = samples,
                    kWhPerPct = if (rise >= 2.0) energy / rise else c.kWhPerPct,
                )
            }

            // Trip: while the car is talking to us.
            if (state == LinkState.CarAsleep) {
                _trip.value?.let { finish(it); _trip.value = null }
                continue
            }
            if (state != LinkState.Live || speed == null) continue
            var t = _trip.value
            if (t == null || now - t.lastAt > NEW_TRIP_AFTER_MS) {
                t?.let { finish(it) }
                t = Trip(now, now, 0.0, 0.0, 0.0, soc, soc)
                AppLog.info(TAG, "Trip started (${soc?.let { "%.1f %%".format(it) } ?: "charge unknown"})")
            }
            var used = t.usedKWh
            var recovered = t.recoveredKWh
            var dist = t.distanceMi
            if (!_charging.value) {
                dist += abs(speed) / 3.6 * dt / 1609.344
                if (kw != null) {
                    if (kw < 0) used += -kw * dt / 3600.0
                    else if (abs(speed) > 1.0) recovered += kw * dt / 3600.0
                }
            }
            t = t.copy(lastAt = now, distanceMi = dist, usedKWh = used, recoveredKWh = recovered, socNow = soc ?: t.socNow)
            _trip.value = t
            if (mono - lastSave > 30_000) {
                lastSave = mono
                app.getSharedPreferences("trips", Context.MODE_PRIVATE).edit().putString("current", gson.toJson(t)).apply()
            }
        }
    }

    private fun finish(t: Trip, keepCurrent: Boolean = false) {
        val prefs = app.getSharedPreferences("trips", Context.MODE_PRIVATE)
        if (t.distanceMi >= MIN_SAVED_MI) {
            val list = (listOf(t) + _history.value).take(20)
            _history.value = list
            prefs.edit().putString("history", gson.toJson(list)).apply()
            AppLog.info(TAG, "Trip saved: %.1f mi, used %.2f kWh, recovered %.2f kWh, %s mi/kWh, %d min".format(
                t.distanceMi, t.usedKWh, t.recoveredKWh, t.miPerKWh?.let { "%.2f".format(it) } ?: "—", t.minutes))
        }
        if (!keepCurrent) prefs.edit().remove("current").apply()
    }
}
