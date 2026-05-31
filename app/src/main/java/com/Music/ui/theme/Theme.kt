package com.Music.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val MuseDarkColorScheme = darkColorScheme(
    // Primary — cyan circuit glow
    primary                = MuseCyan,
    onPrimary              = MuseOnCyan,
    primaryContainer       = MuseCyanContainer,
    onPrimaryContainer     = MuseOnCyanContainer,

    // Secondary — steel blue
    secondary              = MuseBlue,
    onSecondary            = MuseOnBlue,
    secondaryContainer     = MuseBlueContainer,
    onSecondaryContainer   = MuseOnBlueContainer,

    // Tertiary — teal accent
    tertiary               = MuseTeal,
    onTertiary             = MuseOnTeal,
    tertiaryContainer      = MuseTealContainer,
    onTertiaryContainer    = MuseOnTealContainer,

    // Backgrounds
    background             = MuseBackground,
    onBackground           = MuseOnBackground,
    surface                = MuseSurface,
    onSurface              = MuseOnSurface,
    surfaceVariant         = MuseSurfaceVariant,
    onSurfaceVariant       = MuseOnSurfaceVar,

    // Outlines
    outline                = MuseOutline,
    outlineVariant         = MuseOutlineVariant,

    // Errors
    error                  = MuseError,
    onError                = MuseOnError,
    errorContainer         = MuseErrorContainer,
    onErrorContainer       = MuseOnErrorContainer,

    // Inverse (snackbars, tooltips)
    inverseSurface         = MuseOnSurface,
    inverseOnSurface       = MuseSurface,
    inversePrimary         = MuseCyanDim,

    // Tint applied to elevated surfaces
    surfaceTint            = MuseCyan,

    scrim                  = Color(0xCC000000)
)

@Composable
fun MuseTheme(content: @Composable () -> Unit) {
    // Always dark — matches the icon's charcoal aesthetic
    // No dynamic color — keeps brand palette consistent across all devices
    MaterialTheme(
        colorScheme = MuseDarkColorScheme,
        typography  = MuseTypography,
        content     = content
    )
}