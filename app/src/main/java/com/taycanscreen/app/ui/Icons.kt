package com.taycanscreen.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Line icons from the "Taycan Center Screen Concept" design (24×24 stroke paths). Tint with Icon(tint=…). */
object DesignIcons {
    private fun stroke(name: String, width: Float, vararg paths: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            paths.forEach {
                addPath(
                    pathData = addPathNodes(it),
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = width,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()

    private fun filled(name: String, vararg paths: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            paths.forEach { addPath(pathData = addPathNodes(it), fill = SolidColor(Color.Black)) }
        }.build()

    private fun circle(cx: Float, cy: Float, r: Float) =
        "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0"

    val Vehicle = stroke("vehicle", 1.7f, "M3 13l2.5-6h13L21 13v5H3z", "M3 13h18", circle(7f, 16f, 1f), circle(17f, 16f, 1f))
    val Navigate = stroke("navigate", 1.9f, "M3 11l19-9-9 19-2-8z")
    val Media = stroke("media", 1.9f, "M9 18V5l12-2v13", circle(6f, 18f, 3f), circle(18f, 16f, 3f))
    val Bolt = stroke("bolt", 1.9f, "M13 2L4 14h7l-1 8 9-12h-7z")
    val Settings = stroke(
        "settings", 1.9f,
        "M4.5 3h4a1.5 1.5 0 0 1 1.5 1.5v4a1.5 1.5 0 0 1-1.5 1.5h-4A1.5 1.5 0 0 1 3 8.5v-4A1.5 1.5 0 0 1 4.5 3z",
        "M15.5 3h4a1.5 1.5 0 0 1 1.5 1.5v4a1.5 1.5 0 0 1-1.5 1.5h-4A1.5 1.5 0 0 1 14 8.5v-4A1.5 1.5 0 0 1 15.5 3z",
        "M4.5 14h4a1.5 1.5 0 0 1 1.5 1.5v4a1.5 1.5 0 0 1-1.5 1.5h-4A1.5 1.5 0 0 1 3 19.5v-4A1.5 1.5 0 0 1 4.5 14z",
        "M15.5 14h4a1.5 1.5 0 0 1 1.5 1.5v4a1.5 1.5 0 0 1-1.5 1.5h-4a1.5 1.5 0 0 1-1.5-1.5v-4a1.5 1.5 0 0 1 1.5-1.5z",
    )
    val Volume = stroke("volume", 1.9f, "M11 5L6 9H3v6h3l5 4z", "M15.5 8.5a5 5 0 0 1 0 7")
    val ChevronLeft = stroke("chevron-left", 2.2f, "M15 18l-6-6 6-6")
    val ChevronRight = stroke("chevron-right", 2.2f, "M9 18l6-6-6-6")
    val Plus = stroke("plus", 2f, "M5 12h14", "M12 5v14")
    val Minus = stroke("minus", 2f, "M5 12h14")
    val Recenter = stroke("recenter", 2f, circle(12f, 12f, 3f), "M12 2v3", "M12 19v3", "M2 12h3", "M19 12h3")
    val Battery = stroke("battery", 1.8f, "M4 7h14a2 2 0 0 1 2 2v6a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V9a2 2 0 0 1 2-2z", "M22 11v2")
    val Sun = stroke(
        "sun", 1.9f, circle(12f, 12f, 4f),
        "M12 2v2", "M12 20v2", "M4.9 4.9l1.4 1.4", "M17.7 17.7l1.4 1.4", "M2 12h2", "M20 12h2", "M4.9 19.1l1.4-1.4", "M17.7 6.3l1.4-1.4",
    )
    val Moon = stroke("moon", 1.9f, "M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8z")
    val Auto = stroke("auto", 1.9f, circle(12f, 12f, 9f), "M12 3v18", "M12 3a9 9 0 0 1 0 18z")
    val Play = filled("play", "M7 4l13 8-13 8z")
    val Pause = filled("pause", "M6 4h4v16H6z", "M14 4h4v16h-4z")
    val Previous = filled("previous", "M19 20L9 12l10-8z", "M4 5h2.5v14H4z")
    val Next = filled("next", "M5 4l10 8-10 8z", "M17.5 5H20v14h-2.5z")
    val Close = stroke("close", 2.2f, "M6 6l12 12", "M18 6L6 18")
    /** Design's "Audio and equalizer" sliders icon. */
    val Equalizer = stroke(
        "equalizer", 1.9f, "M4 21v-7", "M4 10V3", "M12 21v-9", "M12 8V3", "M20 21v-5", "M20 12V3", "M1 14h6", "M9 8h6", "M17 16h6",
    )
    /** Compass with "N": map is north-up. */
    val NorthUp = stroke("north-up", 1.9f, circle(12f, 12f, 9f), "M9 15.5V8.5l6 7V8.5")
    /** Compass with arrow: map turns with the car. */
    val HeadingUp = stroke("heading-up", 1.9f, circle(12f, 12f, 9f), "M12 6l4 10-4-2.5L8 16z")
}
