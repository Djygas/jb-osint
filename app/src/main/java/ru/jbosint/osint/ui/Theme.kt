package ru.jbosint.osint.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.jbosint.osint.R

// Палитра приложения: глубокий тёмный фон, акценты индиго → фиолетовый.
val JbBg = Color(0xFF0B0E14)
val JbBgSoft = Color(0xFF0E1220)
val JbSurface = Color(0xFF121722)
val JbSurfaceHigh = Color(0xFF171E2C)
val JbBorder = Color(0xFF232B3B)
val JbBorderSoft = Color(0xFF1B2231)
val JbAccent = Color(0xFF4F5BFF)
val JbAccent2 = Color(0xFF8B5CF6)
val JbText = Color(0xFFEDEFF5)
val JbMuted = Color(0xFF8A93A6)
val JbFaint = Color(0xFF5B6478)
val JbGreen = Color(0xFF22C55E)
val JbAmber = Color(0xFFF59E0B)
val JbRed = Color(0xFFEF4444)
val JbSlate = Color(0xFF7C8AA5)

val InterFamily = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold)
)

private val JbTypography = Typography(
    displaySmall = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 32.sp, lineHeight = 38.sp, letterSpacing = (-0.6).sp),
    headlineLarge = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 32.sp, letterSpacing = (-0.4).sp),
    headlineMedium = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = (-0.2).sp),
    headlineSmall = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 19.sp, lineHeight = 25.sp),
    titleLarge = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 24.sp),
    titleMedium = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp),
    titleSmall = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 18.sp),
    bodyLarge = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Normal, fontSize = 13.5.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 18.sp, letterSpacing = 0.1.sp),
    labelMedium = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.2.sp),
    labelSmall = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Medium, fontSize = 10.5.sp, lineHeight = 14.sp, letterSpacing = 0.4.sp)
)

private val JbColors = darkColorScheme(
    primary = JbAccent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF2A3199),
    onPrimaryContainer = Color(0xFFDDE0FF),
    secondary = JbAccent2,
    onSecondary = Color.White,
    background = JbBg,
    onBackground = JbText,
    surface = JbSurface,
    onSurface = JbText,
    surfaceVariant = JbSurfaceHigh,
    onSurfaceVariant = JbMuted,
    outline = JbBorder,
    outlineVariant = JbBorderSoft,
    error = JbRed,
    onError = Color.White
)

private val JbShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun JbTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = JbColors,
        typography = JbTypography,
        shapes = JbShapes,
        content = content
    )
}
