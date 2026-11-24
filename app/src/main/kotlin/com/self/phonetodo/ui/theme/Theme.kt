package com.self.phonetodo.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.self.phonetodo.model.ThemeMode

private val LightColors = lightColorScheme(
    primary = PineAccentLight,
    onPrimary = Color.White,
    primaryContainer = PineContainerLight,
    onPrimaryContainer = Color(0xFF0A473D),
    secondary = DialAmberLight,
    onSecondary = Color.White,
    background = VellumGround,
    onBackground = GraphiteInk,
    surface = PorcelainSurface,
    onSurface = GraphiteInk,
    surfaceVariant = VellumSubtle,
    onSurfaceVariant = PewterMuted,
    surfaceContainerLow = Color(0xFFF3F5F2),
    surfaceContainer = Color(0xFFECEFEA),
    surfaceContainerHigh = Color(0xFFE3E8E4),
    surfaceContainerHighest = Color(0xFFD9E0DC),
    outline = Color(0xFFB8C2BD),
    outlineVariant = HairlineLight,
    error = SignalRed,
)

private val DarkColors = darkColorScheme(
    primary = PineAccentDark,
    onPrimary = Color(0xFF07231E),
    primaryContainer = PineContainerDark,
    onPrimaryContainer = Color(0xFFC2EFE5),
    secondary = DialAmberDark,
    onSecondary = Color(0xFF281900),
    background = ObsidianGround,
    onBackground = StarlightInk,
    surface = ObsidianSurface,
    onSurface = StarlightInk,
    surfaceVariant = ObsidianSubtle,
    onSurfaceVariant = DialMuted,
    surfaceContainerLow = Color(0xFF151B20),
    surfaceContainer = Color(0xFF1A2227),
    surfaceContainerHigh = Color(0xFF222C33),
    surfaceContainerHighest = Color(0xFF2B3740),
    outline = Color(0xFF4F5D66),
    outlineVariant = HairlineDark,
    error = Color(0xFFF06C6C),
)

private val AmoledColors = DarkColors.copy(
    background = OledGround,
    surface = OledSurface,
    surfaceVariant = OledSubtle,
    surfaceContainerHighest = Color(0xFF29343D),
)

@Composable
fun PhoneToDoTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val colorScheme = when (themeMode) {
        ThemeMode.LIGHT -> LightColors
        ThemeMode.DARK -> DarkColors
        ThemeMode.AMOLED -> AmoledColors
        ThemeMode.SYSTEM -> if (systemDark) DarkColors else LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content,
    )
}
