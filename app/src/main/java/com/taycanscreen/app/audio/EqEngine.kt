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
    /** Built-in presets (bands 31 Hz … 16 kHz). The first four are from the design. */
    val PRESETS = listOf(
        Preset("flat", "Flat", List(10) { 0f }),
        Preset("night", "Night drive", listOf(2f, 1.5f, 1f, 0f, 0.5f, 1.5f, 2f, 1f, -1f, -2f)),
        Preset("bass", "Bass+", listOf(5f, 4.5f, 3.5f, 2f, 0.5f, 0f, 0f, 0f, 0.5f, 1f)),
        Preset("treble", "Treble+", listOf(0f, 0f, 0f, 0f, 0f, 0.5f, 1.5f, 3f, 4.5f, 5f)),
        Preset("vocal", "Vocal", listOf(-2f, -1.5f, -1f, 0f, 1.5f, 3f, 3f, 2f, 0.5f, 0f)),
        Preset("spoken", "Podcast", listOf(-3f, -2f, -1f, 0f, 2f, 4f, 4f, 2.5f, 0f, -1f)),
        Preset("rock", "Rock", listOf(4f, 3.5f, 2.5f, 1f, -0.5f, -1f, 0.5f, 2f, 3f, 3.5f)),
        Preset("pop", "Pop", listOf(-1f, -0.5f, 0.5f, 2f, 3f, 3f, 2f, 0.5f, -0.5f, -1f)),
        Preset("hiphop", "Hip-hop", listOf(5f, 4.5f, 3f, 1.5f, 0f, -0.5f, 0.5f, 0f, 1f, 1.5f)),
        Preset("electronic", "Electronic", listOf(5f, 4f, 2f, 0f, -1.5f, 0f, 1f, 2.5f, 4f, 4.5f)),
        Preset("jazz", "Jazz", listOf(3f, 2f, 1f, 1.5f, -1f, -1f, 0f, 1f, 2f, 3f)),
        Preset("classical", "Classical", listOf(2.5f, 2f, 1.5f, 1f, 0f, 0f, 0f, 1f, 2f, 2.5f)),
        Preset("loudness", "Loudness", listOf(5f, 4f, 2.5f, 0f, -1f, -1f, 0f, 1.5f, 3.5f, 4.5f)),
    )
    const val USER_SLOTS = 3

    private val _userPresets = MutableStateFlow<List<Preset>>(emptyList())
    /** The owner's saved EQs ("My EQ 1…3"); bands null = empty slot. */
    val userPresets: StateFlow<List<Preset>> = _userPresets.asStateFlow()

    /** Saves the current bands into slot 1…3. */
    fun saveUser(slot: Int) {
        val bands = _settings.value.bands
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("user$slot", bands.joinToString(",")).apply()
        loadUser()
        _settings.value = _settings.value.copy(preset = "user$slot")
        save()
        AppLog.info(TAG, "Saved My EQ $slot: ${bands.joinToString(" ")}")
    }

    private fun loadUser() {
        val p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _userPresets.value = (1..USER_SLOTS).map { i ->
            Preset("user$i", "My EQ $i", p.getString("user$i", null)?.split(',')?.mapNotNull { it.toFloatOrNull() }?.takeIf { it.size == 10 })
        }
    }

    data class Settings(val bands: List<Float>, val preset: String, val adaptive: Boolean)
    data class Status(val attachedTo: String?, val detail: String)

    private val _settings = MutableStateFlow(Settings(PRESETS.first { it.id == "night" }.bands!!, "night", true))
    val settings: StateFlow<Settings> = _settings.asStateFlow()
    private val _status = MutableStateFlow(Status(null, WAITING))
    val status: StateFlow<Status> = _status.asStateFlow()
    private val _speedBoost = MutableStateFlow(0f)
    /** Current speed-adaptive bass boost in dB (0–3). */
    val speedBoost: StateFlow<Float> = _speedBoost.asStateFlow()

    private const val WAITING = "Equalizer starts when music plays"
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private lateinit var app: Context
    // Main thread only (attach, detach and apply all touch it).
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
            override fun onReceive(c: Context, i: Intent) = onSessionBroadcast(i)
        }
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        else app.registerReceiver(receiver, filter)
        AppLog.info(TAG, "Listening for players' audio sessions")
        reattachRemembered()
    }

    /**
     * A player announced (or closed) its audio session. Comes from the receiver registered above and
     * from [AudioSessionReceiver] in the manifest, which also catches announcements made while our
     * app wasn't running yet (e.g. Spotify resuming as the car powers up).
     */
    fun onSessionBroadcast(i: Intent) {
        val session = i.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, AudioEffect.ERROR)
        val pkg = i.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME) ?: "?"
        if (session == AudioEffect.ERROR) return
        if (i.action == AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION) {
            if (effects.containsKey(session)) return // same announcement through both receivers
            AppLog.info(TAG, "Audio session $session opened by $pkg")
            attach(session, pkg)
        } else if (i.action == AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION) {
            if (!effects.containsKey(session)) return
            AppLog.info(TAG, "Audio session $session closed by $pkg")
            detach(session)
            if (session == rememberedSession()) forgetSession()
        }
    }

    // Spotify keeps one session number while a player lives and makes a new one each time it builds a
    // new player (121 → 129 → 137 → 145 within minutes in the logs). After one of our updates we try the
    // remembered number once; if that player is gone Android answers error -3, which is expected and
    // handled quietly: the EQ attaches again by itself the next time Spotify starts playing.
    private fun bootCount(): Int =
        runCatching { android.provider.Settings.Global.getInt(app.contentResolver, android.provider.Settings.Global.BOOT_COUNT) }.getOrDefault(-1)

    private fun rememberedSession(): Int = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("spotify_session", 0)

    private fun rememberSession(session: Int) {
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt("spotify_session", session).putInt("spotify_session_boot", bootCount()).apply()
    }

    private fun forgetSession() {
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("spotify_session").apply()
    }

    private fun reattachRemembered() {
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val session = prefs.getInt("spotify_session", 0)
        val boot = prefs.getInt("spotify_session_boot", -2)
        if (session <= 0) return
        if (boot != bootCount() || boot == -1) {
            AppLog.info(TAG, "Box restarted since Spotify's audio session $session was seen; waiting for a new one")
            forgetSession()
            return
        }
        AppLog.info(TAG, "Re-attaching to Spotify's audio session $session (remembered from this box start)")
        attach(session, "com.spotify.music", remembered = true)
    }

    /** Fallback: try the global output mix (session 0) so the EQ works even without announcements. */
    private var globalTried = false

    fun tryGlobal() {
        if (effects.isNotEmpty() || globalTried) return
        globalTried = true
        AppLog.info(TAG, "No player announced an audio session; trying the global output mix")
        attach(0, "all audio")
    }

    private fun attach(session: Int, owner: String, remembered: Boolean = false) {
        if (effects.containsKey(session)) return
        var created: DynamicsProcessing? = null
        try {
            val cfg = DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                2, // stereo
                true, 10, // pre-EQ with our 10 bands
                false, 0, // no multiband compressor
                false, 0, // no post-EQ
                true, // limiter: catches peaks so boosts can stay audible
            ).build()
            val dp = DynamicsProcessing(0, session, cfg)
            created = dp
            dp.setLimiterAllChannelsTo(
                DynamicsProcessing.Limiter(true, true, 0, 1f, 60f, 10f, -1f, 0f),
            )
            effects[session] = dp
            // A real player session is better than the global mix; don't run both.
            if (session != 0) effects.remove(0)?.let { runCatching { it.release() }; sessionOwners.remove(0); AppLog.info(TAG, "Released the global EQ in favour of $owner") }
            sessionOwners[session] = owner
            apply()
            dp.enabled = true
            AppLog.info(TAG, "Equalizer attached to session $session ($owner)")
            if (owner == "com.spotify.music" && session > 0) rememberSession(session)
            _status.value = Status(if (session == 0) "All audio" else ownerLabel(owner), "Equalizer active")
        } catch (e: Exception) {
            effects.remove(session)
            created?.let { runCatching { it.release() } }
            if (remembered) {
                AppLog.info(TAG, "Spotify's earlier audio session $session is gone (${e.message}); waiting for it to start playing")
                forgetSession()
            } else {
                AppLog.warn(TAG, "Couldn't attach to session $session ($owner): ${e.javaClass.simpleName}: ${e.message}")
            }
            if (effects.isEmpty()) _status.value = Status(null, WAITING)
        }
    }

    private fun detach(session: Int) {
        effects.remove(session)?.let { runCatching { it.release() } }
        sessionOwners.remove(session)
        if (effects.isEmpty()) _status.value = Status(null, WAITING)
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
        val p = (PRESETS + _userPresets.value).firstOrNull { it.id == id } ?: return
        val bands = p.bands ?: return // empty saved slot
        _settings.value = _settings.value.copy(bands = bands, preset = id)
        AppLog.info(TAG, "Preset ${p.label}")
        save(); apply()
    }

    fun setAdaptive(on: Boolean) {
        _settings.value = _settings.value.copy(adaptive = on)
        save(); apply()
    }

    /** Called with the car's speed; above 45 mph adds up to +3 dB of bass by 75 mph (road noise). */
    fun onSpeed(mph: Double?) {
        // Comes from the car-data thread; the effects belong to the main thread.
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            main.post { onSpeed(mph) }
            return
        }
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

    /**
     * Automatic preamp: the limiter catches peaks, so only pull down boosts above +3 dB (and by half).
     * The first version subtracted the whole boost, which made Bass+ mostly sound quieter.
     */
    fun preampDb(): Float = -max(0f, ((effectiveBands().maxOrNull() ?: 0f) - 3f) / 2f)

    /** Called when Spotify's Equalizer menu opens our panel with its audio session. */
    fun attachFromPanel(session: Int, pkg: String) {
        AppLog.info(TAG, "$pkg opened our equalizer with session $session")
        if (session > 0) attach(session, pkg)
    }

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
        loadUser()
        val p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val bands = p.getString("bands", null)?.split(',')?.mapNotNull { it.toFloatOrNull() }?.takeIf { it.size == 10 }
        if (bands != null) {
            _settings.value = Settings(bands, p.getString("preset", "custom") ?: "custom", p.getBoolean("adaptive", true))
        }
    }
}
