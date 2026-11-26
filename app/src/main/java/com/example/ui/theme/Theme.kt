package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val LightColorScheme = lightColorScheme(
    primary = AppleSystemBlueLight,
    onPrimary = Color.White,
    primaryContainer = AppleSystemBlueLight.copy(alpha = 0.14f),
    onPrimaryContainer = AppleSystemBlueLight,
    secondary = AppleSystemIndigoLight,
    onSecondary = Color.White,
    background = AppleGroupedBgLight,
    onBackground = AppleTextPrimaryLight,
    surface = AppleSurfaceLight,
    onSurface = AppleTextPrimaryLight,
    surfaceVariant = AppleSecondarySurfaceLight,
    onSurfaceVariant = AppleTextSecondaryLight,
    outline = AppleHairlineLight,
    error = AppleSystemRedLight,
    onError = Color.White
)

private val DarkColorScheme = darkColorScheme(
    primary = AppleSystemBlueDark,
    onPrimary = Color.White,
    primaryContainer = AppleSystemBlueDark.copy(alpha = 0.20f),
    onPrimaryContainer = AppleSystemBlueDark,
    secondary = AppleSystemIndigoDark,
    onSecondary = Color.White,
    background = AppleGroupedBgDark,
    onBackground = AppleTextPrimaryDark,
    surface = AppleSurfaceDark,
    onSurface = AppleTextPrimaryDark,
    surfaceVariant = AppleSecondarySurfaceDark,
    onSurfaceVariant = AppleTextSecondaryDark,
    outline = AppleHairlineDark,
    error = AppleSystemRedDark,
    onError = Color.White
)

private val PhoneBridgeShapes = Shapes(
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(34.dp)
)

@Composable
fun PhoneBridgeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val semanticColors = if (darkTheme) DarkAppleSemanticColors else LightAppleSemanticColors

    CompositionLocalProvider(LocalAppleColors provides semanticColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = PhoneBridgeTypography,
            shapes = PhoneBridgeShapes,
            content = content
        )
    }
}
