package home.iptv.sync

import home.iptv.core.database.IptvDatabase
import home.iptv.core.model.*
import home.iptv.core.network.SourceLoader
import androidx.room.withTransaction
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

class ImportCoordinator(private val db: IptvDatabase, private val loader: SourceLoader) {
    private val dao = db.dao()
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val running = MutableStateFlow<Map<String, String>>(emptyMap())
    val progress = running.asStateFlow()
    suspend fun <T> locked(id: String, block: suspend () -> T): T = locks.getOrPut(id) { Mutex() }.withLock { block() }

    suspend fun refresh(id: String, force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        // A simultaneous foreground/background request joins the existing operation by skipping it.
        val mutex = locks.getOrPut(id) { Mutex() }
        if (!mutex.tryLock()) return@withContext true
        try {
            val source = dao.source(id) ?: return@withContext true
            val now = System.currentTimeMillis()
            dao.saveSource(source.copy(lastAttempt = now, error = null))
            running.update { it + (id to "Загрузка…") }
            var staging: String? = null
            try {
                loader.open(source, force).use { download ->
                    val input = download.input
                    if (input == null) {
                        dao.saveSource(source.copy(lastAttempt = now, lastSuccess = now, error = null))
                    } else if (source.kind == Source.PLAYLIST) {
                        running.update { it + (id to "Разбор каналов…") }
                        val parsed = M3uParser.parse(input.reader(Charsets.UTF_8), id, download.baseUrl)
                        currentCoroutineContext().ensureActive()
                        db.withTransaction {
                            val merged = ChannelMatcher.reconcile(dao.channels(id), parsed.channels)
                            dao.saveChannels(merged)
                            dao.saveSource(source.copy(lastAttempt = now, lastSuccess = now, error = null, count = parsed.channels.size,
                                etag = download.etag, lastModified = download.modified, suggestedEpg = parsed.epgUrls.joinToString("\n")))
                            remap()
                        }
                    } else {
                        val generation = newId(); staging = generation
                        // Clean abandoned generations left by a killed process before filling the next one.
                        dao.deleteOtherPrograms(id, source.generation)
                        dao.deleteOtherEpgChannels(id, source.generation)
                        val channels = ArrayList<EpgChannel>(400)
                        val programs = ArrayList<Program>(400)
                        var processed = 0
                        val count = XmlTvParser.parse(input, source, generation, now, { channel ->
                            currentCoroutineContext().ensureActive()
                            channels += channel
                            if (channels.size == 400) { dao.saveEpgChannels(channels); channels.clear() }
                        }, { program ->
                            currentCoroutineContext().ensureActive()
                            programs += program
                            if (programs.size == 400) {
                                dao.savePrograms(programs); processed += programs.size; programs.clear()
                                running.update { it + (id to "Импорт: $processed передач") }
                            }
                        })
                        if (channels.isNotEmpty()) dao.saveEpgChannels(channels)
                        if (programs.isNotEmpty()) dao.savePrograms(programs)
                        currentCoroutineContext().ensureActive()
                        db.withTransaction {
                            dao.saveSource(source.copy(generation = generation, lastAttempt = now, lastSuccess = now, error = null,
                                count = count, etag = download.etag, lastModified = download.modified))
                            remap()
                            dao.deleteOtherPrograms(id, generation)
                            dao.deleteOtherEpgChannels(id, generation)
                            dao.prunePrograms(now - 86_400_000L)
                        }
                        staging = null
                    }
                }
                true
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) {
                // Network exceptions often contain credentials in their messages. Only our own messages are displayed.
                val message = if (e is ImportException) e.message else "Не удалось загрузить источник. Проверьте сеть, адрес и формат файла"
                dao.saveSource(source.copy(lastAttempt = now, error = message))
                false
            } finally {
                withContext(NonCancellable) {
                    staging?.let { dao.deleteProgramGeneration(id, it); dao.deleteEpgGeneration(id, it) }
                }
            }
        } finally { running.update { it - id }; mutex.unlock() }
    }

    suspend fun remap() {
        val fixed = dao.mappings().filter { it.mode != ChannelEpgMapping.AUTO }.map { it.channelId }.toSet()
        val links = dao.links()
        val epg = dao.epgChannels()
        val index = EpgIndex(epg)
        val maps = dao.allChannels().filter { it.id !in fixed }.mapNotNull { index.match(it, links) }
        dao.deleteAutoMappings()
        dao.saveMappings(maps)
    }
}

/** Build indexes once per import; channel matching is independent of the size of the EPG. */
class EpgIndex(channels: List<EpgChannel>) {
    private val ids = channels.groupBy { it.sourceId to it.externalId }
    private val names = channels.flatMap { channel -> channel.names.split('\n').map { (channel.sourceId to normalize(it)) to channel } }.groupBy({ it.first }, { it.second })
    fun match(channel: Channel, links: List<PlaylistEpg>): ChannelEpgMapping? {
        val sources = links.filter { it.playlistId == channel.playlistId }.sortedBy { it.priority }
        if (channel.tvgId.isNotBlank()) for (link in sources) {
            ids[link.sourceId to channel.tvgId]?.singleOrNull()?.let { return ChannelEpgMapping(channel.id, it.sourceId, it.externalId) }
        }
        for (link in sources) {
            val found = listOf(channel.tvgName, channel.name).filter(String::isNotBlank)
                .flatMap { names[link.sourceId to normalize(it)].orEmpty() }.distinctBy { it.externalId }
            if (found.size == 1) return ChannelEpgMapping(channel.id, found.single().sourceId, found.single().externalId)
            if (found.size > 1) return null
        }
        return null
    }
}
