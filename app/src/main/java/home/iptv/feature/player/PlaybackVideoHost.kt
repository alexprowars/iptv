package home.iptv.feature.player

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import kotlin.math.min

/** Keep the TextureView attached and at the same size when changing the viewing mode.
 * Layer transforms animate without resizing the decoder's output surface every frame. */
@Composable
internal fun PlaybackVideoHost(fullscreen: Boolean, visible: Boolean, previewBounds: Rect,
                               video: @Composable (Modifier) -> Unit) {
    val expansion by animateFloatAsState(if (fullscreen) 1f else 0f,
        tween(160, easing = FastOutSlowInEasing), label = "video-expansion")
    video(Modifier.fillMaxSize().graphicsLayer {
        val ready = fullscreen || !previewBounds.isEmpty
        val width = size.width.coerceAtLeast(1f)
        val height = size.height.coerceAtLeast(1f)
        val x = lerp(previewBounds.width / width, 1f, expansion)
        val y = lerp(previewBounds.height / height, 1f, expansion)
        transformOrigin = TransformOrigin(0f, 0f)
        scaleX = x
        scaleY = y
        translationX = lerp(previewBounds.left, 0f, expansion)
        translationY = lerp(previewBounds.top, 0f, expansion)
        alpha = if (visible && ready) 1f else 0f
        clip = true
        shape = RoundedCornerShape((7f * (1f - expansion) / min(x, y).coerceAtLeast(.01f)).dp)
    }.clearAndSetSemantics {})
}
