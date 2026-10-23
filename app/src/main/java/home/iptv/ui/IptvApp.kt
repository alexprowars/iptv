package home.iptv.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.*
import home.iptv.IptvViewModel
import home.iptv.BuildConfig
import home.iptv.core.model.*
import home.iptv.feature.channels.ChannelsScreen
import home.iptv.feature.guide.GuideScreen
import home.iptv.feature.guide.GuidePosition
import home.iptv.feature.mapping.MappingScreen
import home.iptv.feature.player.PlayerScreen
import home.iptv.feature.player.rememberPlaybackController
import home.iptv.feature.player.PlayerGuideOverlay
import home.iptv.feature.player.PlaybackVideoHost
import home.iptv.feature.player.VideoSurface
import home.iptv.feature.search.SearchScreen
import home.iptv.feature.sources.*
import kotlinx.coroutines.delay

@Composable
fun IptvApp(vm: IptvViewModel = viewModel()) {
    val activity = LocalActivity.current
    val sources by vm.sources.collectAsState()
    val channels by vm.channels.collectAsState()
    val groups by vm.groups.collectAsState()
    val visible by vm.visible.collectAsState()
    val preferences by vm.preferences.collectAsState()
    val favorites by vm.favorites.collectAsState()
    val recentChannels by vm.recentChannels.collectAsState()
    val filter by vm.filter.collectAsState()
    val playback by vm.playback.collectAsState()
    val notice by vm.notice.collectAsState()
    var screen by rememberSaveable { mutableStateOf("channels") }
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    var previewBounds by remember { mutableStateOf(Rect.Zero) }
    var menuOpen by remember { mutableStateOf(false) }
    var menuRoot by remember { mutableStateOf(false) }
    val menuFocus = remember { FocusRequester() }
    var editor by remember { mutableStateOf<Source?>(null) }
    var attachTo by remember { mutableStateOf<String?>(null) }
    var actions by remember { mutableStateOf<Channel?>(null) }
    var mapping by remember { mutableStateOf<Channel?>(null) }
    var replacing by remember { mutableStateOf<Channel?>(null) }
    var description by remember { mutableStateOf<Pair<Channel, Program>?>(null) }
    var direct by remember { mutableStateOf(false) }
    var streamUrl by remember { mutableStateOf("") }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val currentNow = rememberUpdatedState(now)
    var guideStart by rememberSaveable { mutableLongStateOf(now / 3_600_000L * 3_600_000L) }
    val listState = rememberLazyListState()
    val guideState = rememberLazyListState()
    val guidePosition = rememberSaveable(saver = GuidePosition.Saver) { GuidePosition(vm.lastFocused, now) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(30_000) } }
    fun navigate(destination: String) {
        vm.interact(); screen = destination; editor = null; mapping = null; replacing = null; menuOpen = false
    }
    fun dashboardInputBlocked() = menuOpen || actions != null || description != null || notice != null || direct
    fun openNavigationMenu() { menuRoot = false; menuOpen = true }
    val dashboard = screen in listOf("channels", "guide") && editor == null && mapping == null && replacing == null
    LaunchedEffect(fullscreen, actions != null, menuOpen) {
        android.util.Log.i("IPTV_REMOTE", "fullscreen=$fullscreen channel_menu=${actions != null} navigation=$menuOpen")
    }
    val controller = rememberPlaybackController(playback?.channel, playback?.headers.orEmpty(),
        playback != null && (dashboard || fullscreen), vm::played)
    var alignedWithPlayback by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(playback?.channel?.id) {
        if (!alignedWithPlayback && playback != null) {
            guidePosition.channelState.value = playback!!.channel.id
            guidePosition.timeState.longValue = now
            alignedWithPlayback = true
        }
    }
    // Prepare the returning cursor while the dashboard is hidden, including channel changes.
    LaunchedEffect(fullscreen, dashboard, playback?.channel?.id, now) {
        if (fullscreen && dashboard) playback?.channel?.let { channel ->
            guidePosition.channelState.value = channel.id
            guidePosition.timeState.longValue = now
            guideStart = now / 3_600_000L * 3_600_000L
        }
    }
    fun watch(channel: Channel) {
        android.util.Log.i("IPTV_REMOTE", "watch channel_menu=${actions != null}")
        alignedWithPlayback = true
        vm.play(channel, visible)
        if (vm.playback.value?.channel?.id == channel.id) fullscreen = true
    }
    fun returnFromPlayer(destination: String = screen) {
        // Reuse the cursor already aligned with the dashboard clock. Updating it again would
        // invalidate the header and selected row during the video animation.
        vm.playback.value?.channel?.let { channel ->
            val prepared = dashboard && destination == screen && guidePosition.channelState.value == channel.id &&
                guidePosition.timeState.longValue == now && guideStart == now / 3_600_000L * 3_600_000L
            // Back may arrive before the hidden dashboard handles a channel change.
            if (!prepared) {
                val liveTime = System.currentTimeMillis()
                now = liveTime
                guidePosition.channelState.value = channel.id
                guidePosition.timeState.longValue = liveTime
                guideStart = liveTime / 3_600_000L * 3_600_000L
            }
            if (destination in listOf("channels", "guide") && channel.playlistId.isNotBlank() &&
                visible.none { it.id == channel.id }) vm.setFilter("all")
        }
        screen = destination
        fullscreen = false
    }
    fun program(channel: Channel, item: Program?) {
        android.util.Log.i("IPTV_REMOTE", "programme_click channel_menu=${actions != null}")
        if (dashboardInputBlocked()) return
        if (item == null || currentNow.value in item.start until item.end) watch(channel)
        else description = channel to item
    }
    BackHandler(enabled = !fullscreen && !dashboardInputBlocked()) {
        when { editor != null -> editor = null; mapping != null -> mapping = null; replacing != null -> replacing = null
            dashboard -> openNavigationMenu()
            else -> screen = "channels" }
    }
    @Composable
    fun NavigationSidebar(modifier: Modifier = Modifier, groupsOnly: Boolean = false) {
            Column(modifier.width(210.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Text(if (groupsOnly) "Каналы" else "IPTV", style = MaterialTheme.typography.headlineLarge, color = Accent)
                Text("Ваш эфир", color = Muted)
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(6.dp), modifier = Modifier.weight(1f)) {
                    item { TvButton("Все каналы", { vm.setFilter("all"); navigate("channels") }, Modifier.fillMaxWidth().focusRequester(menuFocus)) }
                    item { TvButton("★ Избранное", { vm.setFilter("favorites"); navigate("channels") }, Modifier.fillMaxWidth()) }
                    if (!groupsOnly) {
                        item { TvButton("Телепрограмма", { navigate("guide") }, Modifier.fillMaxWidth()) }
                        item { TvButton("Поиск", { vm.setFilter("search"); navigate("search") }, Modifier.fillMaxWidth()) }
                        item { TvButton("Источники", { navigate("sources") }, Modifier.fillMaxWidth()) }
                        item { TvButton("Настройки", { navigate("settings") }, Modifier.fillMaxWidth()) }
                    }
                    if (sources.any { it.kind == Source.PLAYLIST }) item { Text("ПЛЕЙЛИСТЫ", color = Muted, style = MaterialTheme.typography.labelSmall) }
                    items(sources.filter { it.kind == Source.PLAYLIST }, key = { "source:${it.id}" }) { source ->
                        TvButton(source.name, { vm.setFilter("playlist:${source.id}"); navigate("channels") }, Modifier.fillMaxWidth())
                    }
                    if (groups.isNotEmpty()) item { Text("ГРУППЫ", color = Muted, style = MaterialTheme.typography.labelSmall) }
                    items(groups, key = { "group:$it" }) { group -> TvButton(group, { vm.setFilter("group:$group"); navigate("channels") }, Modifier.fillMaxWidth()) }
                }
                Text("${time(now)}  ·  ${visible.size} каналов", color = Muted, style = MaterialTheme.typography.labelSmall)
            }
    }
    Box(Modifier.fillMaxSize().background(if (fullscreen) Color.Black else MaterialTheme.colorScheme.background)) {
        PlaybackVideoHost(fullscreen, visible = playback != null && (dashboard || fullscreen), previewBounds) { modifier ->
            VideoSurface(controller, fullscreen && preferences.fill, modifier)
        }
        val selection = playback
        if (selection != null && (dashboard || fullscreen)) Box(Modifier.fillMaxSize()
            .graphicsLayer { alpha = if (fullscreen) 1f else 0f }
            .then(if (fullscreen) Modifier else Modifier.clearAndSetSemantics {})) {
            val programs by remember(selection.channel.id, now / 60_000) { vm.dao.programs(listOf(selection.channel.id), now, now + 12 * 3_600_000L) }.collectAsState(emptyList())
            val recentIds = remember(recentChannels) { recentChannels.map { it.id } }
            val recentPrograms by remember(recentIds, now / 60_000) { vm.dao.programs(recentIds, now, now + 1) }.collectAsState(emptyList())
            val enriched = channels.find { it.id == selection.channel.id } ?: selection.channel
            PlayerScreen(enriched, programs.map { it.program }, recentChannels,
                recentPrograms.associate { it.channelId to it.program }, vm::step,
                onRecent = { channel ->
                    val queue = if (visible.any { it.id == channel.id }) visible else channels.also { vm.setFilter("all") }
                    vm.play(channel, queue)
                }, onBack = { returnFromPlayer() }, controller = controller, active = fullscreen,
                guide = { active, close -> PlayerGuideOverlay(vm, visible, enriched.id, now,
                    onWatch = { channel -> vm.play(channel, visible) }, onClose = close, active = active) })
        }
        if (dashboard) RetainedDashboard(visible = !fullscreen) {
            Box(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                GuideScreen(vm, visible, guideState, now, guideStart, { guideStart = it }, guidePosition,
                    onMenu = { openNavigationMenu() }, onActions = {
                        android.util.Log.i("IPTV_REMOTE", "programme_long_click fullscreen=$fullscreen")
                        actions = it
                    },
                    suspended = fullscreen || menuOpen || actions != null || description != null || notice != null,
                    playingId = selection?.channel?.id,
                    header = { channel, item -> DashboardHeader(controller, selection?.channel, channel, item, now,
                        onWatch = {
                            android.util.Log.i("IPTV_REMOTE", "preview_click channel_menu=${actions != null}")
                            if (selection != null && !dashboardInputBlocked()) fullscreen = true
                        }, onRetry = controller::retry,
                        onPreviewBounds = { if (!fullscreen) previewBounds = it }) },
                    onProgram = ::program)
            }
        } else if (!fullscreen) Row(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 22.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            NavigationSidebar()
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(when {
                        editor != null -> "Источники"
                        mapping != null -> "Сопоставление EPG"
                        replacing != null -> "Выберите замену"
                        screen == "sources" -> "Источники"
                        screen == "settings" -> "Настройки"
                        screen == "guide" -> "Телепрограмма"
                        screen == "search" -> "Поиск"
                        filter == "favorites" -> "Избранное"
                        filter.startsWith("playlist:") -> sources.find { it.id == filter.removePrefix("playlist:") }?.name ?: "Каналы"
                        filter.startsWith("group:") -> filter.removePrefix("group:")
                        else -> "Все каналы"
                    }, style = MaterialTheme.typography.headlineMedium)
                }
                Box(Modifier.weight(1f)) {
                    when {
                        editor != null -> SourceEditor(vm, editor!!, attachTo) { editor = null; attachTo = null }
                        mapping != null -> MappingScreen(vm, mapping!!) { mapping = null }
                        replacing != null -> LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(8.dp)) {
                            val old = replacing!!
                            item { Text("Избранное и ручная привязка «${old.name}» будут перенесены на выбранный канал.", color = Muted) }
                            items(channels.filter { it.playlistId == old.playlistId && it.available }, key = { it.id }) { candidate ->
                                TvButton(candidate.name, { vm.run { vm.graph.repository.replaceChannel(old.id, candidate.id); replacing = null } }, Modifier.fillMaxWidth())
                            }
                        }
                        screen == "sources" -> SourcesScreen(vm, { editor = it; attachTo = null }, { source, url -> editor = Source(kind = Source.EPG, name = "EPG · ${source.name}", location = url); attachTo = source.id })
                        screen == "guide" -> GuideScreen(vm, visible, guideState, now, guideStart, { guideStart = it }, guidePosition, onProgram = ::program)
                        screen == "search" -> SearchScreen(vm) { channel, item -> program(channel, item) }
                        screen == "settings" -> Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                            TvButton("Продолжать последний канал: ${if (preferences.autoResume) "да" else "нет"}", { vm.run { vm.graph.preferences.autoResume(!preferences.autoResume) } })
                            TvButton("Изображение: ${if (preferences.fill) "заполнить" else "вписать"}", { vm.run { vm.graph.preferences.fill(!preferences.fill) } })
                            TvButton("Открыть поток по URL", { direct = true })
                            Text("IPTV ${BuildConfig.VERSION_NAME}", color = Muted)
                        }
                        else -> key(filter) { ChannelsScreen(vm, visible, listState, now, { actions = it }, { screen = "sources" }) }
                    }
                }
            }
        }
    }
    if (menuOpen) Dialog(onDismissRequest = { menuOpen = false },
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BackHandler { if (menuRoot) activity?.finish() else menuRoot = true }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .45f)).onPreviewKeyEvent { event ->
            val key = event.nativeKeyEvent
            if (key.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (key.action == KeyEvent.ACTION_DOWN && key.repeatCount == 0) {
                    if (menuRoot) menuRoot = false else menuOpen = false
                }
                true
            } else if (key.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                if (key.action == KeyEvent.ACTION_DOWN && key.repeatCount == 0) menuRoot = true
                true
            } else false
        }) {
            NavigationSidebar(Modifier.background(Color(0xFF171C20)).padding(24.dp), groupsOnly = !menuRoot)
        }
        LaunchedEffect(menuRoot) { withFrameNanos { }; menuFocus.requestFocus() }
    }
    actions?.let { channel -> Modal(channel.name, { actions = null }) {
        Text("${channel.groupName.ifBlank { "Без группы" }} · ${sources.find { it.id == channel.playlistId }?.name.orEmpty()}", color = Muted)
        channel.unavailableReason?.let { Text(it, color = Muted) }
        TvButton(if (favorites.any { it.channelId == channel.id }) "Убрать из избранного" else "Добавить в избранное", { vm.favorite(channel.id); actions = null })
        if (favorites.any { it.channelId == channel.id }) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvButton("Выше в избранном", { vm.run { vm.graph.repository.moveFavorite(channel.id, -1) } })
            TvButton("Ниже в избранном", { vm.run { vm.graph.repository.moveFavorite(channel.id, 1) } })
        }
        TvButton("Сопоставление EPG", { mapping = channel; actions = null })
        if (!channel.available) TvButton("Выбрать замену канала", { replacing = channel; actions = null })
        Text("tvg-id: ${channel.tvgId.ifBlank { "не задан" }}", color = Muted)
        TvButton("Закрыть", { actions = null })
    } }
    description?.let { (channel, item) -> Modal(item.title, { description = null }) {
        Text("${channel.name} · ${dateTime(item.start)}–${time(item.end)}", color = Muted)
        LazyColumn(Modifier.heightIn(max = 220.dp)) { item { Text(item.description.ifBlank { "Описание отсутствует" }) } }
        Text("Доступен только прямой эфир", color = Muted)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            TvButton("Смотреть канал в эфире", { watch(channel); description = null })
            TvButton("Закрыть", { description = null })
        }
    } }
    if (direct) Modal("Открыть поток", { direct = false }) {
        Input("HTTP(S) URL · HLS или MPEG-TS", streamUrl, { streamUrl = it })
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            TvButton("Открыть", { vm.openStream(streamUrl.trim()); if (vm.playback.value != null) { direct = false; fullscreen = true } })
            TvButton("Отмена", { direct = false })
        }
    }
    notice?.let { message -> Modal("IPTV", { vm.notice.value = null }) { Text(message); TvButton("Понятно", { vm.notice.value = null }) } }
}
