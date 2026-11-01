package kr.taeuk.pleos.rentalcheck.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val lightColors = lightColorScheme(
    primary = Color(0xFF006C61), onPrimary = Color.White,
    primaryContainer = Color(0xFFDDF2EC), onPrimaryContainer = Color(0xFF064C44),
    secondary = Color(0xFF49665D), onSecondary = Color.White,
    secondaryContainer = Color(0xFFDDF2EC), onSecondaryContainer = Color(0xFF064C44),
    background = Color(0xFFF1F5F3), surface = Color.White,
    surfaceContainerLow = Color(0xFFF7FAF8), surfaceContainer = Color(0xFFF0F5F2),
    surfaceContainerHigh = Color(0xFFF7FAF8), surfaceContainerHighest = Color(0xFFE1EBE5),
    surfaceVariant = Color(0xFFEBF0ED), onSurface = Color(0xFF182F2C), onBackground = Color(0xFF182F2C),
    onSurfaceVariant = Color(0xFF536963), outlineVariant = Color(0xFFD6E2DC),
    error = Color(0xFF80500B), errorContainer = Color(0xFFFFF0D6), onErrorContainer = Color(0xFF634008),
)
private val darkColors = darkColorScheme(
    primary = Color(0xFF8DDED1), onPrimary = Color(0xFF102D29),
    primaryContainer = Color(0xFF193D37), onPrimaryContainer = Color(0xFFB8F3E4),
    secondary = Color(0xFFB5CEC3), onSecondary = Color(0xFF102D29),
    secondaryContainer = Color(0xFF28473D), onSecondaryContainer = Color(0xFFCEEBDE),
    background = Color(0xFF11221F), surface = Color(0xFF192D28), surfaceVariant = Color(0xFF263C35),
    surfaceContainerLow = Color(0xFF202A32), surfaceContainer = Color(0xFF253139),
    surfaceContainerHigh = Color(0xFF29363D), surfaceContainerHighest = Color(0xFF304148),
    onSurface = Color(0xFFEDF4F7), onBackground = Color(0xFFEDF4F7), onSurfaceVariant = Color(0xFFB2C4CE),
    outlineVariant = Color(0xFF40515D), error = Color(0xFFFFD194),
    errorContainer = Color(0xFF3A2D1E), onErrorContainer = Color(0xFFFFD194),
)
// Use one font family and predictable baselines for Korean copy, metrics and buttons instead of inheriting unrelated Material defaults.
private fun vehicleText(size: Int, height: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(
    fontFamily = FontFamily.SansSerif, fontSize = size.sp, lineHeight = height.sp, fontWeight = weight,
    platformStyle = PlatformTextStyle(includeFontPadding = false))

private val vehicleType = Typography(
    displaySmall = vehicleText(42, 50, FontWeight.SemiBold),
    headlineSmall = vehicleText(32, 40, FontWeight.SemiBold),
    titleLarge = vehicleText(26, 34, FontWeight.SemiBold),
    titleMedium = vehicleText(22, 30, FontWeight.SemiBold),
    titleSmall = vehicleText(18, 26, FontWeight.Medium),
    bodyLarge = vehicleText(20, 30), bodyMedium = vehicleText(20, 30), bodySmall = vehicleText(18, 28),
    labelLarge = vehicleText(18, 24, FontWeight.SemiBold),
    labelMedium = vehicleText(16, 24, FontWeight.Medium), labelSmall = vehicleText(16, 24),
)

// Give every vehicle screen the same readable type scale, restrained surfaces and rounded controls while honoring system night mode.
@Composable
fun RentalCheckTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColors else lightColors, typography = vehicleType,
        shapes = Shapes(small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(20.dp), large = RoundedCornerShape(28.dp)), content = content)
}
