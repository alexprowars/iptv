package home.iptv.feature.player

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Text
import home.iptv.core.model.Channel
import home.iptv.core.model.Program
import kotlinx.coroutines.delay

@Composable
fun PlayerScreen(channel: Channel, programs: List<Program>,
                 recentChannels: List<Channel>, recentPrograms: Map<String, Program>,
                 onStep: (Int) -> Unit, onRecent: (Channel) -> Unit, onBack: () -> Unit,
                 controller: PlaybackController, active: Boolean,
                 guide: @Composable (Boolean, () -> Unit) -> Unit) {
    val state by controller.state.collectAsState()
    PlayerContent(channel, programs, state, onStep, onRecent, onBack, controller::retry,
        recentChannels, recentPrograms, guide, active = active) {}
}

/** Video stays mounted while either overlay is open. All key events renew the inactivity timeout. */
@Composable
internal fun PlayerContent(channel: Channel, programs: List<Program>, state: PlaybackController.State,
                           onStep: (Int) -> Unit, onRecent: (Channel) -> Unit, onBack: () -> Unit,
                           onRetry: () -> Unit, recentChannels: List<Channel> = emptyList(),
                           recentPrograms: Map<String, Program> = emptyMap(),
                           guide: @Composable (Boolean, () -> Unit) -> Unit = { _, _ -> },
                           active: Boolean = true, video: @Composable () -> Unit) {
    var panel by remember(channel.id) { mutableStateOf(false) }
    var guideOpen by remember(channel.id) { mutableStateOf(false) }
    var guidePrepared by remember(channel.id) { mutableStateOf(false) }
    LaunchedEffect(channel.id, state.playing) {
        if (state.playing && !guidePrepared) { delay(300); guidePrepared = true }
    }
    var activity by remember { mutableIntStateOf(0) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val focus = remember { FocusRequester() }
    val panelFocus = remember { FocusRequester() }
    LaunchedEffect(active) { if (!active) { panel = false; guideOpen = false } }
    LaunchedEffect(state.error, active) { if (active && state.error != null) panel = true }
    LaunchedEffect(panel, guideOpen) {
        if (panel || guideOpen) while (true) { now = System.currentTimeMillis(); delay(1000) }
    }
    LaunchedEffect(panel, guideOpen, activity) {
        if (panel && !guideOpen) { delay(10_000); panel = false }
    }
    BackHandler(enabled = active) { when { guideOpen -> guideOpen = false; panel -> panel = false; else -> onBack() } }
    LaunchedEffect(active, panel, guideOpen) {
        if (active && !guideOpen && !panel) { withFrameNanos { }; focus.requestFocus() }
    }
    Box(Modifier.fillMaxSize().onPreviewKeyEvent { event ->
        if (!active) return@onPreviewKeyEvent false
        val native = event.nativeKeyEvent
        activity++
        val code = native.keyCode
        if (code == KeyEvent.KEYCODE_CHANNEL_UP || code == KeyEvent.KEYCODE_CHANNEL_DOWN) {
            if (native.action == KeyEvent.ACTION_DOWN) onStep(if (code == KeyEvent.KEYCODE_CHANNEL_UP) 1 else -1)
            true
        } else if (!panel && !guideOpen && code in listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER)) {
            if (native.action == KeyEvent.ACTION_DOWN) panel = true
            true
        } else false
    }) {
        video()
        Box(Modifier.size(1.dp).focusRequester(focus).focusable(enabled = active).testTag("player-remote"))
        if (state.buffering && !panel && !guideOpen) Text("Открываем ${channel.name}…", Modifier.align(Alignment.TopStart).padding(32.dp).background(Color.Black.copy(alpha = .7f)).padding(12.dp))
        if (panel) {
            Text(home.iptv.ui.guideDate(now), Modifier.align(Alignment.TopEnd).padding(horizontal = 32.dp, vertical = 18.dp),
                color = Color.White, fontSize = 17.sp)
            PlayerInfoOverlay(channel, programs, state, now, panelFocus,
                recentChannels.filter { it.id != channel.id }.take(10), recentPrograms,
                onRecent = { onRecent(it); panel = false }, onRetry = onRetry,
                onGuide = { panel = false; guidePrepared = true; guideOpen = true },
                modifier = Modifier.align(Alignment.BottomCenter))
        }
        if (guidePrepared) Box(Modifier.fillMaxSize()
            .graphicsLayer { alpha = if (guideOpen) 1f else 0f }
            .then(if (guideOpen) Modifier else Modifier.clearAndSetSemantics {})) {
            guide(active && guideOpen) { guideOpen = false }
        }
    }
}

/** One session is shared by the guide preview and fullscreen, so opening either keeps the stream. */
@Composable
fun rememberPlaybackController(channel: Channel?, headers: String, enabled: Boolean,
                               onPlayed: (String) -> Unit): PlaybackController {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val played by rememberUpdatedState(onPlayed)
    val controller = remember { PlaybackController(context, scope, { played(it) }) }
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(channel?.id, headers) { channel?.let { controller.select(it, headers) } }
    DisposableEffect(owner, controller, enabled) {
        val observer = LifecycleEventObserver { _, event ->
            if (enabled && event == Lifecycle.Event.ON_START) controller.start()
            if (event == Lifecycle.Event.ON_STOP) controller.stop()
        }
        owner.lifecycle.addObserver(observer)
        if (enabled && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) controller.start()
        onDispose { owner.lifecycle.removeObserver(observer); controller.stop() }
    }
    return controller
}

@OptIn(UnstableApi::class)
@Composable
fun VideoSurface(controller: PlaybackController, fill: Boolean, modifier: Modifier = Modifier) {
    val state by controller.state.collectAsState()
    AndroidView(factory = { android.view.LayoutInflater.from(it).inflate(home.iptv.R.layout.video_surface, android.widget.FrameLayout(it), false) as PlayerView }, modifier = modifier, update = { view ->
        view.player = state.player
        view.resizeMode = if (fill) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
        view.keepScreenOn = state.playing
    }, onRelease = { it.player = null; it.keepScreenOn = false })
}
