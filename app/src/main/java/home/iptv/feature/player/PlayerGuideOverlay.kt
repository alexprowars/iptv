package home.iptv.feature.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    val position = remember { GuidePosition(playingId, now) }
    val list = rememberLazyListState(initialFirstVisibleItemIndex = channels.indexOfFirst { it.id == playingId }.coerceAtLeast(0))
    var start by remember { mutableLongStateOf(now / 3_600_000L * 3_600_000L) }
    var details by remember { mutableStateOf<Pair<Channel, Program>?>(null) }
    val detailFocus = remember { FocusRequester() }
    BackHandler(enabled = active && details != null) { details = null }
    LaunchedEffect(active, playingId, if (active) 0L else now) {
        details = null
        val target = channels.firstOrNull { it.id == playingId }?.id ?: channels.firstOrNull()?.id.orEmpty()
        position.channelState.value = target
        position.timeState.longValue = now
        start = now / 3_600_000L * 3_600_000L
        if (!active) {
            val index = channels.indexOfFirst { it.id == target }.coerceAtLeast(0)
            if (list.firstVisibleItemIndex != index || list.firstVisibleItemScrollOffset != 0) list.requestScrollToItem(index)
        }
    }
    Box(Modifier.fillMaxSize().testTag("player-guide-overlay")) {
        Column(Modifier.fillMaxSize().testTag("player-guide-panel")
            .background(Color(0x40101619)).padding(horizontal = 16.dp, vertical = 8.dp)) {
            GuideScreen(vm, channels, list, now, start, { start = it }, position,
                playingId = playingId, suspended = details != null || !active, translucent = true, interactive = active,
                header = { channel, program ->
                    Row(Modifier.fillMaxWidth().height(62.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        channel?.let { ChannelLogo(it, Modifier.width(65.dp).height(40.dp)) }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(program?.title ?: channel?.name ?: "Телегид", fontSize = 19.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(program?.let { "${guideTime(it.start, now)}–${time(it.end)}  ${it.description}" } ?: "Телепрограмма отсутствует", color = Muted,
                                fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }, onProgram = { channel, program ->
                    if (program == null || now in program.start until program.end) { onWatch(channel); onClose() }
                    else details = channel to program
                })
        }
        details?.let { (channel, program) ->
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .5f)), contentAlignment = Alignment.Center) {
                Column(Modifier.width(650.dp).background(Color(0xF01C242A)).padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
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
