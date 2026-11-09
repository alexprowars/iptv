package home.iptv.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Typography

private fun textStyle(size: Int, height: Int, weight: FontWeight = FontWeight.Normal) =
    TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = weight,
        fontSize = size.sp, lineHeight = height.sp)

val Typography = Typography(
    headlineLarge = textStyle(32, 38, FontWeight.SemiBold),
    headlineMedium = textStyle(28, 34, FontWeight.SemiBold),
    headlineSmall = textStyle(24, 30, FontWeight.SemiBold),
    titleLarge = textStyle(22, 28, FontWeight.SemiBold),
    titleMedium = textStyle(18, 24, FontWeight.Medium),
    titleSmall = textStyle(16, 22, FontWeight.Medium),
    bodyLarge = textStyle(16, 24),
    bodyMedium = textStyle(14, 20),
    bodySmall = textStyle(12, 18),
    labelLarge = textStyle(14, 20, FontWeight.SemiBold),
    labelMedium = textStyle(12, 18, FontWeight.Medium),
    labelSmall = textStyle(11, 16, FontWeight.Medium)
)
