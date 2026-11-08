package com.taycanscreen.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object Palette {
    val Background = Color(0xFF0B0B0C)
    val Surface = Color(0xFF17171A)
    val SurfaceHigh = Color(0xFF232328)
    val Text = Color(0xFFF4F4F5)
    val TextDim = Color(0xFFA1A1AA)
    val Accent = Color(0xFF39D98A)
    val Warn = Color(0xFFFFB020)
    val Error = Color(0xFFFF5A5F)
}

@Composable
fun TaycanTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Palette.Background,
            surface = Palette.Surface,
            onBackground = Palette.Text,
            onSurface = Palette.Text,
            primary = Palette.Accent,
            onPrimary = Color.Black,
            secondary = Palette.SurfaceHigh,
            onSecondary = Palette.Text,
            error = Palette.Error,
        ),
        content = content,
    )
}
