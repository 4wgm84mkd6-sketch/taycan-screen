package com.taycanscreen.app.ui

import kotlinx.coroutines.flow.MutableStateFlow

/** Requests from outside the screen (e.g. Spotify's Equalizer menu) to show a panel. */
object UiEvents {
    /**
     * Screenshot tests only: draw a flat placeholder instead of the live map (its native code can't run
     * off the box) and skip the start-up sequence.
     */
    @Volatile var preview = false

    val openPanel = MutableStateFlow<String?>(null)
}
