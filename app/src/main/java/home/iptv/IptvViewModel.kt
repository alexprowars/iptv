package home.iptv

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import home.iptv.core.model.*
import home.iptv.feature.guide.GuideProgramCache
import home.iptv.feature.guide.guideQueryIds
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class IptvViewModel(application: Application, private val saved: SavedStateHandle) : AndroidViewModel(application) {
    val graph = (application as IptvApplication).graph
    val dao = graph.db.dao()
    internal val guideCache = GuideProgramCache(viewModelScope, dao::programs)
    val sources = dao.observeSources().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val channels = combine(dao.observeChannels(), dao.observeEpgLogos()) { channels, logos ->
        val epgLogos = logos.associate { it.channelId to it.logo }
        channels.map { channel ->
            if (channel.logo.isBlank()) channel.copy(logo = epgLogos[channel.id].orEmpty()) else channel
        }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val groups = channels.map { all -> all.map { it.groupName }.filter(String::isNotBlank).distinct().sorted() }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val favorites = dao.observeFavorites().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val links = dao.observeLinks().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val preferences = graph.preferences.flow.stateIn(viewModelScope, SharingStarted.Eagerly, PlaybackPreferences())
    val recentChannels = combine(channels, preferences) { channels, preferences ->
        val byId = channels.associateBy { it.id }
        preferences.recentChannelIds.mapNotNull { byId[it]?.takeIf(Channel::available) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val filter = saved.getStateFlow("filter", "all")
    val query = saved.getStateFlow("query", "")
    val notice = MutableStateFlow<String?>(null)
    val busy = graph.importer.progress
    data class Selection(val channel: Channel, val headers: String, val context: String)
    val playback = MutableStateFlow<Selection?>(null)
    private var queue = emptyList<String>()
    private var pointer = 0
    private var interacted = false
    var lastFocused: String
        get() = saved["focused"] ?: ""
        set(value) { saved["focused"] = value }

    val visible = combine(channels, favorites, filter, query) { all, stars, filter, query ->
        val favoriteOrder = stars.associate { it.channelId to it.rank }
        val filtered = when {
            filter == "favorites" -> all.filter { it.id in favoriteOrder }.sortedBy { favoriteOrder[it.id] }
            filter.startsWith("playlist:") -> all.filter { it.playlistId == filter.removePrefix("playlist:") }
            filter.startsWith("group:") -> all.filter { it.groupName == filter.removePrefix("group:") }
            else -> all
        }
        if (filter == "search") filtered.filter { it.searchName.contains(normalize(query)) } else filtered
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        graph.onStart()
        viewModelScope.launch {
            val hour = 3_600_000L
            val liveWindow = flow {
                while (currentCoroutineContext().isActive) {
                    emit(System.currentTimeMillis() / hour * hour)
                    delay(60_000)
                }
            }
            // Warm the initial/filtered page, without chasing fullscreen channel changes.
            combine(visible, liveWindow) { channels, start ->
                val index = channels.indexOfFirst { it.id == lastFocused }.coerceAtLeast(0)
                guideQueryIds(channels, index) to start
            }.distinctUntilChanged().collect { (ids, start) ->
                guideCache.preload(ids, start, start + 2 * hour)
            }
        }
        viewModelScope.launch {
            val pref = graph.preferences.flow.first()
            if (pref.autoResume && pref.lastChannelId.isNotBlank()) {
                val channel = dao.channel(pref.lastChannelId)
                if (channel?.available == true && !interacted) {
                    setFilter(if (pref.context.startsWith("search:")) "search" else pref.context)
                    if (pref.context.startsWith("search:")) setQuery(pref.context.removePrefix("search:"))
                    val all = dao.allChannels()
                    val stars = dao.favorites().map { it.channelId }
                    val restored = when {
                        pref.context.startsWith("search:") -> all.filter { it.searchName.contains(normalize(pref.context.removePrefix("search:"))) }
                        pref.context == "favorites" -> stars.mapNotNull { id -> all.find { it.id == id } }
                        pref.context.startsWith("playlist:") -> all.filter { it.playlistId == pref.context.removePrefix("playlist:") }
                        pref.context.startsWith("group:") -> all.filter { it.groupName == pref.context.removePrefix("group:") }
                        else -> all
                    }
                    queue = restored.filter { it.available }.map { it.id }
                    if (channel.id !in queue) queue = listOf(channel.id) + queue
                    pointer = queue.indexOf(channel.id)
                    lastFocused = channel.id
                    playback.value = Selection(channel, dao.source(channel.playlistId)?.headers.orEmpty(), pref.context)
                }
            }
        }
    }
    fun setFilter(value: String) { saved["filter"] = value }
    fun setQuery(value: String) { saved["query"] = value }
    fun interact() { interacted = true }
    fun play(channel: Channel, list: List<Channel> = visible.value) {
        interacted = true
        if (!channel.available) { notice.value = channel.unavailableReason; return }
        queue = list.filter { it.available }.map { it.id }.distinct()
        if (channel.id !in queue) queue = listOf(channel.id) + queue
        pointer = queue.indexOf(channel.id)
        lastFocused = channel.id
        playback.value = Selection(channel, sources.value.find { it.id == channel.playlistId }?.headers.orEmpty(), if (filter.value == "search") "search:${query.value}" else filter.value)
    }
    fun openStream(url: String) {
        val error = home.iptv.sync.M3uParser.streamError(url)
        if (error != null) { notice.value = error; return }
        play(Channel(playlistId = "", name = "Прямой поток", streamUrl = url), emptyList())
    }
    fun step(delta: Int) {
        if (queue.isEmpty()) return
        repeat(queue.size) {
            pointer = (pointer + delta + queue.size) % queue.size
            val channel = channels.value.find { it.id == queue[pointer] && it.available }
            if (channel != null) {
                lastFocused = channel.id
                playback.value = Selection(channel, sources.value.find { it.id == channel.playlistId }?.headers.orEmpty(), playback.value?.context ?: filter.value)
                return
            }
        }
    }
    fun played(id: String) {
        val selection = playback.value ?: return
        if (selection.channel.id == id && selection.channel.playlistId.isNotBlank()) run { graph.preferences.played(id, selection.context) }
    }
    fun run(block: suspend () -> Unit) { viewModelScope.launch { try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { notice.value = if (e is IllegalArgumentException || e is home.iptv.sync.ImportException) e.message else "Не удалось выполнить действие. Проверьте источник и повторите" } } }
    fun refresh(id: String) { run { graph.importer.refresh(id, force = true) } }
    fun saveSource(source: Source, done: () -> Unit) { run { graph.repository.saveSource(source); done(); graph.importer.refresh(source.id) } }
    fun importFile(uri: Uri, done: (String, Boolean) -> Unit) { run { val result = graph.loader.retain(uri); done(result.first, result.second) } }
    fun favorite(id: String) { run { graph.repository.toggleFavorite(id) } }
    fun closePlayer() { playback.value = null }
}
