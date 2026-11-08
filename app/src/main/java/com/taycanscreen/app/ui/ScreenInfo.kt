package com.taycanscreen.app.ui

import android.app.Activity
import android.os.Build

/** Window sizes as Android reports them; used to size layouts to the Porsche frame. */
data class WindowNumbers(
    val windowWidthPx: Int,
    val windowHeightPx: Int,
    val maxWidthPx: Int,
    val maxHeightPx: Int,
)

fun Activity.windowNumbers(): WindowNumbers {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val cur = windowManager.currentWindowMetrics.bounds
        val max = windowManager.maximumWindowMetrics.bounds
        WindowNumbers(cur.width(), cur.height(), max.width(), max.height())
    } else {
        val dm = resources.displayMetrics
        WindowNumbers(dm.widthPixels, dm.heightPixels, dm.widthPixels, dm.heightPixels)
    }
}
