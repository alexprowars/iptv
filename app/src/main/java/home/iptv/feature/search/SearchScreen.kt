package home.iptv.feature.search

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.*
import home.iptv.IptvViewModel
import home.iptv.core.model.*
import home.iptv.ui.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf

@Composable
fun SearchScreen(vm: IptvViewModel, onProgram: (Channel, Program) -> Unit) {
    val firstField = remember { FocusRequester() }
    LaunchedEffect(Unit) { withFrameNanos { }; firstField.requestFocus() }
    val query by vm.query.collectAsState()
    val channels by vm.visible.collectAsState()
    val all by vm.channels.collectAsState()
    var debounced by remember { mutableStateOf(query) }
    LaunchedEffect(query) { delay(250); debounced = query }
    val programs by remember(debounced) { if (debounced.isBlank()) flowOf(emptyList()) else vm.dao.searchPrograms(normalize(debounced), System.currentTimeMillis() - 86_400_000L) }.collectAsState(emptyList())
    val channelMap = remember(all) { all.associateBy { it.id } }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Input("Название канала или передачи", query, vm::setQuery, Modifier.focusRequester(firstField))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(8.dp)) {
            item { Text("Каналы · ${channels.size}", style = MaterialTheme.typography.titleMedium) }
            items(channels, key = { "channel:${it.id}" }) { channel -> TvButton(channel.name, { vm.play(channel, channels) }, Modifier.fillMaxWidth()) }
            item { Text("Передачи${if (programs.size == 150) " · первые 150, уточните запрос" else " · ${programs.size}"}", style = MaterialTheme.typography.titleMedium) }
            items(programs, key = { "program:${it.channelId}:${it.program.id}" }) { entry ->
                val channel = channelMap[entry.channelId]
                if (channel != null) TvButton("${entry.program.title} · ${channel.name} · ${dateTime(entry.program.start)}", { onProgram(channel, entry.program) }, Modifier.fillMaxWidth())
            }
        }
    }
}
