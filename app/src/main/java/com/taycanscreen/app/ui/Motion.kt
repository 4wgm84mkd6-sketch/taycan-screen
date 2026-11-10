package com.taycanscreen.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** How things arrive and leave on the Drive screen: quick, quiet, never a hard cut. */
object Motion {
    val PanelIn: EnterTransition = fadeIn(tween(160)) + slideInVertically(tween(220)) { it / 16 }
    val PanelOut: ExitTransition = fadeOut(tween(120))
    val CardIn: EnterTransition = fadeIn(tween(220)) + slideInVertically(tween(260)) { -it / 4 }
    val FadeIn: EnterTransition = fadeIn(tween(200))
    val FadeOut: ExitTransition = fadeOut(tween(120))
}

/**
 * Shows [content] with an enter and exit animation. A plain function (not tied to a Row or Column), so
 * it can be used anywhere in a layout.
 */
@Composable
fun Appear(
    visible: Boolean,
    modifier: Modifier = Modifier,
    enter: EnterTransition = Motion.PanelIn,
    exit: ExitTransition = Motion.PanelOut,
    content: @Composable () -> Unit,
) {
    AnimatedVisibility(visible, modifier, enter, exit) { content() }
}
