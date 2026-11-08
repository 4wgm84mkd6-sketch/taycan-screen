package com.taycanscreen.app.ui

import androidx.compose.ui.graphics.Color
import java.time.LocalTime

/** Day and night colors from the design's "Drive — day" and "Drive — night" artboards. */
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
) {
    companion object {
        val Day = DriveColors(
            background = Color(0xFFF4F5F7), panel = Color(0xFFFFFFFF), border = Color(0xFFE3E5E8),
            text = Color(0xFF171A20), textDim = Color(0xFF5C5E62), textFaint = Color(0xFF6E7175),
            chip = Color(0xFFEEF0F2), track = Color(0xFFE3E5E8), accent = Color(0xFF3E6AE1),
            good = Color(0xFF2E9E5B), warn = Color(0xFFD98A00), danger = Color(0xFFC2362B),
            shadow = Color(0x1A171A20), isNight = false,
        )
        val Night = DriveColors(
            background = Color(0xFF0B0C0E), panel = Color(0xFF17191D), border = Color(0xFF2A2E34),
            text = Color(0xFFEEF0F2), textDim = Color(0xFFA3A7AD), textFaint = Color(0xFF8D9197),
            chip = Color(0xFF24272C), track = Color(0xFF2A2E34), accent = Color(0xFF6B8FF2),
            good = Color(0xFF3CC173), warn = Color(0xFFF2A93B), danger = Color(0xFFFF7A6E),
            shadow = Color(0x73000000), isNight = true,
        )
    }
}

enum class ThemeMode { Auto, Day, Night;
    fun next(): ThemeMode = entries[(ordinal + 1) % entries.size]
    fun label(): String = when (this) { Auto -> "Auto"; Day -> "Day"; Night -> "Night" }
}

/** Auto: night from 6:30 pm to 6:30 am by the box's clock (set the box's time zone correctly). */
fun ThemeMode.isNight(now: LocalTime = LocalTime.now()): Boolean = when (this) {
    ThemeMode.Day -> false
    ThemeMode.Night -> true
    ThemeMode.Auto -> now.isAfter(LocalTime.of(18, 30)) || now.isBefore(LocalTime.of(6, 30))
}
