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
import home.iptv.ui.theme.IptvColors
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
    fun watch(channel: Channel, expand: Boolean = true) {
        android.util.Log.i("IPTV_REMOTE", "watch channel_menu=${actions != null}")
        alignedWithPlayback = true
        vm.play(channel, visible)
        if (expand && vm.playback.value?.channel?.id == channel.id) fullscreen = true
    }
    fun returnFromPlayer(destination: String = screen) {
        // Restore the cursor on return; channel switches leave the hidden grid untouched.
        vm.playback.value?.channel?.let { channel ->
            val prepared = dashboard && destination == screen && guidePosition.channelState.value == channel.id &&
                guidePosition.timeState.longValue == now && guideStart == now / 3_600_000L * 3_600_000L
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
        if (item == null || currentNow.value in item.start until item.end) {
            watch(channel, expand = vm.playback.value?.channel?.id == channel.id)
        }
        else description = channel to item
    }
    BackHandler(enabled = !fullscreen && !dashboardInputBlocked()) {
        when { editor != null -> editor = null; mapping != null -> mapping = null; replacing != null -> replacing = null
            dashboard -> openNavigationMenu()
            else -> screen = "channels" }
    }
    @Composable
    fun NavigationSidebar(modifier: Modifier = Modifier, groupsOnly: Boolean = false) {
        val playlistCounts = remember(channels) { channels.groupingBy { it.playlistId }.eachCount() }
        val groupCounts = remember(channels) { channels.groupingBy { it.groupName }.eachCount() }
        val showingChannels = screen == "channels" || (groupsOnly && screen == "guide")
        Column(modifier.width(264.dp).fillMaxHeight()
            .background(IptvColors.Surface, androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
            .padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            MenuHeader(groupsOnly)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp),
                contentPadding = PaddingValues(vertical = 4.dp), modifier = Modifier.weight(1f)) {
                item { MenuHeading("Смотреть") }
                item {
                    MenuEntry("Все каналы", MenuIcon.Channels, showingChannels && filter == "all",
                        { vm.setFilter("all"); navigate("channels") }, Modifier.focusRequester(menuFocus), channels.size)
                }
                item {
                    MenuEntry("Избранное", MenuIcon.Favorite, showingChannels && filter == "favorites",
                        { vm.setFilter("favorites"); navigate("channels") }, count = favorites.size)
                }
                if (!groupsOnly) {
                    item { MenuEntry("Телепрограмма", MenuIcon.Guide, screen == "guide", { navigate("guide") }) }
                    item { MenuEntry("Поиск", MenuIcon.Search, screen == "search", { vm.setFilter("search"); navigate("search") }) }
                    item { MenuHeading("Приложение") }
                    item { MenuEntry("Источники", MenuIcon.Sources, screen == "sources", { navigate("sources") }) }
                    item { MenuEntry("Настройки", MenuIcon.Settings, screen == "settings", { navigate("settings") }) }
                }
                if (sources.any { it.kind == Source.PLAYLIST }) item { MenuHeading("Плейлисты") }
                items(sources.filter { it.kind == Source.PLAYLIST }, key = { "source:${it.id}" }) { source ->
                    MenuEntry(source.name, MenuIcon.Playlist, showingChannels && filter == "playlist:${source.id}",
                        { vm.setFilter("playlist:${source.id}"); navigate("channels") }, count = playlistCounts[source.id] ?: 0)
                }
                if (groups.isNotEmpty()) item { MenuHeading("Категории") }
                items(groups, key = { "group:$it" }) { group ->
                    MenuEntry(group, MenuIcon.Group, showingChannels && filter == "group:$group",
                        { vm.setFilter("group:$group"); navigate("channels") }, count = groupCounts[group] ?: 0)
                }
            }
            MenuFooter(time(now), visible.size, if (!menuOpen) "→ К разделу    ·    Назад — к каналам"
                else if (groupsOnly) "← Главное меню    ·    → К просмотру"
                else "→ К каналам    ·    Назад — выход")
        }
    }
    val dashboardChannels = rememberVisibleValue(visible, !fullscreen)
    val dashboardPlaying = rememberVisibleValue(playback?.channel, !fullscreen)
    val dashboardNow = rememberVisibleValue(now, !fullscreen)
    Box(Modifier.fillMaxSize().background(if (fullscreen) Color.Black else MaterialTheme.colorScheme.background)) {
        PlaybackVideoHost(fullscreen, visible = playback != null && (dashboard || fullscreen), previewBounds) { modifier ->
            VideoSurface(controller, fullscreen && preferences.fill, modifier)
        }
        val selection = playback
        if (selection != null && (dashboard || fullscreen)) Box(Modifier.fillMaxSize()
            .graphicsLayer { alpha = if (fullscreen) 1f else 0f }
            .then(if (fullscreen) Modifier else Modifier.clearAndSetSemantics {})) {
            val enriched = channels.find { it.id == selection.channel.id } ?: selection.channel
            PlayerScreen(enriched, recentChannels, vm.dao::programs, vm::step,
                onRecent = { channel ->
                    val queue = if (visible.any { it.id == channel.id }) visible else channels.also { vm.setFilter("all") }
                    vm.play(channel, queue)
                }, onBack = { returnFromPlayer() }, controller = controller, active = fullscreen,
                initialPrograms = { ids, from, to -> vm.guideCache.snapshot(ids, from, to).programs },
                guide = { active, close -> PlayerGuideOverlay(vm, visible, enriched.id, now,
                    onWatch = { channel -> vm.play(channel, visible) }, onClose = close, active = active) })
        }
        if (dashboard) RetainedDashboard(visible = !fullscreen) {
            Box(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                GuideScreen(vm, dashboardChannels, guideState, dashboardNow, guideStart, { guideStart = it }, guidePosition,
                    onMenu = { openNavigationMenu() }, onActions = {
                        android.util.Log.i("IPTV_REMOTE", "programme_long_click fullscreen=$fullscreen")
                        actions = it
                    },
                    suspended = fullscreen || menuOpen || actions != null || description != null || notice != null,
                    playingId = dashboardPlaying?.id,
                    header = { channel, item -> DashboardHeader(controller, dashboardPlaying, channel, item, dashboardNow,
                        onWatch = {
                            android.util.Log.i("IPTV_REMOTE", "preview_click channel_menu=${actions != null}")
                            if (dashboardPlaying != null && !dashboardInputBlocked()) fullscreen = true
                        }, onRetry = controller::retry,
                        onPreviewBounds = { if (!fullscreen) previewBounds = it }) },
                    onProgram = ::program)
            }
        } else if (!fullscreen) Row(Modifier.fillMaxSize().padding(SidebarOuterPadding), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
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
                        screen == "settings" -> LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(8.dp)) {
                            item { SectionCard {
                                Text("Просмотр", style = MaterialTheme.typography.titleLarge)
                                SettingRow("Продолжать последний канал", "Возобновлять эфир при запуске приложения",
                                    if (preferences.autoResume) "Включено" else "Выключено",
                                    { vm.run { vm.graph.preferences.autoResume(!preferences.autoResume) } })
                                SettingRow("Размер изображения", "Как видео размещается на экране",
                                    if (preferences.fill) "Заполнить" else "Вписать",
                                    { vm.run { vm.graph.preferences.fill(!preferences.fill) } })
                            } }
                            item { SectionCard {
                                Text("Прямой эфир", style = MaterialTheme.typography.titleLarge)
                                Text("Откройте отдельный поток по ссылке.", color = Muted)
                                TvButton("Открыть поток по URL", { direct = true })
                            } }
                            item { Text("IPTV ${BuildConfig.VERSION_NAME}", color = Muted,
                                style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(8.dp)) }
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
            NavigationSidebar(Modifier.padding(SidebarOuterPadding), groupsOnly = !menuRoot)
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
