package home.iptv.feature.channels

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import home.iptv.ui.theme.IptvColors
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.*
import coil3.compose.AsyncImage
import home.iptv.IptvViewModel
import home.iptv.core.model.*
import home.iptv.ui.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf

@Composable
fun ChannelsScreen(vm: IptvViewModel, list: List<Channel>, listState: LazyListState, now: Long,
                   onActions: (Channel) -> Unit, onSources: () -> Unit) {
    val stars by vm.favorites.collectAsState()
    val starIds = remember(stars) { stars.map { it.channelId }.toSet() }
    val visibleIds by remember(list, listState) { derivedStateOf {
        val indices = listState.layoutInfo.visibleItemsInfo.map { it.index }
        (if (indices.isEmpty()) list.take(12) else indices.mapNotNull { list.getOrNull(it) }).map { it.id }
    } }
    val programs by remember(visibleIds, now / 60_000) {
        if (visibleIds.isEmpty()) flowOf(emptyList()) else vm.dao.programs(visibleIds, now, now + 6 * 3_600_000L)
    }.collectAsState(emptyList())
    val grouped = remember(programs) { programs.groupBy { it.channelId } }
    var restored by remember { mutableStateOf(false) }
    val target = remember(list) { vm.lastFocused.takeIf { id -> list.any { it.id == id } } ?: list.firstOrNull()?.id }
    val focus = remember { FocusRequester() }
    LaunchedEffect(list.isNotEmpty()) {
        if (list.isNotEmpty() && !restored) {
            val index = list.indexOfFirst { it.id == target }.coerceAtLeast(0)
            listState.scrollToItem(index)
            delay(80)
            runCatching { focus.requestFocus() }
            restored = true
        }
    }
    if (list.isEmpty()) Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Здесь пока нет каналов", style = MaterialTheme.typography.headlineSmall)
        TvButton("Источники", onSources)
    } else LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(10.dp)) {
        itemsIndexed(list, key = { _, channel -> channel.id }) { index, channel ->
            val current = grouped[channel.id]?.firstOrNull { it.program.start <= now && it.program.end > now }?.program
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvButton("", onClick = { vm.play(channel, list) }, onLongClick = { onActions(channel) },
                    modifier = Modifier.weight(1f).height(78.dp).then(if (channel.id == target) Modifier.focusRequester(focus) else Modifier)
                        .onFocusChanged { if (it.isFocused) vm.lastFocused = channel.id }) {
                    Text("${index + 1}", color = LocalContentColor.current.copy(alpha = .8f), modifier = Modifier.width(36.dp))
                    ChannelLogo(channel, Modifier.size(44.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("${if (channel.id in starIds) "★  " else ""}${channel.name}", maxLines = 1)
                        Text(if (!channel.available) "Недоступен" else current?.title ?: "Нет программы", maxLines = 1, color = LocalContentColor.current.copy(alpha = .8f), style = MaterialTheme.typography.bodySmall)
                        current?.let {
                            val fraction = ((now - it.start).toFloat() / (it.end - it.start)).coerceIn(0f, 1f)
                            Box(Modifier.fillMaxWidth().height(2.dp).background(IptvColors.Outline)) { Box(Modifier.fillMaxWidth(fraction).height(2.dp).background(Accent)) }
                        }
                    }
                    current?.let { Text(timeRange(it), color = LocalContentColor.current.copy(alpha = .8f), style = MaterialTheme.typography.labelSmall) }
                }
                TvButton("⋯", { onActions(channel) })
            }
        }
    }
}
