package home.iptv.core.database

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import home.iptv.core.model.*
import kotlinx.coroutines.flow.Flow

@Dao
interface IptvDao {
    @Query("SELECT * FROM sources ORDER BY kind, name") fun observeSources(): Flow<List<Source>>
    @Query("SELECT * FROM sources") suspend fun sources(): List<Source>
    @Query("SELECT * FROM sources WHERE id = :id") suspend fun source(id: String): Source?
    @Upsert suspend fun saveSource(source: Source)
    @Query("DELETE FROM sources WHERE id = :id") suspend fun deleteSource(id: String)
    @Query("SELECT * FROM channels ORDER BY playlistId, position") fun observeChannels(): Flow<List<Channel>>
    @Query("SELECT * FROM channels WHERE playlistId = :id ORDER BY position") suspend fun channels(id: String): List<Channel>
    @Query("SELECT * FROM channels") suspend fun allChannels(): List<Channel>
    @Query("SELECT * FROM channels WHERE id = :id") suspend fun channel(id: String): Channel?
    @Upsert suspend fun saveChannels(channels: List<Channel>)
    @Query("DELETE FROM channels WHERE playlistId = :id") suspend fun deleteChannels(id: String)
    @Query("SELECT * FROM favorites ORDER BY rank") fun observeFavorites(): Flow<List<Favorite>>
    @Query("SELECT * FROM favorites ORDER BY rank") suspend fun favorites(): List<Favorite>
    @Upsert suspend fun saveFavorites(favorites: List<Favorite>)
    @Query("DELETE FROM favorites WHERE channelId = :id") suspend fun removeFavorite(id: String)
    @Query("SELECT * FROM playlist_epg ORDER BY priority") fun observeLinks(): Flow<List<PlaylistEpg>>
    @Query("SELECT * FROM playlist_epg ORDER BY priority") suspend fun links(): List<PlaylistEpg>
    @Upsert suspend fun saveLinks(links: List<PlaylistEpg>)
    @Query("DELETE FROM playlist_epg WHERE playlistId = :id OR sourceId = :id") suspend fun deleteLinks(id: String)
    @Query("SELECT * FROM mappings") suspend fun mappings(): List<ChannelEpgMapping>
    @Query("SELECT * FROM mappings WHERE channelId = :id") fun observeMapping(id: String): Flow<ChannelEpgMapping?>
    @Upsert suspend fun saveMappings(mappings: List<ChannelEpgMapping>)
    @Query("DELETE FROM mappings WHERE channelId IN (SELECT id FROM channels WHERE playlistId = :id)") suspend fun deletePlaylistMappings(id: String)
    @Query("DELETE FROM mappings WHERE mode = 'auto'") suspend fun deleteAutoMappings()
    @Query("SELECT e.* FROM epg_channels e JOIN sources s ON s.id = e.sourceId AND s.generation = e.generation") suspend fun epgChannels(): List<EpgChannel>
    @Query("SELECT e.* FROM epg_channels e JOIN sources s ON s.id = e.sourceId AND s.generation = e.generation WHERE instr(e.searchName, :query) > 0 OR instr(lower(e.externalId), :query) > 0 ORDER BY e.names LIMIT 100")
    fun searchEpg(query: String): Flow<List<EpgChannel>>
    @Query("SELECT m.channelId, e.logo FROM mappings m JOIN epg_channels e ON e.sourceId = m.sourceId AND e.externalId = m.epgId JOIN sources s ON s.id = e.sourceId AND s.generation = e.generation WHERE m.mode != 'disabled' AND e.logo != ''")
    fun observeEpgLogos(): Flow<List<ChannelEpgLogo>>
    @Upsert suspend fun saveEpgChannels(channels: List<EpgChannel>)
    @Upsert suspend fun savePrograms(programs: List<Program>)
    @Query("DELETE FROM programs WHERE sourceId = :id AND generation != :generation") suspend fun deleteOtherPrograms(id: String, generation: String)
    @Query("DELETE FROM epg_channels WHERE sourceId = :id AND generation != :generation") suspend fun deleteOtherEpgChannels(id: String, generation: String)
    @Query("DELETE FROM programs WHERE sourceId = :id AND generation = :generation") suspend fun deleteProgramGeneration(id: String, generation: String)
    @Query("DELETE FROM epg_channels WHERE sourceId = :id AND generation = :generation") suspend fun deleteEpgGeneration(id: String, generation: String)
    @Query("DELETE FROM programs WHERE end < :cutoff") suspend fun prunePrograms(cutoff: Long)
    @Query("SELECT p.*, m.channelId AS channelId FROM programs p JOIN sources s ON s.id = p.sourceId AND s.generation = p.generation JOIN mappings m ON m.sourceId = p.sourceId AND m.epgId = p.epgId AND m.mode != 'disabled' WHERE m.channelId IN (:ids) AND p.end > :from AND p.start < :to ORDER BY p.start")
    fun programs(ids: List<String>, from: Long, to: Long): Flow<List<ChannelProgram>>
    @Query("SELECT p.*, m.channelId AS channelId FROM programs p JOIN sources s ON s.id = p.sourceId AND s.generation = p.generation JOIN mappings m ON m.sourceId = p.sourceId AND m.epgId = p.epgId AND m.mode != 'disabled' WHERE instr(p.searchTitle, :query) > 0 AND p.end > :now ORDER BY p.start LIMIT 150")
    fun searchPrograms(query: String, now: Long): Flow<List<ChannelProgram>>
    @Query("SELECT p.* FROM programs p JOIN sources s ON s.id = p.sourceId AND s.generation = p.generation WHERE p.sourceId = :sourceId AND p.epgId = :epgId AND p.end > :now ORDER BY p.start LIMIT 3")
    fun previewPrograms(sourceId: String, epgId: String, now: Long): Flow<List<Program>>
}

@Database(entities = [Source::class, Channel::class, Favorite::class, PlaylistEpg::class, EpgChannel::class, Program::class, ChannelEpgMapping::class], version = 2, exportSchema = true)
abstract class IptvDatabase : RoomDatabase() {
    abstract fun dao(): IptvDao
    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE epg_channels ADD COLUMN logo TEXT NOT NULL DEFAULT ''")
                // Fetch icons on the next normal source refresh; previous programmes remain visible.
                db.execSQL("UPDATE sources SET etag = NULL, lastModified = NULL, lastSuccess = 0 WHERE kind = 'epg'")
            }
        }
        fun open(context: Context): IptvDatabase = Room.databaseBuilder(context, IptvDatabase::class.java, "iptv.db").addMigrations(MIGRATION_1_2).build()
    }
}
