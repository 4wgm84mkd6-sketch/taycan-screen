package com.taycanscreen.app.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.audiofx.AudioEffect
import android.media.audiofx.DynamicsProcessing
import android.os.Build
import com.taycanscreen.app.log.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.max

/**
 * 10-band equalizer from the design's Audio screen, applied with Android's DynamicsProcessing effect.
 *
 * Where it attaches (the original "gate G4" question):
 *  1. Players that announce their audio session (ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION, which
 *     Spotify and most players send) get the EQ on that session.
 *  2. Otherwise we try session 0 (the box's whole output mix). Android may refuse that; the log says.
 */
object EqEngine {
    private const val TAG = "eq"
    private const val PREFS = "eq"

    val FREQS = listOf("31", "63", "125", "250", "500", "1k", "2k", "4k", "8k", "16k")
    /** Upper edge of each band (geometric midpoints between centres), Hz. */
    private val CUTOFFS = floatArrayOf(44f, 88f, 177f, 354f, 707f, 1414f, 2828f, 5657f, 11314f, 20000f)
    /** How much of the speed boost each band gets (design: bass-heavy). */
    private val BOOST_WEIGHTS = floatArrayOf(1f, 1f, 0.9f, 0.7f, 0.5f, 0.4f, 0.2f, 0f, 0f, 0f)

    data class Preset(val id: String, val label: String, val bands: List<Float>?)
    val PRESETS = listOf(
        Preset("flat", "Flat", List(10) { 0f }),
        Preset("vocal", "Vocal", listOf(-2f, -1.5f, -1f, 0f, 1.5f, 3f, 3f, 2f, 0.5f, 0f)),
        Preset("bass", "Bass+", listOf(5f, 4.5f, 3.5f, 2f, 0.5f, 0f, 0f, 0f, 0.5f, 1f)),
        Preset("night", "Night drive", listOf(2f, 1.5f, 1f, 0f, 0.5f, 1.5f, 2f, 1f, -1f, -2f)),
        Preset("custom", "Custom", null),
    )

    data class Settings(val bands: List<Float>, val preset: String, val adaptive: Boolean)
    data class Status(val attachedTo: String?, val detail: String)

    private val _settings = MutableStateFlow(Settings(PRESETS[3].bands!!, "night", true))
    val settings: StateFlow<Settings> = _settings.asStateFlow()
    private val _status = MutableStateFlow(Status(null, "Play something in Spotify to attach the equalizer"))
    val status: StateFlow<Status> = _status.asStateFlow()
    private val _speedBoost = MutableStateFlow(0f)
    /** Current speed-adaptive bass boost in dB (0–3). */
    val speedBoost: StateFlow<Float> = _speedBoost.asStateFlow()

    private lateinit var app: Context
    private val effects = HashMap<Int, DynamicsProcessing>() // audio session → effect
    private val sessionOwners = HashMap<Int, String>()

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        load()
        val filter = IntentFilter().apply {
            addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
            addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val session = i.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, AudioEffect.ERROR)
                val pkg = i.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME) ?: "?"
                if (session == AudioEffect.ERROR) return
                if (i.action == AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION) {
                    AppLog.info(TAG, "Audio session $session opened by $pkg")
                    attach(session, pkg)
                } else {
                    AppLog.info(TAG, "Audio session $session closed by $pkg")
                    detach(session)
                }
            }
        }
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        else app.registerReceiver(receiver, filter)
        AppLog.info(TAG, "Listening for players' audio sessions")
    }

    /** Fallback: try the global output mix (session 0) so the EQ works even without announcements. */
    private var globalTried = false

    fun tryGlobal() {
        if (effects.isNotEmpty() || globalTried) return
        globalTried = true
        AppLog.info(TAG, "No player announced an audio session; trying the global output mix")
        attach(0, "all audio")
    }

    private fun attach(session: Int, owner: String) {
        if (effects.containsKey(session)) return
        try {
            val cfg = DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                2, // stereo
                true, 10, // pre-EQ with our 10 bands
                false, 0, // no multiband compressor
                false, 0, // no post-EQ
                false, // no limiter (the automatic preamp prevents clipping)
            ).build()
            val dp = DynamicsProcessing(0, session, cfg)
            effects[session] = dp
            sessionOwners[session] = owner
            apply()
            dp.enabled = true
            AppLog.info(TAG, "Equalizer attached to session $session ($owner)")
            _status.value = Status(if (session == 0) "All audio" else ownerLabel(owner), "Equalizer active")
        } catch (e: Exception) {
            AppLog.warn(TAG, "Couldn't attach to session $session ($owner): ${e.javaClass.simpleName}: ${e.message}")
            if (effects.isEmpty()) _status.value = Status(null, "Couldn't attach to ${ownerLabel(owner)} (${e.javaClass.simpleName})")
        }
    }

    private fun detach(session: Int) {
        effects.remove(session)?.let { runCatching { it.release() } }
        sessionOwners.remove(session)
        if (effects.isEmpty()) _status.value = Status(null, "Play something in Spotify to attach the equalizer")
    }

    private fun ownerLabel(pkg: String) = when (pkg) {
        "com.spotify.music" -> "Spotify"
        "com.google.android.apps.youtube.music" -> "YouTube Music"
        else -> pkg
    }

    // ---- settings ----

    fun setBand(index: Int, db: Float) {
        val b = _settings.value.bands.toMutableList()
        b[index] = db.coerceIn(-12f, 12f)
        _settings.value = _settings.value.copy(bands = b, preset = "custom")
        save(); apply()
    }

    fun pickPreset(id: String) {
        val p = PRESETS.firstOrNull { it.id == id } ?: return
        _settings.value = _settings.value.copy(bands = p.bands ?: _settings.value.bands, preset = id)
        AppLog.info(TAG, "Preset ${p.label}")
        save(); apply()
    }

    fun setAdaptive(on: Boolean) {
        _settings.value = _settings.value.copy(adaptive = on)
        save(); apply()
    }

    /** Called with the car's speed; above 45 mph adds up to +3 dB of bass by 75 mph (road noise). */
    fun onSpeed(mph: Double?) {
        val boost = if (_settings.value.adaptive && mph != null) (((mph - 45) / 30).coerceIn(0.0, 1.0) * 3).toFloat() else 0f
        if (kotlin.math.abs(boost - _speedBoost.value) >= 0.25f || (boost == 0f && _speedBoost.value != 0f)) {
            _speedBoost.value = boost
            apply()
        }
    }

    /** The bands actually applied, including the speed boost. */
    fun effectiveBands(): List<Float> {
        val boost = _speedBoost.value
        return _settings.value.bands.mapIndexed { i, v -> (v + boost * BOOST_WEIGHTS[i]).coerceIn(-12f, 12f) }
    }

    /** Automatic preamp: pull everything down by the highest boost so loud songs don't distort. */
    fun preampDb(): Float = -max(0f, effectiveBands().maxOrNull() ?: 0f)

    private fun apply() {
        if (effects.isEmpty()) return
        val bands = effectiveBands()
        val pre = preampDb()
        effects.forEach { (session, dp) ->
            try {
                bands.forEachIndexed { i, db -> dp.setPreEqBandAllChannelsTo(i, DynamicsProcessing.EqBand(true, CUTOFFS[i], db)) }
                dp.setInputGainAllChannelsTo(pre)
            } catch (e: Exception) {
                AppLog.warn(TAG, "Couldn't update EQ on session $session: ${e.message}")
            }
        }
    }

    private fun save() {
        val s = _settings.value
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("bands", s.bands.joinToString(","))
            .putString("preset", s.preset)
            .putBoolean("adaptive", s.adaptive)
            .apply()
    }

    private fun load() {
        val p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val bands = p.getString("bands", null)?.split(',')?.mapNotNull { it.toFloatOrNull() }?.takeIf { it.size == 10 }
        if (bands != null) {
            _settings.value = Settings(bands, p.getString("preset", "custom") ?: "custom", p.getBoolean("adaptive", true))
        }
    }
}
