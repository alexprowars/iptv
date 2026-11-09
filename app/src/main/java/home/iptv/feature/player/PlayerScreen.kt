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
import home.iptv.ui.theme.IptvColors
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.graphics.Color
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
import home.iptv.core.model.ChannelProgram
import home.iptv.core.model.Program
import home.iptv.ui.RetainedDashboard
import home.iptv.ui.rememberVisibleValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
fun PlayerScreen(channel: Channel, recentChannels: List<Channel>,
                 loadPrograms: (List<String>, Long, Long) -> Flow<List<ChannelProgram>>,
                 onStep: (Int) -> Unit, onRecent: (Channel) -> Unit, onBack: () -> Unit,
                 controller: PlaybackController, active: Boolean,
                 initialPrograms: (List<String>, Long, Long) -> Map<String, List<Program>> = { _, _, _ -> emptyMap() },
                 guide: @Composable (Boolean, () -> Unit) -> Unit) {
    val state by controller.state.collectAsState()
    PlayerContent(channel, emptyList(), state, onStep, onRecent, onBack, controller::retry,
        recentChannels = recentChannels, guide = guide, active = active, infoPrograms = { now ->
            val recentIds = remember(recentChannels, channel.id) {
                recentChannels.filter { it.id != channel.id }.take(10).map { it.id }
            }
            // Reuse the guide's already loaded programmes on the first frame.
            // Live queries remain scoped to the visible panel.
            val initial = remember(channel.id, recentIds, now / 60_000) {
                val start = now / 3_600_000L * 3_600_000L
                initialPrograms(listOf(channel.id) + recentIds, start, start + 2 * 3_600_000L)
            }
            val programs by remember(loadPrograms, channel.id, now / 60_000) {
                loadPrograms(listOf(channel.id), now, now + 12 * 3_600_000L)
                    .map { rows -> rows.map { it.program } }.distinctUntilChanged()
            }.collectAsState(initial[channel.id].orEmpty())
            val recentPrograms by remember(loadPrograms, recentIds, now / 60_000) {
                if (recentIds.isEmpty()) flowOf(emptyMap()) else loadPrograms(recentIds, now, now + 1)
                    .map { rows -> rows.associate { it.channelId to it.program } }.distinctUntilChanged()
            }.collectAsState(recentIds.mapNotNull { id ->
                initial[id]?.lastOrNull { now in it.start until it.end }?.let { id to it }
            }.toMap())
            programs to recentPrograms
        }) {}
}

/** Video stays mounted while either overlay is open. All key events renew the inactivity timeout. */
@Composable
internal fun PlayerContent(channel: Channel, programs: List<Program>, state: PlaybackController.State,
                           onStep: (Int) -> Unit, onRecent: (Channel) -> Unit, onBack: () -> Unit,
                           onRetry: () -> Unit, recentChannels: List<Channel> = emptyList(),
                           recentPrograms: Map<String, Program> = emptyMap(),
                           guide: @Composable (Boolean, () -> Unit) -> Unit = { _, _ -> },
                           active: Boolean = true,
                           infoPrograms: @Composable (Long) -> Pair<List<Program>, Map<String, Program>> = { programs to recentPrograms },
                           video: @Composable () -> Unit) {
    var panel by remember(channel.id) { mutableStateOf(false) }
    var guideOpen by remember(channel.id) { mutableStateOf(false) }
    var activity by remember { mutableIntStateOf(0) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var infoStage by remember(channel.id) { mutableIntStateOf(0) }
    var infoData by remember(channel.id) { mutableStateOf(emptyList<Program>() to emptyMap<String, Program>()) }
    val focus = remember { FocusRequester() }
    val panelFocus = remember { FocusRequester() }
    val history = remember(recentChannels, channel.id) { recentChannels.filter { it.id != channel.id }.take(10) }
    LaunchedEffect(active, channel.id, panel, history.size) {
        if (!active || !panel || infoStage >= 4 + history.size) return@LaunchedEffect
        // Build the first visible panel a section per frame. Keeping its measured
        // content makes subsequent opens cheap, without any channel-switch warmup.
        now = System.currentTimeMillis()
        while (infoStage < 4 + history.size) {
            infoStage++
            withFrameNanos { }
        }
    }
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
            if (native.action == KeyEvent.ACTION_DOWN) { now = System.currentTimeMillis(); panel = true }
            true
        } else false
    }) {
        video()
        Box(Modifier.size(1.dp).focusRequester(focus).focusable(enabled = active).testTag("player-remote"))
        if (active && state.buffering && !panel && !guideOpen) Text("Открываем ${channel.name}…", Modifier.align(Alignment.TopStart).padding(32.dp).background(IptvColors.Surface.copy(alpha = .92f), RoundedCornerShape(12.dp)).padding(12.dp))
        if (infoStage > 0) {
            val showInfo = active && panel
            if (showInfo) infoData = infoPrograms(now)
            val infoNow = rememberVisibleValue(now, showInfo)
            val infoState = rememberVisibleValue(state, showInfo)
            val infoHistory = rememberVisibleValue(history, showInfo)
            RetainedDashboard(visible = showInfo) {
                val clock = remember(infoNow / 60_000) { home.iptv.ui.guideDate(infoNow) }
                Text(clock, Modifier.align(Alignment.TopEnd).padding(horizontal = 32.dp, vertical = 18.dp)
                    .background(IptvColors.Background.copy(alpha = .85f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                    color = IptvColors.Text, fontSize = 17.sp)
                PlayerInfoOverlay(channel, infoData.first, infoState, infoNow, panelFocus,
                    infoHistory, infoData.second, interactive = showInfo, buildStage = infoStage,
                    onRecent = { onRecent(it); panel = false }, onRetry = onRetry,
                    onGuide = { panel = false; guideOpen = true },
                    modifier = Modifier.align(Alignment.BottomCenter))
            }
        }
        // Programme data stays in the ViewModel cache; a closed grid does no layout
        // or composition work while the next channel starts. The video stays mounted.
        if (active && guideOpen) Box(Modifier.fillMaxSize()) {
            guide(true) { guideOpen = false }
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
