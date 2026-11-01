package si.vicare.customapp.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Warm "flame" palette: a burnt-orange primary, neutral warm surfaces, and a cool blue tertiary
// used for cold values (outside temperature) so hot and cold read at a glance.
private val Light = lightColorScheme(
    primary = Color(0xFFB0410E),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDBCC),
    onPrimaryContainer = Color(0xFF390C00),
    secondary = Color(0xFF77574A),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFDBCF),
    onSecondaryContainer = Color(0xFF2C160C),
    tertiary = Color(0xFF2F6385),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFCAE6FF),
    onTertiaryContainer = Color(0xFF001E30),
    error = Color(0xFFBA1A1A),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFFF8F5),
    onBackground = Color(0xFF221A16),
    surface = Color(0xFFFFF8F5),
    onSurface = Color(0xFF221A16),
    surfaceVariant = Color(0xFFF4DED5),
    onSurfaceVariant = Color(0xFF53433D),
    outline = Color(0xFF85736C),
    outlineVariant = Color(0xFFD8C2B9),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFF1EB),
    surfaceContainer = Color(0xFFFCEAE3),
    surfaceContainerHigh = Color(0xFFF6E5DD),
    surfaceContainerHighest = Color(0xFFF0DFD7),
    inverseSurface = Color(0xFF382E2A),
    inverseOnSurface = Color(0xFFFFEDE6),
    inversePrimary = Color(0xFFFFB595),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFFFB595),
    onPrimary = Color(0xFF5B1A00),
    primaryContainer = Color(0xFF8A2E00),
    onPrimaryContainer = Color(0xFFFFDBCC),
    secondary = Color(0xFFE7BDAD),
    onSecondary = Color(0xFF442A1F),
    secondaryContainer = Color(0xFF5D4034),
    onSecondaryContainer = Color(0xFFFFDBCF),
    tertiary = Color(0xFF9ACCF4),
    onTertiary = Color(0xFF00344F),
    tertiaryContainer = Color(0xFF0F4B6B),
    onTertiaryContainer = Color(0xFFCAE6FF),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF1A120E),
    onBackground = Color(0xFFF0DFD7),
    surface = Color(0xFF1A120E),
    onSurface = Color(0xFFF0DFD7),
    surfaceVariant = Color(0xFF53433D),
    onSurfaceVariant = Color(0xFFD8C2B9),
    outline = Color(0xFFA08D85),
    outlineVariant = Color(0xFF53433D),
    surfaceContainerLowest = Color(0xFF140D0A),
    surfaceContainerLow = Color(0xFF221A16),
    surfaceContainer = Color(0xFF271E1A),
    surfaceContainerHigh = Color(0xFF322824),
    surfaceContainerHighest = Color(0xFF3D332E),
    inverseSurface = Color(0xFFF0DFD7),
    inverseOnSurface = Color(0xFF382E2A),
    inversePrimary = Color(0xFFB0410E),
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
)

private val LightExtra = ExtraColors(
    warning = Color(0xFF8A5A00),
    warningContainer = Color(0xFFFFDEA6),
    onWarningContainer = Color(0xFF2B1700),
    success = Color(0xFF2E6B30),
    hot = Color(0xFFC2410C),
    cold = Color(0xFF2F6385),
)

private val DarkExtra = ExtraColors(
    warning = Color(0xFFFFBA3B),
    warningContainer = Color(0xFF684300),
    onWarningContainer = Color(0xFFFFDEA6),
    success = Color(0xFF8FD88B),
    hot = Color(0xFFFF9E70),
    cold = Color(0xFF9ACCF4),
)

val LocalExtraColors = staticCompositionLocalOf { LightExtra }

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
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
val SectionLabelStyle = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp)

@Composable
fun ViessmannTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> Dark
        else -> Light
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalExtraColors provides if (darkTheme) DarkExtra else LightExtra) {
        MaterialTheme(colorScheme = colorScheme, typography = AppTypography, shapes = AppShapes, content = content)
    }
}

object AppColors {
    val extra: ExtraColors @Composable get() = LocalExtraColors.current
}
