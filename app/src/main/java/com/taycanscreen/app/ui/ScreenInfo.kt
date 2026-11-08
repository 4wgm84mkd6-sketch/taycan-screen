package com.taycanscreen.app.ui

import android.app.Activity
import android.os.Build
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/** Window sizes as Android reports them; used to size layouts to the Porsche frame. */
data class WindowNumbers(
    val windowWidthPx: Int,
    val windowHeightPx: Int,
    val maxWidthPx: Int,
    val maxHeightPx: Int,
    /** Space taken by the box's status bar and dock (system bars), in px per side. */
    val barsLeft: Int,
    val barsTop: Int,
    val barsRight: Int,
    val barsBottom: Int,
)

fun Activity.windowNumbers(): WindowNumbers {
    val bars = ViewCompat.getRootWindowInsets(window.decorView)
        ?.getInsets(WindowInsetsCompat.Type.systemBars())
    val l = bars?.left ?: 0
    val t = bars?.top ?: 0
    val r = bars?.right ?: 0
    val b = bars?.bottom ?: 0
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val cur = windowManager.currentWindowMetrics.bounds
        val max = windowManager.maximumWindowMetrics.bounds
        WindowNumbers(cur.width(), cur.height(), max.width(), max.height(), l, t, r, b)
    } else {
        val dm = resources.displayMetrics
        WindowNumbers(dm.widthPixels, dm.heightPixels, dm.widthPixels, dm.heightPixels, l, t, r, b)
    }
}

/** Hides (or shows) the box's status bar and dock. A swipe from the edge brings them back briefly. */
fun Activity.setFullScreen(on: Boolean) {
    val controller = WindowCompat.getInsetsController(window, window.decorView)
    if (on) {
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    } else {
        controller.show(WindowInsetsCompat.Type.systemBars())
    }
}
