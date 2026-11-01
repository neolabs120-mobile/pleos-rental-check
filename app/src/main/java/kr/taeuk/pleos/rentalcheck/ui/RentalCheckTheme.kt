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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val lightColors = lightColorScheme(
    primary = Color(0xFF006C61), onPrimary = Color.White,
    primaryContainer = Color(0xFFDDF2EC), onPrimaryContainer = Color(0xFF064C44),
    secondary = Color(0xFF49665D), onSecondary = Color.White,
    secondaryContainer = Color(0xFFDDF2EC), onSecondaryContainer = Color(0xFF064C44),
    background = Color(0xFFF2F5F4), surface = Color.White,
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
    background = Color(0xFF171E24), surface = Color(0xFF202A32), surfaceVariant = Color(0xFF2C3943),
    surfaceContainerLow = Color(0xFF202A32), surfaceContainer = Color(0xFF253139),
    surfaceContainerHigh = Color(0xFF29363D), surfaceContainerHighest = Color(0xFF304148),
    onSurface = Color(0xFFEDF4F7), onBackground = Color(0xFFEDF4F7), onSurfaceVariant = Color(0xFFB2C4CE),
    outlineVariant = Color(0xFF40515D), error = Color(0xFFFFD194),
    errorContainer = Color(0xFF3A2D1E), onErrorContainer = Color(0xFFFFD194),
)
private val vehicleType = Typography(
    headlineSmall = TextStyle(fontSize = 30.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 20.sp, lineHeight = 30.sp),
    bodyMedium = TextStyle(fontSize = 18.sp, lineHeight = 26.sp),
    bodySmall = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    labelLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
)

// Give every vehicle screen the same readable type scale, restrained surfaces and rounded controls while honoring system night mode.
@Composable
fun RentalCheckTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColors else lightColors, typography = vehicleType,
        shapes = Shapes(small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(20.dp), large = RoundedCornerShape(28.dp)), content = content)
}
