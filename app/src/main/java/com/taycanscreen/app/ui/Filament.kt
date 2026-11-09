package com.taycanscreen.app.ui

import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.taycanscreen.app.R

/**
 * Filament type, shapes and icons. Fonts are bundled (SIL Open Font License, see assets/licenses):
 * Saira (variable weight and width) for numbers and labels, JetBrains Mono for small caps readouts.
 *
 * The Drive screen draws in design pixels: 1 dp there equals 1 px of the 1602×720 Filament boards
 * (see DriveScreen's density override), so sizes below are copied straight from the design.
 */
@OptIn(ExperimentalTextApi::class)
object Filament {
    private fun saira(w: Int, width: Float = 100f) =
        Font(R.font.saira, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w), FontVariation.width(width)))

    private fun mono(w: Int) =
        Font(R.font.jetbrains_mono, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))

    val Saira = FontFamily(saira(300), saira(400), saira(500), saira(600), saira(700))
    /** Gear letters: font-stretch 112%. */
    val SairaWide = FontFamily(saira(600, 112f))
    /** Start-up title: font-stretch 125%. */
    val SairaTitle = FontFamily(saira(300, 125f))
    val Mono = FontFamily(mono(400), mono(500), mono(600))

    /** clip-path "cut": top-left and bottom-right corners cut at 14 px. */
    val Cut = CutCornerShape(topStart = 14.dp, bottomEnd = 14.dp)
    /** "cut-s": the same at 8 px. */
    val CutS = CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp)

    /**
     * Owner (2026-11-09): all text about 2 pt bigger. Small and mid sizes grow by 3 design px
     * (13 → 16, 20 → 23), tapering to nothing for the big numerals (the speed stays 132).
     */
    fun bump(size: TextUnit): TextUnit {
        val s = size.value
        val extra = 3f * ((90f - s) / 60f).coerceIn(0f, 1f)
        return (s + extra).sp
    }

    /** Tight line box (Saira's own is 1.57× the size, which clipped numbers in fixed-height rows). */
    private val Tight = LineHeightStyle(LineHeightStyle.Alignment.Proportional, LineHeightStyle.Trim.Both)

    /** Numbers: Saira with tabular figures, so digits don't jump as values change. */
    fun num(size: TextUnit, weight: Int, color: Color, glow: Color? = null) = TextStyle(
        fontFamily = Saira, fontWeight = FontWeight(weight), fontSize = bump(size), color = color,
        fontFeatureSettings = "tnum", shadow = glow?.let { Shadow(it, blurRadius = 14f) },
        lineHeight = 1.2.em, lineHeightStyle = Tight,
    )

    fun label(size: TextUnit, color: Color, spacing: Float = 0.14f, weight: Int = 400) = TextStyle(
        fontFamily = Mono, fontWeight = FontWeight(weight), fontSize = bump(size), color = color,
        letterSpacing = spacing.em, fontFeatureSettings = "tnum",
        lineHeight = 1.2.em, lineHeightStyle = Tight,
    )

    fun text(size: TextUnit, weight: Int, color: Color) = TextStyle(
        fontFamily = Saira, fontWeight = FontWeight(weight), fontSize = bump(size), color = color,
        lineHeight = 1.2.em, lineHeightStyle = Tight,
    )
}

/** Line icons from Filament's Icons board (24×24, square caps, mitred joins). Tint with Icon(tint=…). */
object FIcons {
    private fun stroke(name: String, width: Float, path: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            addPath(
                pathData = addPathNodes(path),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = width,
                strokeLineCap = StrokeCap.Square,
                strokeLineJoin = StrokeJoin.Miter,
            )
        }.build()

    private fun filled(name: String, path: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            addPath(
                pathData = addPathNodes(path),
                fill = SolidColor(Color.Black),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.2f,
                strokeLineJoin = StrokeJoin.Miter,
            )
        }.build()

    val Vehicle = stroke("vehicle", 1.5f, "M3 16H2v-3.2l3.6-1.3L9 8h6.6l4 3.7 2.4.8V16h-1.2 M8.3 16h7.4 M4.7 16a1.8 1.8 0 1 0 3.6 0a1.8 1.8 0 1 0-3.6 0 M15.7 16a1.8 1.8 0 1 0 3.6 0a1.8 1.8 0 1 0-3.6 0")
    val Navigate = stroke("navigate", 1.5f, "M12 3l7 18-7-4-7 4z")
    val Music = stroke("music", 1.5f, "M8.5 17V5.5L20 3v11.5 M3.5 17a2.5 2.5 0 1 0 5 0a2.5 2.5 0 1 0-5 0 M15 14.5a2.5 2.5 0 1 0 5 0a2.5 2.5 0 1 0-5 0")
    val Eq = stroke("eq", 1.5f, "M6 4v16 M12 4v16 M18 4v16 M3.5 14h5 M9.5 8h5 M15.5 16h5")
    val Sun = stroke("sun", 1.5f, "M8 12a4 4 0 1 0 8 0a4 4 0 1 0-8 0 M12 2v2.5 M12 19.5V22 M2 12h2.5 M19.5 12H22 M4.9 4.9l1.8 1.8 M17.3 17.3l1.8 1.8 M4.9 19.1l1.8-1.8 M17.3 6.7l1.8-1.8")
    val Moon = stroke("moon", 1.5f, "M20 14.5A8.5 8.5 0 1 1 9.5 4a6.8 6.8 0 0 0 10.5 10.5z")
    /** Auto day/night: half sun, half moon. */
    val Auto = stroke("auto", 1.5f, "M3 12a9 9 0 1 0 18 0a9 9 0 1 0-18 0 M12 3v18 M12 7.5a4.5 4.5 0 0 1 0 9")
    val Setup = stroke("setup", 1.5f, "M4 4h6v6H4z M14 4h6v6h-6z M4 14h6v6H4z M14 14h6v6h-6z")
    val Volume = stroke("volume", 1.8f, "M4 9h4l5-4v14l-5-4H4z M16.5 8.5a5 5 0 0 1 0 7 M19 6a8.5 8.5 0 0 1 0 12")
    val Plus = stroke("plus", 1.6f, "M12 5v14 M5 12h14")
    val Minus = stroke("minus", 1.6f, "M5 12h14")
    val Compass = stroke("compass", 1.6f, "M3 12a9 9 0 1 0 18 0a9 9 0 1 0-18 0 M12 5.5l3 6.5H9z M9 12l3 6.5 3-6.5")
    val Recenter = stroke("recenter", 1.6f, "M6 12a6 6 0 1 0 12 0a6 6 0 1 0-12 0 M12 2v4 M12 18v4 M2 12h4 M18 12h4 M11 12a1 1 0 1 0 2 0a1 1 0 1 0-2 0")
    val Gps = stroke("gps", 1.8f, "M2.5 20a1.5 1.5 0 1 0 3 0a1.5 1.5 0 1 0-3 0 M4 14a6 6 0 0 1 6 6 M4 8a12 12 0 0 1 12 12")
    val Prev = stroke("prev", 1.6f, "M19 5L9 12l10 7z M5 5v14")
    val Next = stroke("next", 1.6f, "M5 5l10 7-10 7z M19 5v14")
    val Close = stroke("close", 1.6f, "M5 5l14 14 M19 5L5 19")
    val Search = stroke("search", 1.6f, "M4 10.5a6.5 6.5 0 1 0 13 0a6.5 6.5 0 1 0-13 0 M15.5 15.5L21 21")
    val Pin = stroke("pin", 1.6f, "M12 21s-6.5-6.2-6.5-11.5a6.5 6.5 0 0 1 13 0C18.5 14.8 12 21 12 21z M10 9.5a2 2 0 1 0 4 0a2 2 0 1 0-4 0")
    val Recent = stroke("recent", 1.6f, "M3 12a9 9 0 1 0 18 0a9 9 0 1 0-18 0 M12 7v5l3 2")
    val Backspace = stroke("backspace", 1.6f, "M8 5h12v14H8l-6-7z M11 9l6 6 M17 9l-6 6")
    val Shift = stroke("shift", 1.6f, "M12 4l8 8h-4.5v7h-7v-7H4z")

    // Turn arrows for the next-manoeuvre banner (drawn heavier so they read at a glance).
    val TurnStraight = stroke("t-straight", 2.4f, "M12 21V4 M6 10l6-6 6 6")
    val TurnRight = stroke("t-right", 2.4f, "M6 21v-8a4 4 0 0 1 4-4h10 M15 4l5 5-5 5")
    val TurnLeft = stroke("t-left", 2.4f, "M18 21v-8a4 4 0 0 0-4-4H4 M9 4L4 9l5 5")
    val TurnSlightRight = stroke("t-sright", 2.4f, "M8 21v-7l9-9 M11 5h6v6")
    val TurnSlightLeft = stroke("t-sleft", 2.4f, "M16 21v-7L7 5 M13 5H7v6")
    val TurnSharpRight = stroke("t-shright", 2.4f, "M7 21V6l11 11 M18 10v7h-7")
    val TurnSharpLeft = stroke("t-shleft", 2.4f, "M17 21V6L6 17 M6 10v7h7")
    val UTurn = stroke("t-uturn", 2.4f, "M16 21V9a4 4 0 0 0-8 0v8 M4 13l4 4 4-4")
    val Roundabout = stroke("t-round", 2.4f, "M12 21v-6 M12 15a4 4 0 1 1 4-4 M16 11V6 M13 8l3-3 3 3")
    val Play = filled("play", "M7 4.5v15L19.5 12z")
    val Pause = filled("pause", "M6.5 4.5h4v15h-4z M13.5 4.5h4v15h-4z")
}
