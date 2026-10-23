package home.iptv.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.tv.material3.LocalContentColor
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

@Composable
fun IPTVTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(
        primary = Color(0xFF90CAF9), onPrimary = Color(0xFF101820),
        background = Color(0xFF121619), surface = Color(0xFF202427),
        onSurface = Color(0xFFF0F5F7), onBackground = Color(0xFFF0F5F7)
    ), typography = Typography) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground, content = content)
    }
}
