package home.iptv.feature.guide

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import home.iptv.ui.theme.IptvColors
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.tv.material3.LocalContentColor

/** The grid needs focus and clicks without a separate animated TV Surface for every programme. */
@Composable
internal fun GuideProgramCell(onClick: () -> Unit, modifier: Modifier = Modifier,
                              onLongClick: (() -> Unit)?, enabled: Boolean = true, containerColor: Color,
                              focusedContainerColor: Color, contentKey: Any? = Unit,
                              content: @Composable BoxScope.() -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val pressedKeys = remember(contentKey) { mutableSetOf<Int>() }
    var longPressed by remember(contentKey) { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    CompositionLocalProvider(LocalContentColor provides if (focused) IptvColors.OnAccent else IptvColors.Text) {
        Box(modifier.clip(RoundedCornerShape(8.dp))
            .background(if (focused) focusedContainerColor else containerColor)
            .onFocusChanged {
                focused = it.isFocused
                if (!it.isFocused) pressedKeys.clear()
            }
            .focusProperties { canFocus = enabled }
            .onPreviewKeyEvent { event ->
                val key = event.nativeKeyEvent
                if (!enabled || key.keyCode !in listOf(KeyEvent.KEYCODE_DPAD_CENTER,
                        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER)) return@onPreviewKeyEvent false
                when (key.action) {
                    KeyEvent.ACTION_DOWN -> {
                        if (key.repeatCount == 0) {
                            pressedKeys += key.keyCode
                            longPressed = false
                            false
                        } else longPressed || key.keyCode !in pressedKeys
                    }
                    KeyEvent.ACTION_UP -> {
                        // Losing and regaining focus must not turn the same hold into a new click.
                        val pressed = pressedKeys.remove(key.keyCode)
                        longPressed || !pressed
                    }
                    else -> false
                }
            }
            .combinedClickable(interactionSource = null, indication = null, enabled = enabled,
                onLongClickLabel = "Действия канала", onLongClick = onLongClick?.let { action -> {
                    longPressed = true
                    // Cancel Foundation's pending click before another window receives the release.
                    focusManager.clearFocus()
                    action()
                } }, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 2.dp),
            contentAlignment = Alignment.CenterStart, content = content)
    }
}
