package home.iptv.core.database

import androidx.room.withTransaction
import home.iptv.core.model.*
import home.iptv.core.network.parseHeaders
import home.iptv.sync.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class Repository(val db: IptvDatabase, private val importer: ImportCoordinator, private val scheduler: RefreshScheduler) {
    val dao = db.dao()
    suspend fun saveSource(source: Source) = withContext(Dispatchers.IO) {
        require(source.name.isNotBlank()) { "Введите название" }
        require(source.intervalHours in listOf(0, 1, 3, 6, 12, 24)) { "Некорректный интервал" }
        require(source.timeShiftMinutes in -1440..1440) { "Поправка времени: от −1440 до 1440 минут" }
        parseHeaders(source.headers)
        val scheme = java.net.URI(source.location).scheme
        require(scheme in listOf("http", "https", "content", "file")) { "Укажите HTTP(S)-адрес или выберите файл" }
        if (scheme == "http" || scheme == "https") require(!java.net.URI(source.location).host.isNullOrBlank()) { "Некорректный адрес" }
        importer.locked(source.id) {
            val existing = dao.source(source.id)
            val changed = existing?.let { it.location != source.location || it.headers != source.headers || it.timeShiftMinutes != source.timeShiftMinutes } ?: true
            val saved = source.copy(etag = if (changed) null else existing?.etag, lastModified = if (changed) null else existing?.lastModified,
                lastAttempt = existing?.lastAttempt ?: 0, lastSuccess = if (changed) 0 else existing?.lastSuccess ?: 0,
                count = existing?.count ?: 0, generation = existing?.generation.orEmpty(), error = null,
                intervalHours = if (source.snapshot) 0 else source.intervalHours)
            dao.saveSource(saved)
            scheduler.schedule(saved)
        }
    }
    suspend fun deleteSource(id: String) = withContext(Dispatchers.IO) {
        scheduler.cancel(id)
        importer.locked(id) {
            db.withTransaction {
                dao.deletePlaylistMappings(id)
                dao.deleteChannels(id)
                dao.deleteLinks(id)
                dao.deleteSource(id)
                dao.deleteOtherPrograms(id, "__deleted__")
                dao.deleteOtherEpgChannels(id, "__deleted__")
                // Manual references intentionally survive EPG removal to make missing bindings visible.
                importer.remap()
            }
        }
    }
    suspend fun toggleFavorite(id: String) = db.withTransaction {
        val all = dao.favorites()
        if (all.any { it.channelId == id }) dao.removeFavorite(id)
        else dao.saveFavorites(listOf(Favorite(id, (all.maxOfOrNull { it.rank } ?: -1) + 1)))
    }
    suspend fun moveFavorite(id: String, delta: Int) = db.withTransaction {
        val all = dao.favorites().toMutableList()
        val index = all.indexOfFirst { it.channelId == id }
        if (index < 0) return@withTransaction
        val target = (index + delta).coerceIn(0, all.lastIndex)
        val item = all.removeAt(index); all.add(target, item)
        dao.saveFavorites(all.mapIndexed { rank, favorite -> favorite.copy(rank = rank) })
    }
    suspend fun setMapping(mapping: ChannelEpgMapping) = db.withTransaction {
        dao.saveMappings(listOf(mapping)); importer.remap()
    }
    suspend fun setEpgSources(playlistId: String, ids: List<String>) = db.withTransaction {
        dao.deleteLinks(playlistId)
        dao.saveLinks(ids.mapIndexed { priority, id -> PlaylistEpg(playlistId, id, priority) })
        importer.remap()
    }
    suspend fun replaceChannel(oldId: String, newId: String) = db.withTransaction {
        val old = dao.channel(oldId) ?: return@withTransaction
        val new = dao.channel(newId) ?: return@withTransaction
        require(!old.available && new.available && old.playlistId == new.playlistId) { "Выберите доступный канал из того же плейлиста" }
        val favorite = dao.favorites().firstOrNull { it.channelId == oldId }
        if (favorite != null) { dao.removeFavorite(oldId); dao.saveFavorites(listOf(favorite.copy(channelId = newId))) }
        dao.mappings().firstOrNull { it.channelId == oldId && it.mode != ChannelEpgMapping.AUTO }?.let { dao.saveMappings(listOf(it.copy(channelId = newId))) }
    }
}
