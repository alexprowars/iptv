package home.iptv.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

@Composable
fun IPTVTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(
        primary = IptvColors.Accent, onPrimary = IptvColors.OnAccent,
        primaryContainer = IptvColors.Selected, onPrimaryContainer = IptvColors.Text,
        secondary = IptvColors.Accent, onSecondary = IptvColors.OnAccent,
        background = IptvColors.Background, onBackground = IptvColors.Text,
        surface = IptvColors.Surface, onSurface = IptvColors.Text,
        surfaceVariant = IptvColors.Elevated, onSurfaceVariant = IptvColors.Muted,
        border = IptvColors.Outline, error = IptvColors.Error
    ), typography = Typography) {
        CompositionLocalProvider(LocalContentColor provides IptvColors.Text, content = content)
    }
}
