package home.iptv.feature.mapping

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.*
import home.iptv.IptvViewModel
import home.iptv.core.model.*
import home.iptv.ui.*
import kotlinx.coroutines.flow.flowOf

@Composable
fun MappingScreen(vm: IptvViewModel, channel: Channel, onDone: () -> Unit) {
    val firstField = remember { FocusRequester() }
    LaunchedEffect(channel.id) { withFrameNanos { }; firstField.requestFocus() }
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<EpgChannel?>(null) }
    val sources by vm.sources.collectAsState()
    val mapping by remember(channel.id) { vm.dao.observeMapping(channel.id) }.collectAsState(null)
    val candidates by remember(query) { vm.dao.searchEpg(normalize(query)) }.collectAsState(emptyList())
    val preview by remember(selected) { selected?.let { vm.dao.previewPrograms(it.sourceId, it.externalId, System.currentTimeMillis()) } ?: flowOf(emptyList()) }.collectAsState(emptyList())
    fun set(value: ChannelEpgMapping) { vm.run { vm.graph.repository.setMapping(value); onDone() } }
    Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Программа · ${channel.name}", style = MaterialTheme.typography.headlineSmall)
        Text(when (mapping?.mode) {
            ChannelEpgMapping.DISABLED -> "Программа отключена"
            ChannelEpgMapping.MANUAL -> "Ручная привязка: ${sources.find { it.id == mapping?.sourceId }?.name ?: "источник отсутствует"} · ${mapping?.epgId}"
            else -> mapping?.let { "Автоматически: ${it.epgId}" } ?: "Совпадение не найдено или неоднозначно"
        }, color = Muted)
        val mappedPreview by remember(mapping) { mapping?.let { vm.dao.previewPrograms(it.sourceId, it.epgId, System.currentTimeMillis()) } ?: flowOf(emptyList()) }.collectAsState(emptyList())
        if (mapping?.mode == ChannelEpgMapping.MANUAL && mappedPreview.isEmpty()) Text("Для сохранённой привязки сейчас нет передач. Выбор сохранён.", color = Muted)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvButton("Автоматически", { set(ChannelEpgMapping(channel.id)) })
            TvButton("Без EPG", { set(ChannelEpgMapping(channel.id, mode = ChannelEpgMapping.DISABLED)) })
            TvButton("Назад", onDone)
        }
        Input("Поиск EPG-канала или XMLTV ID", query, { query = it; selected = null }, Modifier.focusRequester(firstField))
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(8.dp)) {
                items(candidates, key = { "${it.sourceId}:${it.externalId}" }) { candidate ->
                    TvButton("${candidate.names.substringBefore('\n')} · ${sources.find { it.id == candidate.sourceId }?.name}", { selected = candidate }, Modifier.fillMaxWidth())
                }
                if (candidates.isEmpty()) item { Text("Нет совпадений. Добавьте или обновите источник EPG.", color = Muted) }
            }
            selected?.let { choice -> SectionCard(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Text(choice.names.substringBefore('\n'), style = MaterialTheme.typography.titleLarge)
                Text(choice.externalId, color = Muted)
                preview.forEach { Text("${dateTime(it.start)}  ${it.title}") }
                if (preview.isEmpty()) Text("Нет ближайших передач", color = Muted)
                TvButton("Привязать к каналу", { set(ChannelEpgMapping(channel.id, choice.sourceId, choice.externalId, ChannelEpgMapping.MANUAL)) })
            } }
        }
    }
}
