package com.edgarguitarist.audiopc.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Colors from [Palette] that Material's scheme has no slot for. */
class ApColors(
    val accent: Color,
    val accent2: Color,
    val onAccent: Color,
    val surface2: Color,
    val border: Color,
    val muted: Color,
    val warn: Color,
    val dark: Boolean,
)

private val DarkAp = ApColors(
    Palette.Accent, Palette.Accent2, Palette.OnAccentDark, Palette.Surface2Dark,
    Palette.BorderDark, Palette.MutedDark, Color(0xFFF5B942), true,
)
private val LightAp = ApColors(
    Palette.AccentLight, Palette.Accent2Light, Palette.OnAccentLight, Palette.Surface2Light,
    Palette.BorderLight, Palette.MutedLight, Color(0xFFB7791F), false,
)

val LocalApColors = staticCompositionLocalOf { DarkAp }

val MaterialTheme.ap: ApColors @Composable get() = LocalApColors.current

@Composable
fun AudioPcTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val ap = if (dark) DarkAp else LightAp
    val scheme = if (dark) darkColorScheme(
        primary = ap.accent, onPrimary = ap.onAccent,
        background = Palette.BgDark, onBackground = Palette.TextDark,
        surface = Palette.SurfaceDark, onSurface = Palette.TextDark,
        surfaceVariant = Palette.Surface2Dark, onSurfaceVariant = Palette.MutedDark,
        outline = Palette.BorderDark, outlineVariant = Palette.BorderDark,
        error = Palette.Danger,
        surfaceContainer = Palette.SurfaceDark, surfaceContainerHigh = Palette.Surface2Dark,
    ) else lightColorScheme(
        primary = ap.accent, onPrimary = ap.onAccent,
        background = Palette.BgLight, onBackground = Palette.TextLight,
        surface = Palette.SurfaceLight, onSurface = Palette.TextLight,
        surfaceVariant = Palette.Surface2Light, onSurfaceVariant = Palette.MutedLight,
        outline = Palette.BorderLight, outlineVariant = Palette.BorderLight,
        error = Color(0xFFD64545),
        surfaceContainer = Palette.SurfaceLight, surfaceContainerHigh = Palette.Surface2Light,
    )
    CompositionLocalProvider(LocalApColors provides ap) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
