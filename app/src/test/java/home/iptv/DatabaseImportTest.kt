package home.iptv

import android.app.Application
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import home.iptv.core.database.*
import home.iptv.core.model.*
import home.iptv.core.network.SourceLoader
import home.iptv.sync.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DatabaseImportTest {
    private lateinit var db: IptvDatabase
    private lateinit var importer: ImportCoordinator
    private lateinit var file: File
    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(context, IptvDatabase::class.java).build()
        importer = ImportCoordinator(db, SourceLoader(context))
        file = File.createTempFile("iptv-test", ".m3u")
    }
    @After fun cleanup() { db.close(); file.delete() }
    private fun playlist(vararg entries: Pair<String, String>) = "#EXTM3U\n" + entries.joinToString("\n") { (id, url) -> "#EXTINF:-1 tvg-id=\"$id\",Channel $id\n$url" }
    private fun epg(vararg channels: Pair<String, String>): String {
        val format = DateTimeFormatter.ofPattern("uuuuMMddHHmmss Z").withZone(ZoneOffset.UTC)
        val now = System.currentTimeMillis()
        val start = format.format(Instant.ofEpochMilli(now))
        val stop = format.format(Instant.ofEpochMilli(now + 3_600_000))
        return "<tv>" + channels.joinToString("") { (id, name) ->
            "<channel id=\"$id\"><display-name>$name</display-name></channel>"
        } + channels.distinctBy { it.first }.joinToString("") { (id, _) ->
            "<programme channel=\"$id\" start=\"$start\" stop=\"$stop\"><title>Show $id</title><desc>Description $id</desc></programme>"
        } + "</tv>"
    }
    private fun storedProgramIds(): List<String> = db.openHelper.readableDatabase
        .query("SELECT epgId FROM programs ORDER BY epgId").use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
    @Test fun firstEpgImportStoresOnlyAvailableMappedChannelsAndKeepsFullCatalogue() = runBlocking {
        val dao = db.dao()
        val source = Source(kind = Source.EPG, name = "epg", location = file.toURI().toString())
        val other = Source(kind = Source.EPG, name = "preferred", location = "", generation = "g")
        dao.saveSource(source); dao.saveSource(other)
        dao.saveEpgChannels(listOf(EpgChannel(other.id, "g", "preferred", "Preferred")))
        dao.saveLinks(listOf(PlaylistEpg("p", other.id, 0), PlaylistEpg("p", source.id, 1)))
        fun channel(id: String, name: String = id, tvgId: String = id) =
            Channel(id = id, playlistId = "p", name = name, tvgId = tvgId, streamUrl = "https://example.org/$id")
        dao.saveChannels(listOf(channel("exact"), channel("alias-match", "Alias HD", "missing"),
            channel("manual", tvgId = "wrong-auto"), channel("disabled"), channel("unavailable").copy(available = false),
            channel("ambiguous", "Ambiguous", ""), channel("priority", "Preferred", ""), channel("unmatched")))
        dao.saveMappings(listOf(ChannelEpgMapping("manual", source.id, "manual-target", ChannelEpgMapping.MANUAL),
            ChannelEpgMapping("disabled", mode = ChannelEpgMapping.DISABLED)))
        file.writeText(epg("exact" to "Exact", "alias" to "Alias SD", "alias" to "Alias HD",
            "manual-target" to "Manual", "wrong-auto" to "Wrong", "disabled" to "Disabled",
            "unavailable" to "Unavailable", "ambiguous-a" to "Ambiguous", "ambiguous-b" to "Ambiguous",
            "lower-priority" to "Preferred", "unused" to "Unused"))
        assertTrue(importer.refresh(source.id))
        assertEquals(listOf("alias", "exact", "manual-target"), storedProgramIds())
        assertEquals(3, dao.source(source.id)?.count)
        assertEquals(10, dao.epgChannels().count { it.sourceId == source.id })
        assertEquals("Alias SD\nAlias HD", dao.epgChannels().single { it.externalId == "alias" }.names)
        assertEquals("alias", dao.mappings().single { it.channelId == "alias-match" }.epgId)
        assertEquals(other.id, dao.mappings().single { it.channelId == "priority" }.sourceId)
        assertFalse(dao.mappings().any { it.channelId == "ambiguous" })
    }
    @Test fun noMatchesPublishesCatalogueWithZeroProgramsAndRemovesPreviouslyImportedPrograms() = runBlocking {
        val dao = db.dao()
        val source = Source(kind = Source.EPG, name = "epg", location = file.toURI().toString(), generation = "old")
        dao.saveSource(source)
        val now = System.currentTimeMillis()
        dao.savePrograms(listOf(Program(sourceId = source.id, generation = "old", epgId = "unused",
            start = now, end = now + 3_600_000, title = "Old unfiltered show")))
        file.writeText(epg("unused" to "Unused"))
        assertTrue(importer.refresh(source.id))
        assertTrue(storedProgramIds().isEmpty())
        assertEquals(0, dao.source(source.id)?.count)
        assertNull(dao.source(source.id)?.error)
        assertEquals("unused", dao.epgChannels().single().externalId)
    }
    @Test fun playlistImportedAfterEpgLoadsProgramsForNewAutomaticMatches() = runBlocking {
        val epgFile = File.createTempFile("iptv-epg", ".xml")
        try {
            val source = Source(kind = Source.EPG, name = "epg", location = epgFile.toURI().toString())
            val playlist = Source(name = "playlist", location = file.toURI().toString())
            db.dao().saveSource(source); db.dao().saveSource(playlist)
            db.dao().saveLinks(listOf(PlaylistEpg(playlist.id, source.id, 0)))
            epgFile.writeText(epg("one" to "One", "unused" to "Unused"))
            assertTrue(importer.refresh(source.id))
            assertTrue(storedProgramIds().isEmpty())
            file.writeText(playlist("one" to "https://example.org/one"))
            assertTrue(importer.refresh(playlist.id))
            assertEquals(listOf("one"), storedProgramIds())
        } finally { epgFile.delete() }
    }
    @Test fun catalogueChangeLoadsProgramsFromFallbackSource() = runBlocking {
        val fallbackFile = File.createTempFile("iptv-fallback", ".xml")
        try {
            val dao = db.dao()
            val primary = Source(kind = Source.EPG, name = "primary", location = file.toURI().toString())
            val fallback = Source(kind = Source.EPG, name = "fallback", location = fallbackFile.toURI().toString())
            val channel = Channel(playlistId = "p", name = "One", streamUrl = "https://example.org/one")
            dao.saveSource(primary); dao.saveSource(fallback)
            dao.saveChannels(listOf(channel))
            dao.saveLinks(listOf(PlaylistEpg("p", primary.id, 0), PlaylistEpg("p", fallback.id, 1)))
            file.writeText(epg("primary-one" to "One"))
            fallbackFile.writeText(epg("fallback-one" to "One"))
            assertTrue(importer.refresh(primary.id))
            assertTrue(importer.refresh(fallback.id))
            assertEquals(listOf("primary-one"), storedProgramIds())
            assertEquals(0, dao.source(fallback.id)?.count)
            // The primary catalogue no longer identifies this channel. Its binding moves to
            // the fallback, whose programmes were deliberately skipped on its first import.
            file.writeText(epg("unrelated" to "Unrelated"))
            assertTrue(importer.refresh(primary.id))
            assertEquals(fallback.id, dao.mappings().single().sourceId)
            assertEquals(listOf("fallback-one"), storedProgramIds())
        } finally { fallbackFile.delete() }
    }
    @Test fun malformedPlaylistDoesNotEraseCatalogOrFavorites() = runBlocking {
        val source = Source(name = "test", location = file.toURI().toString())
        db.dao().saveSource(source)
        file.writeText(playlist("a" to "https://example.org/a", "b" to "https://example.org/b"))
        assertTrue(importer.refresh(source.id))
        val channel = db.dao().channels(source.id).first()
        db.dao().saveFavorites(listOf(Favorite(channel.id, 0)))
        file.writeText("#EXTM3U\n#EXTINF:-1,Broken\n")
        assertFalse(importer.refresh(source.id))
        assertEquals(2, db.dao().channels(source.id).size)
        assertEquals(channel.id, db.dao().favorites().single().channelId)
        assertNotNull(db.dao().source(source.id)?.error)
    }
    @Test fun reorderAndUrlChangePreserveFavoriteAndManualMapping() = runBlocking {
        val source = Source(name = "test", location = file.toURI().toString())
        db.dao().saveSource(source)
        file.writeText(playlist("a" to "https://example.org/a", "b" to "https://example.org/b"))
        importer.refresh(source.id)
        val channel = db.dao().channels(source.id).first()
        db.dao().saveFavorites(listOf(Favorite(channel.id, 0)))
        val mapping = ChannelEpgMapping(channel.id, "source", "epg-id", ChannelEpgMapping.MANUAL)
        db.dao().saveMappings(listOf(mapping))
        file.writeText(playlist("b" to "https://example.org/b?token=new", "a" to "https://example.org/a?token=new"))
        assertTrue(importer.refresh(source.id))
        assertEquals(channel.id, db.dao().channels(source.id).last().id)
        assertEquals(listOf(mapping), db.dao().mappings())
        assertEquals(channel.id, db.dao().favorites().single().channelId)
    }
    @Test fun partialEpgGenerationRemainsInvisibleAndIsDiscarded() = runBlocking {
        val source = Source(kind = Source.EPG, name = "epg", location = file.toURI().toString(), generation = "good")
        db.dao().saveSource(source)
        val channel = Channel(playlistId = "p", name = "One", streamUrl = "https://example.org")
        db.dao().saveChannels(listOf(channel))
        db.dao().saveMappings(listOf(ChannelEpgMapping(channel.id, source.id, "one", ChannelEpgMapping.MANUAL)))
        val now = System.currentTimeMillis()
        db.dao().savePrograms(listOf(Program(sourceId = source.id, generation = "good", epgId = "one", start = now - 1000, end = now + 60_000, title = "Original")))
        val format = DateTimeFormatter.ofPattern("uuuuMMddHHmmss Z").withZone(ZoneOffset.UTC)
        val start = format.format(Instant.ofEpochMilli(now))
        val stop = format.format(Instant.ofEpochMilli(now + 3_600_000))
        // Force at least one staging batch to commit, then fail on the trailing malformed programme.
        file.writeText("<tv><channel id=\"one\"><display-name>One</display-name></channel>" + (1..405).joinToString("") { "<programme channel=\"one\" start=\"$start\" stop=\"$stop\"><title>Staged $it</title></programme>" } + "<programme")
        assertFalse(importer.refresh(source.id))
        assertEquals("good", db.dao().source(source.id)?.generation)
        assertEquals("Original", db.dao().programs(listOf(channel.id), now, now + 60_000).first().single().program.title)
        val cursor = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM programs")
        cursor.use { assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0)) }
    }
    @Test fun transactionRollbackPreservesWorkingGeneration() = runBlocking {
        val source = Source(name = "original", location = "https://example.org")
        db.dao().saveSource(source)
        try { db.withTransaction { db.dao().saveSource(source.copy(name = "temporary")); error("rollback") } } catch (_: IllegalStateException) { }
        assertEquals("original", db.dao().source(source.id)?.name)
    }
    @Test fun invalidDurationsDoNotAbortEpgAndEmptyRefreshPreservesPublishedGeneration() = runBlocking {
        val source = Source(kind = Source.EPG, name = "epg", location = file.toURI().toString(), generation = "old", error = "Конец передачи раньше начала")
        val channel = Channel(playlistId = "p", name = "One", streamUrl = "https://example.org")
        val dao = db.dao()
        dao.saveSource(source)
        dao.saveChannels(listOf(channel))
        dao.saveMappings(listOf(ChannelEpgMapping(channel.id, source.id, "one", ChannelEpgMapping.MANUAL)))
        val now = System.currentTimeMillis()
        dao.savePrograms(listOf(Program(sourceId = source.id, generation = "old", epgId = "one", start = now - 1000, end = now + 60_000, title = "Old")))
        val format = DateTimeFormatter.ofPattern("uuuuMMddHHmmss Z").withZone(ZoneOffset.UTC)
        val start = format.format(Instant.ofEpochMilli(now))
        val stop = format.format(Instant.ofEpochMilli(now + 3_600_000))
        val header = "<tv><channel id=\"one\"><display-name>One</display-name></channel>"
        val invalid = "<programme channel=\"one\" start=\"$stop\" stop=\"$start\"><title>Invalid</title></programme>"
        val zero = "<programme channel=\"one\" start=\"$start\" stop=\"$start\"><title>Zero</title></programme>"
        file.writeText(header + (1..405).joinToString("") {
            "<programme channel=\"one\" start=\"$start\" stop=\"$stop\"><title>New $it</title></programme>"
        } + invalid + zero + "</tv>")
        assertTrue(importer.refresh(source.id))
        val published = requireNotNull(dao.source(source.id))
        assertNotEquals("old", published.generation)
        assertNull(published.error)
        assertTrue(published.lastSuccess > 0)
        assertEquals(405, published.count)
        assertEquals(405, dao.programs(listOf(channel.id), now, now + 60_000).first().size)
        file.writeText(header + invalid + zero + "</tv>")
        assertFalse(importer.refresh(source.id))
        assertEquals(published.generation, dao.source(source.id)?.generation)
        assertEquals(published.lastSuccess, dao.source(source.id)?.lastSuccess)
        assertEquals(405, dao.programs(listOf(channel.id), now, now + 60_000).first().size)
    }
    @Test fun logosFollowActiveGenerationAndRespectDisabledMapping() = runBlocking {
        val source = Source(kind = Source.EPG, name = "epg", location = "", generation = "active")
        db.dao().saveSource(source)
        db.dao().saveEpgChannels(listOf(
            EpgChannel(source.id, "old", "one", "One", logo = "https://example.org/old.png"),
            EpgChannel(source.id, "active", "one", "One", logo = "https://example.org/current.png")))
        val mapping = ChannelEpgMapping("channel", source.id, "one", ChannelEpgMapping.MANUAL)
        db.dao().saveMappings(listOf(mapping))
        assertEquals("https://example.org/current.png", db.dao().observeEpgLogos().first().single().logo)
        db.dao().saveMappings(listOf(mapping.copy(mode = ChannelEpgMapping.DISABLED)))
        assertTrue(db.dao().observeEpgLogos().first().isEmpty())
    }
    @Test fun migrationKeepsSourcesChannelsFavoritesAndManualMapping() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val name = "migration-design.db"
        context.deleteDatabase(name)
        val old = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null)
        val schema = org.json.JSONObject(File("schemas/home.iptv.core.database.IptvDatabase/1.json").readText()).getJSONObject("database")
        val entities = schema.getJSONArray("entities")
        for (index in 0 until entities.length()) {
            val entity = entities.getJSONObject(index)
            old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
            val indices = entity.optJSONArray("indices") ?: org.json.JSONArray()
            for (index in 0 until indices.length()) old.execSQL(indices.getJSONObject(index).getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
        }
        old.execSQL("INSERT INTO sources VALUES ('s','epg','EPG','https://example.org',24,1,0,'',0,100,100,NULL,1,'etag','modified','g','')")
        old.execSQL("INSERT INTO channels VALUES ('c','p','One','','https://example.org','one','','',0,1,NULL,'','one')")
        old.execSQL("INSERT INTO favorites VALUES ('c',0)")
        old.execSQL("INSERT INTO mappings VALUES ('c','s','one','manual')")
        old.execSQL("INSERT INTO epg_channels VALUES ('s','g','one','One','one')")
        old.version = 1
        old.close()
        val upgraded = Room.databaseBuilder(context, IptvDatabase::class.java, name)
            .addMigrations(IptvDatabase.MIGRATION_1_2).build()
        try {
            assertEquals("One", upgraded.dao().channel("c")?.name)
            assertEquals("c", upgraded.dao().favorites().single().channelId)
            assertEquals(ChannelEpgMapping.MANUAL, upgraded.dao().mappings().single().mode)
            assertEquals("", upgraded.dao().epgChannels().single().logo)
            assertNull(upgraded.dao().source("s")?.etag)
            assertEquals(0L, upgraded.dao().source("s")?.lastSuccess)
        } finally { upgraded.close(); context.deleteDatabase(name) }
    }
    @Test fun remapNeverOverwritesDisabledOrManualChoices() = runBlocking {
        val source = Source(kind = Source.EPG, name = "epg", location = "", generation = "g")
        db.dao().saveSource(source)
        db.dao().saveEpgChannels(listOf(EpgChannel(source.id, "g", "auto", "One")))
        db.dao().saveLinks(listOf(PlaylistEpg("p", source.id, 0)))
        val channel = Channel(playlistId = "p", name = "One", streamUrl = "https://example.org")
        db.dao().saveChannels(listOf(channel))
        for (mapping in listOf(ChannelEpgMapping(channel.id, mode = ChannelEpgMapping.DISABLED), ChannelEpgMapping(channel.id, "missing", "missing", ChannelEpgMapping.MANUAL))) {
            db.dao().saveMappings(listOf(mapping))
            db.withTransaction { importer.remap() }
            assertEquals(mapping, db.dao().mappings().single())
        }
    }
}
