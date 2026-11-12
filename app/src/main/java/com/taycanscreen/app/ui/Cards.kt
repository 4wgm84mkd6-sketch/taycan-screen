package com.taycanscreen.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * One corner radius for every card and button that floats over the map or sits inside a panel
 * (owner, 2026-11-12: rounded corners instead of the v3 boards' square ones). Panels docked to the
 * screen edge (Classic's left panel and dock) have no corners to round.
 */
val CARD_RADIUS = 12.dp
val CardShape = RoundedCornerShape(CARD_RADIUS)

/** Filled card with a hairline. Clipped, so a press highlight keeps the rounded corners too. */
fun Modifier.card(fill: Color, line: Color) = clip(CardShape).background(fill).insetLine(line)

/** Filled card without a line. */
fun Modifier.cardFill(fill: Color) = clip(CardShape).background(fill)

/** Card with a line of a given weight (warnings, outlined buttons), optionally filled. */
fun Modifier.cardLine(width: Dp, line: Color, fill: Color = Color.Transparent) =
    clip(CardShape).background(fill).border(width, line, CardShape)
