package com.taycanscreen.app.ui

import androidx.compose.ui.graphics.Color

/**
 * Design tokens (now the v3 look; originally Filament). The older field names stay so the Music
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
        // v3 "Center screen" (Taycan Center Screen Concept, boards "Drive — night" / "Drive — day", 2026-11-09).
        val Night = DriveColors(
            background = Color(0xFF0E0F10), panel = Color(0xFF0E0F10), border = Color(0xFF26292C),
            text = Color(0xFFECEDEE), textDim = Color(0xFFA2A6AB), textFaint = Color(0xFF7E8389),
            chip = Color(0xFF17191B), track = Color(0xFF26292C), accent = Color(0xFF7FD6B5),
            good = Color(0xFF7FD6B5), warn = Color(0xFFE8A13A), danger = Color(0xFFE5484D),
            shadow = Color(0x00000000), isNight = true,
            raised = Color(0xFF17191B), overlay = Color(0xC70E0F10),
            lineFaint = Color(0xFF1C1F22), lineStrong = Color(0xFF3A3E43),
            energyIn = Color(0xFF7FD6B5), accentSoft = Color(0x1F7FD6B5), accentLine = Color(0x807FD6B5),
            coverBg = Color(0xFF1C1F22), onAccent = Color(0xFF0E0F10), glow = 0f,
        )
        val Day = DriveColors(
            background = Color(0xFFF4F3F0), panel = Color(0xFFF4F3F0), border = Color(0xFFDEDCD7),
            text = Color(0xFF151617), textDim = Color(0xFF55585C), textFaint = Color(0xFF6A6D71),
            chip = Color(0xFFFBFAF8), track = Color(0xFFDEDCD7), accent = Color(0xFF1B7F60),
            good = Color(0xFF1B7F60), warn = Color(0xFFB26A00), danger = Color(0xFFB42318),
            shadow = Color(0x00000000), isNight = false,
            raised = Color(0xFFFBFAF8), overlay = Color(0xD9F4F3F0),
            lineFaint = Color(0xFFE8E6E1), lineStrong = Color(0xFFC4C1BB),
            energyIn = Color(0xFF1B7F60), accentSoft = Color(0x1A1B7F60), accentLine = Color(0x801B7F60),
            coverBg = Color(0xFFE8E6E1), onAccent = Color(0xFFFFFFFF), glow = 0f,
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
