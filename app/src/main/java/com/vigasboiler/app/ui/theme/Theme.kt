package com.vigasboiler.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Neutral surfaces with vivid accents: a bright blue primary and a fresh teal tertiary (the
// hero card blends the two). Warm orange stays reserved for heat (burner, supply temperatures).
private val Light = lightColorScheme(
    primary = Color(0xFF1F6FEB),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD8E6FF),
    onPrimaryContainer = Color(0xFF00285C),
    secondary = Color(0xFF4F5F73),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDCE6F3),
    onSecondaryContainer = Color(0xFF0C1D2E),
    tertiary = Color(0xFF0E8A8A),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFC7F0EE),
    onTertiaryContainer = Color(0xFF00201F),
    error = Color(0xFFD32F2F),
    errorContainer = Color(0xFFFDE0DE),
    onErrorContainer = Color(0xFF410E0B),
    background = Color(0xFFF5F7FA),
    onBackground = Color(0xFF181C21),
    surface = Color(0xFFF5F7FA),
    onSurface = Color(0xFF181C21),
    surfaceVariant = Color(0xFFDFE4EB),
    onSurfaceVariant = Color(0xFF475261),
    outline = Color(0xFF727D8B),
    outlineVariant = Color(0xFFC5CDD7),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFEDF1F6),
    surfaceContainerHigh = Color(0xFFE6EBF1),
    surfaceContainerHighest = Color(0xFFDFE5EC),
    inverseSurface = Color(0xFF2D3238),
    inverseOnSurface = Color(0xFFEEF1F5),
    inversePrimary = Color(0xFF8AB8FF),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF8AB8FF),
    onPrimary = Color(0xFF00306B),
    primaryContainer = Color(0xFF1F4F9E),
    onPrimaryContainer = Color(0xFFDCE8FF),
    secondary = Color(0xFFBAC8DA),
    onSecondary = Color(0xFF243140),
    secondaryContainer = Color(0xFF34445A),
    onSecondaryContainer = Color(0xFFD8E4F4),
    tertiary = Color(0xFF5ED8CE),
    onTertiary = Color(0xFF003734),
    tertiaryContainer = Color(0xFF00504B),
    onTertiaryContainer = Color(0xFFA8F2EA),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF12151A),
    onBackground = Color(0xFFE4E7EC),
    surface = Color(0xFF12151A),
    onSurface = Color(0xFFE4E7EC),
    surfaceVariant = Color(0xFF3E4652),
    onSurfaceVariant = Color(0xFFC3CAD4),
    outline = Color(0xFF8D96A3),
    outlineVariant = Color(0xFF3E4652),
    surfaceContainerLowest = Color(0xFF0D1014),
    surfaceContainerLow = Color(0xFF1B1F26),
    surfaceContainer = Color(0xFF1F242C),
    surfaceContainerHigh = Color(0xFF282E37),
    surfaceContainerHighest = Color(0xFF323943),
    inverseSurface = Color(0xFFE4E7EC),
    inverseOnSurface = Color(0xFF2D3238),
    inversePrimary = Color(0xFF1F6FEB),
)

/** Colours outside the Material roles: status levels and hot/cold accents. */
@Immutable
data class ExtraColors(
    val warning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    val success: Color,
    val hot: Color,
    val cold: Color,
    /** One accent per bottom-bar tab (Overview, Heating, Statistics, Messages, More). */
    val tabs: List<Color>,
)

private val LightExtra = ExtraColors(
    warning = Color(0xFFB26A00),
    warningContainer = Color(0xFFFFE2B0),
    onWarningContainer = Color(0xFF2B1700),
    success = Color(0xFF1E8E3E),
    hot = Color(0xFFF06A1A),
    cold = Color(0xFF0891B2),
    tabs = listOf(Color(0xFF1F6FEB), Color(0xFFE8590C), Color(0xFF7C4DFF), Color(0xFF0E8A8A), Color(0xFF3F51B5)),
)

private val DarkExtra = ExtraColors(
    warning = Color(0xFFFFC25C),
    warningContainer = Color(0xFF684300),
    onWarningContainer = Color(0xFFFFDEA6),
    success = Color(0xFF7EDB8F),
    hot = Color(0xFFFF9F5A),
    cold = Color(0xFF67D4EA),
    tabs = listOf(Color(0xFF8AB8FF), Color(0xFFFFA270), Color(0xFFB9A6FF), Color(0xFF5ED8CE), Color(0xFF9FA8DA)),
)

val LocalExtraColors = staticCompositionLocalOf { LightExtra }

/** Accent of the tab being shown; section bars and card icons pick it up (see AppShell). */
val LocalAccent = compositionLocalOf { Color.Unspecified }

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(22.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

private val Base = Typography()

// Default Roboto with a clearer hierarchy: tight, medium-weight numbers and quiet labels.
private val AppTypography = Base.copy(
    displayLarge = Base.displayLarge.copy(fontWeight = FontWeight.Light, letterSpacing = (-1).sp),
    displayMedium = Base.displayMedium.copy(fontWeight = FontWeight.Normal, letterSpacing = (-0.5).sp),
    headlineMedium = Base.headlineMedium.copy(fontWeight = FontWeight.Medium),
    headlineSmall = Base.headlineSmall.copy(fontWeight = FontWeight.Medium),
    titleLarge = Base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = Base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = Base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
)

/** Small uppercase section caption. */
val SectionLabelStyle = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.1.sp)

/** The app's own neutral palette, following the system's light or dark setting. */
@Composable
fun ViessmannTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) Dark else Light
    androidx.compose.runtime.CompositionLocalProvider(LocalExtraColors provides if (darkTheme) DarkExtra else LightExtra) {
        MaterialTheme(colorScheme = colorScheme, typography = AppTypography, shapes = AppShapes, content = content)
    }
}

object AppColors {
    val extra: ExtraColors @Composable get() = LocalExtraColors.current

    /** The current tab's accent, or primary outside the tab shell. */
    val accent: Color @Composable get() = LocalAccent.current.takeIf { it != Color.Unspecified } ?: MaterialTheme.colorScheme.primary
}
