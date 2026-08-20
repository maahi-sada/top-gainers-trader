package app.paisa.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Money in, money out. Used everywhere a figure has a direction. */
object MoneyColors {
    val incomeLight = Color(0xFF15803D)
    val incomeDark = Color(0xFF4ADE80)
    val expenseLight = Color(0xFFDC2626)
    val expenseDark = Color(0xFFF87171)
    val warnLight = Color(0xFFB45309)
    val warnDark = Color(0xFFFBBF24)
}

private val LightColors = lightColorScheme(
    primary = Color(0xFF4F46E5),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEEF2FF),
    onPrimaryContainer = Color(0xFF1E1B4B),
    secondary = Color(0xFF0EA5E9),
    background = Color(0xFFF4F6FB),
    onBackground = Color(0xFF0F172A),
    surface = Color.White,
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFF1F5F9),
    onSurfaceVariant = Color(0xFF64748B),
    outline = Color(0xFFE2E8F0),
    error = Color(0xFFDC2626)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF818CF8),
    onPrimary = Color(0xFF10142B),
    primaryContainer = Color(0xFF1E2545),
    onPrimaryContainer = Color(0xFFE0E7FF),
    secondary = Color(0xFF38BDF8),
    background = Color(0xFF070B14),
    onBackground = Color(0xFFE8EDF7),
    surface = Color(0xFF0F172A),
    onSurface = Color(0xFFE8EDF7),
    surfaceVariant = Color(0xFF16203A),
    onSurfaceVariant = Color(0xFF8B9BB5),
    outline = Color(0xFF1F2B47),
    error = Color(0xFFF87171)
)

private val PaisaTypography = Typography(
    displaySmall = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    headlineSmall = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 15.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp)
)

@Composable
fun PaisaTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = PaisaTypography,
        content = content
    )
}

/** Green for money in, red for money out, in whichever theme is showing. */
@Composable
fun incomeColor(): Color = if (isSystemInDarkTheme()) MoneyColors.incomeDark else MoneyColors.incomeLight

@Composable
fun expenseColor(): Color = if (isSystemInDarkTheme()) MoneyColors.expenseDark else MoneyColors.expenseLight

@Composable
fun warnColor(): Color = if (isSystemInDarkTheme()) MoneyColors.warnDark else MoneyColors.warnLight
