package home.iptv.feature.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import home.iptv.ui.theme.IptvColors
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import home.iptv.IptvViewModel
import home.iptv.core.model.Channel
import home.iptv.core.model.Program
import home.iptv.feature.guide.GuidePosition
import home.iptv.feature.guide.GuideScreen
import home.iptv.feature.guide.guideTime
import home.iptv.ui.*

/** Only Compose content: the fullscreen video underneath remains attached to the same player. */
@Composable
fun PlayerGuideOverlay(vm: IptvViewModel, channels: List<Channel>, playingId: String, now: Long,
                       onWatch: (Channel) -> Unit, onClose: () -> Unit, active: Boolean = true) {
    val guideChannels = rememberVisibleValue(channels, active)
    val guidePlayingId = rememberVisibleValue(playingId, active)
    val guideNow = rememberVisibleValue(now, active)
    val position = remember { GuidePosition(guidePlayingId, guideNow) }
    val list = rememberLazyListState(initialFirstVisibleItemIndex = guideChannels.indexOfFirst { it.id == guidePlayingId }.coerceAtLeast(0))
    var start by remember { mutableLongStateOf(guideNow / 3_600_000L * 3_600_000L) }
    var details by remember { mutableStateOf<Pair<Channel, Program>?>(null) }
    val detailFocus = remember { FocusRequester() }
    BackHandler(enabled = active && details != null) { details = null }
    LaunchedEffect(active, guidePlayingId) {
        if (!active) return@LaunchedEffect
        details = null
        val target = guideChannels.firstOrNull { it.id == guidePlayingId }?.id ?: guideChannels.firstOrNull()?.id.orEmpty()
        position.channelState.value = target
        position.timeState.longValue = guideNow
        start = guideNow / 3_600_000L * 3_600_000L
        val index = guideChannels.indexOfFirst { it.id == target }.coerceAtLeast(0)
        if (list.firstVisibleItemIndex != index || list.firstVisibleItemScrollOffset != 0) list.requestScrollToItem(index)
    }
    Box(Modifier.fillMaxSize().testTag("player-guide-overlay")) {
        Column(Modifier.fillMaxSize().testTag("player-guide-panel")
            .background(IptvColors.Background.copy(alpha = .75f)).padding(horizontal = 16.dp, vertical = 8.dp)) {
            GuideScreen(vm, guideChannels, list, guideNow, start, { start = it }, position,
                playingId = guidePlayingId, suspended = details != null || !active, translucent = true, interactive = active,
                header = { channel, program ->
                    Row(Modifier.fillMaxWidth().height(62.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        channel?.let { ChannelLogo(it, Modifier.width(65.dp).height(40.dp)) }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(program?.title ?: channel?.name ?: "Телегид", fontSize = 19.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(program?.let { "${guideTime(it.start, guideNow)}–${time(it.end)}  ${it.description}" } ?: "Телепрограмма отсутствует", color = Muted,
                                fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }, onProgram = { channel, program ->
                    if (program == null || guideNow in program.start until program.end) { onWatch(channel); onClose() }
                    else details = channel to program
                })
        }
        details?.let { (channel, program) ->
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .5f)), contentAlignment = Alignment.Center) {
                Column(Modifier.width(650.dp).background(IptvColors.Surface, androidx.compose.foundation.shape.RoundedCornerShape(20.dp)).padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(program.title, fontSize = 21.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${channel.name} · ${dateTime(program.start)}–${time(program.end)}", color = Muted, fontSize = 14.sp)
                    Text(program.description.ifBlank { "Описание отсутствует" }, maxLines = 5, overflow = TextOverflow.Ellipsis, fontSize = 15.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TvButton("Смотреть канал в эфире", { onWatch(channel); onClose() }, Modifier.focusRequester(detailFocus))
                        TvButton("Назад к программе", { details = null })
                    }
                }
            }
            LaunchedEffect(program.id) { withFrameNanos { }; detailFocus.requestFocus() }
        }
    }
}
