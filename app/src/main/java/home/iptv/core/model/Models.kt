package home.iptv.core.model

import androidx.room.*
import java.util.Locale
import java.util.UUID

fun newId(): String = UUID.randomUUID().toString()
fun normalize(value: String): String = value.lowercase(Locale.ROOT).replace('ё', 'е').trim().replace(Regex("\\s+"), " ")

@Entity(tableName = "sources")
data class Source(
    @PrimaryKey val id: String = newId(),
    val kind: String = PLAYLIST,
    val name: String,
    val location: String,
    val intervalHours: Int = 24,
    val refreshOnStart: Boolean = true,
    val snapshot: Boolean = false,
    val headers: String = "",
    val timeShiftMinutes: Int = 0,
    val lastAttempt: Long = 0,
    val lastSuccess: Long = 0,
    val error: String? = null,
    val count: Int = 0,
    val etag: String? = null,
    val lastModified: String? = null,
    val generation: String = "",
    val suggestedEpg: String = "",
) {
    companion object { const val PLAYLIST = "playlist"; const val EPG = "epg" }
}

@Entity(tableName = "channels", indices = [Index("playlistId"), Index("searchName")])
data class Channel(
    @PrimaryKey val id: String = newId(),
    val playlistId: String,
    val name: String,
    val groupName: String = "",
    val streamUrl: String,
    val tvgId: String = "",
    val tvgName: String = "",
    val logo: String = "",
    val position: Int = 0,
    val available: Boolean = true,
    val unavailableReason: String? = null,
    val headers: String = "",
    val searchName: String = normalize(name),
)

@Entity(tableName = "favorites", foreignKeys = [ForeignKey(entity = Channel::class, parentColumns = ["id"], childColumns = ["channelId"], onDelete = ForeignKey.CASCADE)])
data class Favorite(@PrimaryKey val channelId: String, val rank: Int)

@Entity(tableName = "playlist_epg", primaryKeys = ["playlistId", "sourceId"], indices = [Index("sourceId")])
data class PlaylistEpg(val playlistId: String, val sourceId: String, val priority: Int)

@Entity(tableName = "epg_channels", primaryKeys = ["sourceId", "generation", "externalId"], indices = [Index("searchName")])
data class EpgChannel(val sourceId: String, val generation: String, val externalId: String, val names: String, val searchName: String = normalize(names), @ColumnInfo(defaultValue = "''") val logo: String = "")

@Entity(tableName = "programs", indices = [Index(value = ["sourceId", "generation", "epgId", "start", "end"]), Index("end")])
data class Program(
    @PrimaryKey val id: String = newId(), val sourceId: String, val generation: String,
    val epgId: String, val start: Long, val end: Long, val title: String,
    val description: String = "", val searchTitle: String = normalize(title),
)

@Entity(tableName = "mappings", indices = [Index("sourceId")])
data class ChannelEpgMapping(@PrimaryKey val channelId: String, val sourceId: String = "", val epgId: String = "", val mode: String = AUTO) {
    companion object { const val AUTO = "auto"; const val MANUAL = "manual"; const val DISABLED = "disabled" }
}

data class ChannelProgram(@Embedded val program: Program, val channelId: String)
data class PlaybackPreferences(val lastChannelId: String = "", val context: String = "all", val autoResume: Boolean = true, val fill: Boolean = false, val recentChannelIds: List<String> = emptyList())
data class ParsedPlaylist(val channels: List<Channel>, val epgUrls: List<String>)

data class ChannelEpgLogo(val channelId: String, val logo: String)
