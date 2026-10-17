package com.edgarguitarist.audiopc.ui

import androidx.compose.ui.graphics.Color

/** Colores del panel web, compartidos por la app y los widgets. */
object Palette {
    val BgDark = Color(0xFF0B0D11)
    val SurfaceDark = Color(0xFF14181E)
    val Surface2Dark = Color(0xFF1B2028)
    val BorderDark = Color(0xFF252B35)
    val TextDark = Color(0xFFEEF1F5)
    val MutedDark = Color(0xFF949DAC)

    val BgLight = Color(0xFFF3F5F7)
    val SurfaceLight = Color(0xFFFFFFFF)
    val Surface2Light = Color(0xFFEEF1F4)
    val BorderLight = Color(0xFFE1E5EB)
    val TextLight = Color(0xFF10151B)
    val MutedLight = Color(0xFF5A6371)

    /** Acento en oscuro / en claro, y el segundo color del degradado de la tarjeta principal. */
    val Accent = Color(0xFF2DD4BF)
    val Accent2 = Color(0xFF22B8E6)
    val AccentLight = Color(0xFF0F9E8E)
    val Accent2Light = Color(0xFF0B86B8)
    val OnAccentDark = Color(0xFF052622)
    val OnAccentLight = Color(0xFFFFFFFF)
    val Danger = Color(0xFFFF6B6B)

    /** Baldosa clara detrás de las fotos de los equipos (en ambos temas). */
    val ThumbTop = Color(0xFFF4F6F9)
    val ThumbBottom = Color(0xFFC9D0DA)
}
