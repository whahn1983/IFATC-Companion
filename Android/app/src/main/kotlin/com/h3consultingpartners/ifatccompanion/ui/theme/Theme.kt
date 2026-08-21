package com.h3consultingpartners.ifatccompanion.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

private val LightColors = lightColorScheme(
    primary = IFATCPalette.primaryLight,
    onPrimary = IFATCPalette.onPrimaryLight,
    primaryContainer = IFATCPalette.primaryContainerLight,
    onPrimaryContainer = IFATCPalette.onPrimaryContainerLight,
    secondary = IFATCPalette.secondaryLight,
    onSecondary = IFATCPalette.onSecondaryLight,
    secondaryContainer = IFATCPalette.secondaryContainerLight,
    onSecondaryContainer = IFATCPalette.onSecondaryContainerLight,
    tertiary = IFATCPalette.tertiaryLight,
    onTertiary = IFATCPalette.onTertiaryLight,
    tertiaryContainer = IFATCPalette.tertiaryContainerLight,
    onTertiaryContainer = IFATCPalette.onTertiaryContainerLight,
    background = IFATCPalette.backgroundLight,
    onBackground = IFATCPalette.onBackgroundLight,
    surface = IFATCPalette.surfaceLight,
    onSurface = IFATCPalette.onSurfaceLight,
    surfaceVariant = IFATCPalette.surfaceVariantLight,
    onSurfaceVariant = IFATCPalette.onSurfaceVariantLight,
    surfaceContainer = IFATCPalette.surfaceContainerLight,
    outline = IFATCPalette.outlineLight,
    outlineVariant = IFATCPalette.outlineVariantLight,
    error = IFATCPalette.errorLight,
    onError = IFATCPalette.onErrorLight,
    errorContainer = IFATCPalette.errorContainerLight,
    onErrorContainer = IFATCPalette.onErrorContainerLight,
)

private val DarkColors = darkColorScheme(
    primary = IFATCPalette.primaryDark,
    onPrimary = IFATCPalette.onPrimaryDark,
    primaryContainer = IFATCPalette.primaryContainerDark,
    onPrimaryContainer = IFATCPalette.onPrimaryContainerDark,
    secondary = IFATCPalette.secondaryDark,
    onSecondary = IFATCPalette.onSecondaryDark,
    secondaryContainer = IFATCPalette.secondaryContainerDark,
    onSecondaryContainer = IFATCPalette.onSecondaryContainerDark,
    tertiary = IFATCPalette.tertiaryDark,
    onTertiary = IFATCPalette.onTertiaryDark,
    tertiaryContainer = IFATCPalette.tertiaryContainerDark,
    onTertiaryContainer = IFATCPalette.onTertiaryContainerDark,
    background = IFATCPalette.backgroundDark,
    onBackground = IFATCPalette.onBackgroundDark,
    surface = IFATCPalette.surfaceDark,
    onSurface = IFATCPalette.onSurfaceDark,
    surfaceVariant = IFATCPalette.surfaceVariantDark,
    onSurfaceVariant = IFATCPalette.onSurfaceVariantDark,
    surfaceContainer = IFATCPalette.surfaceContainerDark,
    outline = IFATCPalette.outlineDark,
    outlineVariant = IFATCPalette.outlineVariantDark,
    error = IFATCPalette.errorDark,
    onError = IFATCPalette.onErrorDark,
    errorContainer = IFATCPalette.errorContainerDark,
    onErrorContainer = IFATCPalette.onErrorContainerDark,
)

private val LocalSemanticColors = staticCompositionLocalOf { IFATCSemanticColors.light }

/** The app's semantic (non-Material-slot) colours for the current theme. */
object IFATCTheme {
    val semantic: IFATCSemanticColors
        @Composable @ReadOnlyComposable get() = LocalSemanticColors.current
}

/**
 * The app theme. [darkTheme] follows the system by default; the Android entry point
 * passes `isSystemInDarkTheme()`.
 */
@Composable
fun IFATCCompanionTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors
    val semantic = if (darkTheme) IFATCSemanticColors.dark else IFATCSemanticColors.light

    CompositionLocalProvider(LocalSemanticColors provides semantic) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = IFATCTypography,
            content = content,
        )
    }
}
