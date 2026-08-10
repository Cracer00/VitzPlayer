package com.vitz.music.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import com.vitz.music.app.data.ThemeMode

/**
 * Палитра намеренно та же, что у Vitz Dashboard: приложения стоят на одном планшете в машине
 * и переключаются одно в другое, а разъезжающиеся оттенки в темноте читаются как сбой.
 * Динамические цвета Material You отключены по той же причине, что и в приборке.
 */
private val Ink = Color(0xFF04070A)

val NightBackground = Color(0xFF080A0D)
val NightSurface = Color(0xFF12161C)
val NightSurfaceVariant = Color(0xFF1B222B)
val NightOutline = Color(0xFF2A333F)
val NightOnSurface = Color(0xFFE6EAF0)
val NightOnSurfaceMuted = Color(0xFF9AA6B4)

val AccentTeal = Color(0xFF00D8C0)
val AccentAmber = Color(0xFFFFB020)
val AccentRed = Color(0xFFFF4D4D)
val AccentPink = Color(0xFFFF5C8A)

val DayBackground = Color(0xFFF4F6F9)
val DaySurface = Color(0xFFFFFFFF)
val DaySurfaceVariant = Color(0xFFE7ECF2)
val DayOutline = Color(0xFFC4CDD8)
val DayOnSurface = Color(0xFF10161D)
val DayPrimary = Color(0xFF00867A)

private val DarkColors = darkColorScheme(
    primary = AccentTeal,
    onPrimary = Ink,
    secondary = AccentAmber,
    onSecondary = Ink,
    tertiary = AccentPink,
    background = NightBackground,
    onBackground = NightOnSurface,
    surface = NightSurface,
    onSurface = NightOnSurface,
    surfaceVariant = NightSurfaceVariant,
    onSurfaceVariant = NightOnSurfaceMuted,
    outline = NightOutline,
    outlineVariant = NightOutline,
    error = AccentRed,
    onError = Ink,
    surfaceContainer = NightSurfaceVariant,
    surfaceContainerHigh = NightSurfaceVariant,
    surfaceContainerHighest = NightSurfaceVariant,
)

private val LightColors = lightColorScheme(
    primary = DayPrimary,
    secondary = AccentAmber,
    tertiary = AccentPink,
    background = DayBackground,
    onBackground = DayOnSurface,
    surface = DaySurface,
    onSurface = DayOnSurface,
    surfaceVariant = DaySurfaceVariant,
    outline = DayOutline,
    error = AccentRed,
)

val VitzMusicTypography = Typography(
    titleLarge = Typography().titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = Typography().titleMedium.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = Typography().labelLarge.copy(fontWeight = FontWeight.Medium),
)

/** Тема из настроек: в машине системная не годится, там почти всегда нужна тёмная. */
@Composable
fun VitzMusicTheme(
    mode: ThemeMode,
    content: @Composable () -> Unit,
) {
    val dark = when (mode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.AUTO -> isSystemInDarkTheme()
    }
    VitzMusicTheme(darkTheme = dark, content = content)
}

@Composable
fun VitzMusicTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = VitzMusicTypography,
        content = content,
    )
}
