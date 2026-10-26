package home.iptv.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.focusGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics

/** Keep a mounted, hidden screen on its last visible inputs until it opens again. */
@Composable
internal fun <T> rememberVisibleValue(value: T, visible: Boolean): T {
    val retained = remember { mutableStateOf(value) }
    if (visible) retained.value = value
    return retained.value
}

/** Preserve layout and drawing layers; the hidden subtree is clipped outside the input viewport. */
@Composable
internal fun RetainedDashboard(visible: Boolean, content: @Composable BoxScope.() -> Unit) {
    val shown = rememberUpdatedState(visible)
    Box(Modifier.fillMaxSize().clipToBounds()
        .then(if (visible) Modifier else Modifier.clearAndSetSemantics {})
        .focusProperties { onEnter = { if (!shown.value) cancelFocusChange() } }.focusGroup()) {
        Box(Modifier.fillMaxSize().graphicsLayer {
            alpha = if (shown.value) 1f else 0f
            translationX = if (shown.value) 0f else -size.width
        }, content = content)
    }
}
