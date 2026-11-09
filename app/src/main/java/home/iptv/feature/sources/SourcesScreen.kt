package home.iptv.feature.sources

import android.app.Activity
import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import home.iptv.ui.theme.IptvColors
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import home.iptv.IptvViewModel
import home.iptv.core.model.*
import home.iptv.ui.*

@Composable
fun SourcesScreen(vm: IptvViewModel, onEdit: (Source) -> Unit, onSuggest: (Source, String) -> Unit) {
    val sources by vm.sources.collectAsState()
    val busy by vm.busy.collectAsState()
    val links by vm.links.collectAsState()
    var delete by remember { mutableStateOf<Source?>(null) }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(8.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                TvButton("+ Плейлист", { onEdit(Source(name = "", location = "")) })
                TvButton("+ Телепрограмма", { onEdit(Source(kind = Source.EPG, name = "", location = "")) })
            }
        }
        if (sources.isEmpty()) item { Text("Добавьте M3U-плейлист по ссылке или выберите файл.\nАдреса каналов и программы предоставляет ваш IPTV-провайдер.", color = Muted, modifier = Modifier.padding(vertical = 20.dp)) }
        items(sources, key = { it.id }) { source ->
            SectionCard {
                Text("${if (source.kind == Source.PLAYLIST) "M3U" else "EPG"}  ·  ${source.name}", style = MaterialTheme.typography.titleLarge)
                Text(busy[source.id] ?: "${source.count} ${if (source.kind == Source.PLAYLIST) "каналов" else "передач"}  ·  Успешно: ${dateTime(source.lastSuccess)}", color = Muted)
                Text(when {
                    source.snapshot -> "Локальный снимок · оригинал не обновляется"
                    source.intervalHours == 0 -> "Обновление вручную"
                    else -> "Каждые ${source.intervalHours} ч · ориентир: ${dateTime((source.lastSuccess.takeIf { it > 0 } ?: System.currentTimeMillis()) + source.intervalHours * 3_600_000L)} (Android может отложить)"
                }, color = Muted, style = MaterialTheme.typography.bodySmall)
                source.error?.let { Text(it, color = IptvColors.Error) }
                if (source.kind == Source.EPG && source.count == 0 && links.none { it.sourceId == source.id }) {
                    Text("Не выбран плейлист для телепрограммы. Нажмите «Изменить» и выберите плейлист.", color = Muted)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TvButton("Изменить", { onEdit(source) })
                    TvButton(if (busy.containsKey(source.id)) "Обновляется…" else "Обновить сейчас", { vm.refresh(source.id) })
                    TvButton("Удалить", { delete = source })
                }
                source.suggestedEpg.lineSequence().filter(String::isNotBlank).take(5).forEachIndexed { index, url ->
                    TvButton("Добавить EPG из плейлиста${if (index == 0) "" else " ${index + 1}"}", { onSuggest(source, url) })
                }
            }
        }
    }
    delete?.let { source -> Modal("Удалить «${source.name}»?", { delete = null }) {
        Text(if (source.kind == Source.PLAYLIST) "Каналы и избранное этого плейлиста будут удалены." else "Программа будет удалена. Ручные привязки сохранятся с отметкой об отсутствии источника.")
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            TvButton("Отмена", { delete = null })
            TvButton("Удалить", { vm.run { vm.graph.repository.deleteSource(source.id) }; delete = null })
        }
    } }
}

internal fun initialEpgSources(source: Source, all: List<Source>, links: List<PlaylistEpg>): List<String> =
    if (all.none { it.id == source.id }) all.filter { it.kind == Source.EPG }.map { it.id }
    else links.filter { it.playlistId == source.id }.sortedBy { it.priority }.map { it.sourceId }

internal fun initialEpgPlaylists(source: Source, attachTo: String?, all: List<Source>, links: List<PlaylistEpg>): Set<String> =
    when {
        attachTo != null -> setOf(attachTo)
        all.any { it.id == source.id } -> links.filter { it.sourceId == source.id }.map { it.playlistId }.toSet()
        else -> all.filter { it.kind == Source.PLAYLIST && links.none { link -> link.playlistId == it.id } }.map { it.id }.toSet()
    }

@Composable
fun SourceEditor(vm: IptvViewModel, source: Source, attachTo: String?, onDone: () -> Unit) {
    val context = LocalContext.current
    val all by vm.sources.collectAsState()
    var name by remember(source.id) { mutableStateOf(source.name) }
    var location by remember(source.id) { mutableStateOf(source.location) }
    var snapshot by remember(source.id) { mutableStateOf(source.snapshot) }
    var headers by remember(source.id) { mutableStateOf(source.headers) }
    var hours by remember(source.id) { mutableIntStateOf(source.intervalHours) }
    var startup by remember(source.id) { mutableStateOf(source.refreshOnStart) }
    var shift by remember(source.id) { mutableStateOf(source.timeShiftMinutes.toString()) }
    var selected by remember(source.id) { mutableStateOf(emptyList<String>()) }
    var selectedPlaylists by remember(source.id) { mutableStateOf(emptySet<String>()) }
    var selectionsReady by remember(source.id) { mutableStateOf(false) }
    LaunchedEffect(source.id) {
        // StateFlow may still hold its empty initial value when this editor opens.
        val savedSources = vm.dao.sources()
        val savedLinks = vm.dao.links()
        selected = initialEpgSources(source, savedSources, savedLinks)
        selectedPlaylists = initialEpgPlaylists(source, attachTo, savedSources, savedLinks)
        selectionsReady = true
    }
    if (!selectionsReady) return
    val firstField = remember { FocusRequester() }
    val saveFocus = remember { FocusRequester() }
    var focusSave by remember { mutableStateOf(false) }
    LaunchedEffect(selected, focusSave) {
        if (focusSave) { withFrameNanos { }; saveFocus.requestFocus(); focusSave = false }
    }
    LaunchedEffect(source.id) { withFrameNanos { }; firstField.requestFocus() }
    var saving by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data.takeIf { result.resultCode == Activity.RESULT_OK }
        if (uri != null) vm.importFile(uri) { savedUri, isSnapshot -> location = savedUri; snapshot = isSnapshot; if (isSnapshot) hours = 0 }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(if (source.kind == Source.PLAYLIST) "Плейлист M3U" else "Телепрограмма XMLTV", style = MaterialTheme.typography.titleLarge)
        Text("Подключение и обновление источника", color = Muted)
        Input("Название", name, { name = it }, Modifier.focusRequester(firstField))
        Input("URL или выбранный файл", location, { location = it; snapshot = false })
        TvButton("Выбрать файл", {
            val intent = filePickerIntent(context)
            val unavailable = "На телевизоре нет доступного выбора файлов. Используйте URL или установите файловый менеджер с поддержкой системного выбора документов."
            if (intent == null) vm.notice.value = unavailable
            else try { picker.launch(intent) }
            catch (_: ActivityNotFoundException) { vm.notice.value = unavailable }
            catch (_: SecurityException) { vm.notice.value = "Нет доступа к файловому менеджеру. Выберите другой или используйте URL." }
        })
        if (snapshot) Text("Сохранён снимок файла: провайдер не разрешил постоянный доступ к оригиналу.", color = Muted)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            TvButton(if (hours == 0) "Интервал: вручную" else "Интервал: $hours ч", {
                val options = listOf(0, 1, 3, 6, 12, 24); if (!snapshot) hours = options[(options.indexOf(hours) + 1) % options.size]
            })
            TvButton("При запуске: ${if (startup) "вкл" else "выкл"}", { startup = !startup })
        }
        Input("HTTP-заголовки · по одному на строку (User-Agent: … / Referer: …)", headers, { headers = it },
            Modifier.focusProperties { if (source.kind == Source.PLAYLIST && all.none { it.kind == Source.EPG }) down = saveFocus }, multiline = true)
        if (source.kind == Source.EPG) Input("Поправка времени, минуты (например −60)", shift, { shift = it })
        if (source.kind == Source.PLAYLIST) {
            Text("Источники EPG по приоритету", style = MaterialTheme.typography.titleMedium)
            selected.forEachIndexed { index, id ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("${index + 1}. ${all.find { it.id == id }?.name ?: "Источник удалён"}", Modifier.weight(1f))
                    TvButton("↑", { if (index > 0) selected = selected.toMutableList().apply { removeAt(index); add(index - 1, id) } })
                    TvButton("Убрать", { selected = selected - id; focusSave = true })
                }
            }
            all.filter { it.kind == Source.EPG && it.id !in selected }.forEach { epg -> TvButton("+ ${epg.name}", { selected = selected + epg.id; focusSave = true }) }
            if (all.none { it.kind == Source.EPG }) Text("Добавьте телепрограмму на экране источников после сохранения плейлиста.", color = Muted)
        } else {
            Text("Плейлисты для этой телепрограммы", style = MaterialTheme.typography.titleMedium)
            Text("Передачи загружаются для каналов выбранных плейлистов.", color = Muted)
            all.filter { it.kind == Source.PLAYLIST }.forEach { playlist ->
                val checked = playlist.id in selectedPlaylists
                TvButton("${if (checked) "✓" else "+"} ${playlist.name}", {
                    selectedPlaylists = if (checked) selectedPlaylists - playlist.id else selectedPlaylists + playlist.id
                    focusSave = true
                })
            }
            if (all.none { it.kind == Source.PLAYLIST }) Text("Добавьте плейлист после сохранения телепрограммы.", color = Muted)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            TvButton(if (saving) "Сохранение…" else "Сохранить и обновить", {
                if (!saving) {
                    val correction = shift.toIntOrNull()
                    if (correction == null) vm.notice.value = "Поправка времени должна быть целым числом минут"
                    else {
                        saving = true
                        vm.run {
                            try {
                                vm.graph.repository.saveSource(source.copy(name = name.trim(), location = location.trim(), intervalHours = hours,
                                    refreshOnStart = startup, snapshot = snapshot, headers = headers, timeShiftMinutes = correction))
                                if (source.kind == Source.PLAYLIST) vm.graph.repository.setEpgSources(source.id, selected, refreshPrograms = false)
                                if (source.kind == Source.EPG) vm.graph.repository.setEpgPlaylists(source.id, selectedPlaylists, refreshPrograms = false)
                                // Leave the editor as soon as local settings are stored. Downloads
                                // report progress/errors on the sources screen, in this ViewModel job.
                                onDone()
                                vm.graph.importer.refresh(source.id, force = true)
                            } finally { saving = false }
                        }
                    }
                }
            }, Modifier.focusRequester(saveFocus))
            TvButton("Отмена", onDone)
        }
    }
}
