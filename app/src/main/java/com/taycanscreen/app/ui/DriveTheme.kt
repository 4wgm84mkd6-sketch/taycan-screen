package com.taycanscreen.app.ui

import androidx.compose.ui.graphics.Color

/**
 * Filament design tokens (Main.dc.html, "normal" drive mode). The older field names stay so the Music
 * and Audio panels pick up the new look: background = void, border = lineDim, text = hi,
 * textDim = mid, textFaint = lo, chip = raised.
 */
data class DriveColors(
    val background: Color,
    val panel: Color,
    val border: Color,
    val text: Color,
    val textDim: Color,
    val textFaint: Color,
    val chip: Color,
    val track: Color,
    val accent: Color,
    val good: Color,
    val warn: Color,
    val danger: Color,
    val shadow: Color,
    val isNight: Boolean,
    val raised: Color,
    val overlay: Color,
    val lineFaint: Color,
    val lineStrong: Color,
    /** Energy flowing into the pack (regen, charging): amber at night, burnt orange by day. */
    val energyIn: Color,
    val accentSoft: Color,
    val accentLine: Color,
    val coverBg: Color,
    val onAccent: Color,
    /** Glow strength for strokes and text; 0 by day (the design draws no glow in daylight). */
    val glow: Float,
) {
    companion object {
        val Night = DriveColors(
            background = Color(0xFF04070A), panel = Color(0xFF070D12), border = Color(0x3878DCFF),
            text = Color(0xFFEAF8FF), textDim = Color(0xFF9DBBC8), textFaint = Color(0xFF6A8A97),
            chip = Color(0xFF0C151C), track = Color(0x3878DCFF), accent = Color(0xFF3EE6FF),
            good = Color(0xFF3EE6FF), warn = Color(0xFFFFC23D), danger = Color(0xFFFF4D61),
            shadow = Color(0x00000000), isNight = true,
            raised = Color(0xFF0C151C), overlay = Color(0xE0070D12),
            lineFaint = Color(0x1A78DCFF), lineStrong = Color(0x6B78DCFF),
            energyIn = Color(0xFFFFB24D), accentSoft = Color(0x243EE6FF), accentLine = Color(0x803EE6FF),
            coverBg = Color(0xFF13202A), onAccent = Color(0xFF03161C), glow = 1f,
        )
        val Day = DriveColors(
            background = Color(0xFFE3E9ED), panel = Color(0xFFF6F9FA), border = Color(0x38062838),
            text = Color(0xFF06141C), textDim = Color(0xFF33505E), textFaint = Color(0xFF4F6874),
            chip = Color(0xFFFFFFFF), track = Color(0x38062838), accent = Color(0xFF006E87),
            good = Color(0xFF006E87), warn = Color(0xFF8F5B00), danger = Color(0xFFC2182E),
            shadow = Color(0x00000000), isNight = false,
            raised = Color(0xFFFFFFFF), overlay = Color(0xF0F6F9FA),
            lineFaint = Color(0x1A062838), lineStrong = Color(0x73062838),
            energyIn = Color(0xFFA85200), accentSoft = Color(0x1A006E87), accentLine = Color(0x8C006E87),
            coverBg = Color(0xFFDCE4E9), onAccent = Color(0xFFFFFFFF), glow = 0f,
        )
    }
}

enum class ThemeMode { Auto, Day, Night;
    fun next(): ThemeMode = entries[(ordinal + 1) % entries.size]
    fun label(): String = when (this) { Auto -> "Auto"; Day -> "Day"; Night -> "Night" }
}

/** Auto: night when the sun is down at the car's GPS position (independent of the box's time zone). */
fun ThemeMode.isNight(epochMs: Long = System.currentTimeMillis()): Boolean = when (this) {
    ThemeMode.Day -> false
    ThemeMode.Night -> true
    ThemeMode.Auto -> Sun.isNight(epochMs)
}
