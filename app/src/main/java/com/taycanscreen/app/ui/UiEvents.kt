package com.taycanscreen.app.ui

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow

/** Requests from outside the screen (e.g. Spotify's Equalizer menu) to show a panel. */
object UiEvents {
    /**
     * Screenshot tests only: draw a flat placeholder instead of the live map (its native code can't run
     * off the box) and skip the start-up sequence.
     */
    @Volatile var preview = false

    val openPanel = MutableStateFlow<String?>(null)

    /**
     * Drive layout, chosen in Setup: false = Classic (panel on the left, owner's 2026-11-09/10 choices),
     * true = Quiet (the map fills the screen; speed, next turn and arrival in the corners). Classic stays
     * the default until the owner says otherwise.
     */
    val quiet = MutableStateFlow(false)
    private var layoutLoaded = false

    fun loadLayout(context: Context) {
        if (layoutLoaded || preview) return
        layoutLoaded = true
        quiet.value = context.getSharedPreferences("ui", Context.MODE_PRIVATE).getString("layout", "classic") == "quiet"
    }

    fun setQuiet(context: Context, on: Boolean) {
        layoutLoaded = true
        quiet.value = on
        context.getSharedPreferences("ui", Context.MODE_PRIVATE).edit().putString("layout", if (on) "quiet" else "classic").apply()
        com.taycanscreen.app.log.AppLog.info("screen", "Drive layout: ${if (on) "Quiet" else "Classic"}")
    }
}
