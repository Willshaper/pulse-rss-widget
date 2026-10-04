package com.pulse.rsswidget.widget

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.glance.color.ColorProviders
import androidx.glance.material3.ColorProviders

/**
 * Fixed widget palettes for the Neutral and Black styles. Only the roles the widget draws with are
 * set: surface (background), onSurface (headlines), onSurfaceVariant (fallback icon, empty state),
 * primary (refresh/settings icons, progress) and outline (the line under the top bar).
 */
internal object WidgetColors {

    /** Plain gray in the style of untinted launcher icons — no wallpaper tint; follows light/dark mode. */
    val neutral: ColorProviders = ColorProviders(
        light = lightColorScheme(
            surface = Color(0xFFF4F2FA),
            onSurface = Color(0xFF1C1A24),
            onSurfaceVariant = Color(0xFF484652),
            primary = Color(0xFF1C1A24),
            outline = Color(0xFFC9C5D0)
        ),
        dark = darkColorScheme(
            surface = Color(0xFF1C1A24),
            onSurface = Color(0xFFF7F5FF),
            onSurfaceVariant = Color(0xFFC9C5D0),
            primary = Color(0xFFF7F5FF),
            outline = Color(0xFF484652)
        )
    )

    private val blackScheme = darkColorScheme(
        surface = Color.Black,
        onSurface = Color(0xFFF7F5FF),
        onSurfaceVariant = Color(0xFFA8A6B0),
        primary = Color(0xFFF7F5FF),
        outline = Color(0xFF2A2830)
    )

    /**
     * Pure black for OLED screens, in both light and dark mode. Built as a light/dark pair (both
     * halves black) so it reaches the widget the same way Neutral does — as day/night colors —
     * rather than as single fixed colors, which came out with the wallpaper color on a real device.
     */
    val black: ColorProviders = ColorProviders(light = blackScheme, dark = blackScheme)
}
