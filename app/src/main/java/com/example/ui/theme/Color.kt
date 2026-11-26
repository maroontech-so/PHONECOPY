package com.example.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// Apple-inspired Glassmorphic Palette
val AppleSystemBlueLight = Color(0xFF007AFF)
val AppleSystemBlueDark = Color(0xFF0A84FF)

val AppleSystemGreenLight = Color(0xFF34C759)
val AppleSystemGreenDark = Color(0xFF30D158)

val AppleSystemOrangeLight = Color(0xFFFF9500)
val AppleSystemOrangeDark = Color(0xFFFF9F0A)

val AppleSystemRedLight = Color(0xFFFF3B30)
val AppleSystemRedDark = Color(0xFFFF453A)

val AppleSystemIndigoLight = Color(0xFF5856D6)
val AppleSystemIndigoDark = Color(0xFF5E5CE6)

val AppleGroupedBgLight = Color(0xFFF3F5FA)
val AppleSurfaceLight = Color(0xFFFFFFFF)
val AppleSecondarySurfaceLight = Color(0xFFE6EAF3)
val AppleHairlineLight = Color(0xFFD0D5E2)
val AppleTextPrimaryLight = Color(0xFF0A0A0D)
val AppleTextSecondaryLight = Color(0xFF6B7080)

val AppleGroupedBgDark = Color(0xFF06070B)
val AppleSurfaceDark = Color(0xFF17181E)
val AppleSecondarySurfaceDark = Color(0xFF23252E)
val AppleHairlineDark = Color(0xFF343746)
val AppleTextPrimaryDark = Color(0xFFF8F9FC)
val AppleTextSecondaryDark = Color(0xFF969BA8)

@Immutable
data class AppleSemanticColors(
    val success: Color,
    val warning: Color,
    val danger: Color,
    val indigo: Color,
    val hairline: Color,
    val subtleText: Color,
    val pillSurface: Color,
    val glassFill: Color,
    val glassFillElevated: Color,
    val glassHighlight: Color,
    val glassEdge: Color,
    val ambientTop: Color,
    val ambientBottom: Color,
    val isDark: Boolean
)

val LightAppleSemanticColors = AppleSemanticColors(
    success = AppleSystemGreenLight,
    warning = AppleSystemOrangeLight,
    danger = AppleSystemRedLight,
    indigo = AppleSystemIndigoLight,
    hairline = AppleHairlineLight,
    subtleText = AppleTextSecondaryLight,
    pillSurface = Color(0xFFE3E7F2).copy(alpha = 0.72f),
    glassFill = Color.White.copy(alpha = 0.74f),
    glassFillElevated = Color.White.copy(alpha = 0.88f),
    glassHighlight = Color.White.copy(alpha = 0.92f),
    glassEdge = Color(0xFFB8C2D8).copy(alpha = 0.35f),
    ambientTop = Color(0xFF007AFF).copy(alpha = 0.13f),
    ambientBottom = Color(0xFF5856D6).copy(alpha = 0.09f),
    isDark = false
)

val DarkAppleSemanticColors = AppleSemanticColors(
    success = AppleSystemGreenDark,
    warning = AppleSystemOrangeDark,
    danger = AppleSystemRedDark,
    indigo = AppleSystemIndigoDark,
    hairline = AppleHairlineDark,
    subtleText = AppleTextSecondaryDark,
    pillSurface = Color(0xFF232632).copy(alpha = 0.75f),
    glassFill = Color(0xFF1A1C24).copy(alpha = 0.68f),
    glassFillElevated = Color(0xFF222531).copy(alpha = 0.84f),
    glassHighlight = Color.White.copy(alpha = 0.20f),
    glassEdge = Color.White.copy(alpha = 0.06f),
    ambientTop = Color(0xFF0A84FF).copy(alpha = 0.18f),
    ambientBottom = Color(0xFF5E5CE6).copy(alpha = 0.14f),
    isDark = true
)

val LocalAppleColors = staticCompositionLocalOf { LightAppleSemanticColors }
