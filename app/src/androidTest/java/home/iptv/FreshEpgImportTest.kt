package home.iptv

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.testing.WorkManagerTestInitHelper
import home.iptv.core.database.IptvDatabase
import home.iptv.core.database.Repository
import home.iptv.core.model.*
import home.iptv.core.network.SourceLoader
import home.iptv.feature.sources.initialEpgPlaylists
import home.iptv.feature.sources.initialEpgSources
import home.iptv.sync.ImportCoordinator
import home.iptv.sync.RefreshScheduler
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Uses a separate in-memory database; installed user sources are never modified. */
@RunWith(AndroidJUnit4::class)
class FreshEpgImportTest {
    @Test fun playlistThenEpgImportsProgramsOnFirstSave() = checkFreshImport(epgFirst = false)
    @Test fun epgThenPlaylistImportsProgramsOnFirstSave() = checkFreshImport(epgFirst = true)

    private fun checkFreshImport(epgFirst: Boolean) = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().build())
        val db = Room.inMemoryDatabaseBuilder(context, IptvDatabase::class.java).build()
        val playlistFile = File.createTempFile("fresh-playlist", ".m3u", context.cacheDir)
        val epgFile = File.createTempFile("fresh-guide", ".xml", context.cacheDir)
        try {
            val importer = ImportCoordinator(db, SourceLoader(context))
            val repository = Repository(db, importer, RefreshScheduler(context))
            val dao = db.dao()
            val playlist = Source(name = "Fresh playlist", location = playlistFile.toURI().toString(), intervalHours = 0)
            val epg = Source(kind = Source.EPG, name = "Fresh EPG", location = epgFile.toURI().toString(), intervalHours = 0)
            playlistFile.writeText("#EXTM3U\n#EXTINF:-1 tvg-id=\"one\",One\nhttps://example.org/one")
            val now = System.currentTimeMillis()
            val format = DateTimeFormatter.ofPattern("uuuuMMddHHmmss Z").withZone(ZoneOffset.UTC)
            val start = format.format(Instant.ofEpochMilli(now - 60_000))
            val stop = format.format(Instant.ofEpochMilli(now + 3_600_000))
            epgFile.writeText("<tv><channel id=\"one\"><display-name>One</display-name></channel>" +
                "<programme channel=\"one\" start=\"$start\" stop=\"$stop\"><title>Fresh news</title></programme></tv>")
            // Apply precisely the initial selections and save operations used by SourceEditor.
            for (source in if (epgFirst) listOf(epg, playlist) else listOf(playlist, epg)) {
                val sources = dao.sources()
                val links = dao.links()
                val epgIds = initialEpgSources(source, sources, links)
                val playlists = initialEpgPlaylists(source, null, sources, links)
                repository.saveSource(source)
                if (source.kind == Source.PLAYLIST) repository.setEpgSources(source.id, epgIds)
                else repository.setEpgPlaylists(source.id, playlists)
                assertTrue(importer.refresh(source.id))
            }
            assertEquals(listOf(PlaylistEpg(playlist.id, epg.id, 0)), dao.links())
            assertEquals(1, dao.source(epg.id)?.count)
            val channel = dao.channels(playlist.id).single()
            assertEquals("Fresh news", dao.programs(listOf(channel.id), now, now + 60_000).first().single().program.title)
            // Editing must retain an explicitly empty selection.
            repository.setEpgPlaylists(epg.id, emptySet())
            assertTrue(initialEpgSources(playlist, dao.sources(), dao.links()).isEmpty())
            assertTrue(initialEpgPlaylists(epg, null, dao.sources(), dao.links()).isEmpty())
        } finally {
            db.close()
            playlistFile.delete()
            epgFile.delete()
        }
    }

    @Test fun newEpgLeavesExistingPrioritiesAndManualChoicesIntact() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().build())
        val db = Room.inMemoryDatabaseBuilder(context, IptvDatabase::class.java).build()
        try {
            val dao = db.dao()
            val importer = ImportCoordinator(db, SourceLoader(context))
            val repository = Repository(db, importer, RefreshScheduler(context))
            val first = Source(id = "first", name = "First", location = "")
            val second = Source(id = "second", name = "Second", location = "")
            val primary = Source(id = "primary", kind = Source.EPG, name = "Primary", location = "")
            val added = Source(id = "added", kind = Source.EPG, name = "Added", location = "")
            listOf(first, second, primary).forEach { dao.saveSource(it) }
            dao.saveLinks(listOf(PlaylistEpg(first.id, primary.id, 0)))
            val manual = ChannelEpgMapping("manual", primary.id, "id", ChannelEpgMapping.MANUAL)
            val disabled = ChannelEpgMapping("disabled", mode = ChannelEpgMapping.DISABLED)
            dao.saveMappings(listOf(manual, disabled))
            assertEquals(setOf(second.id), initialEpgPlaylists(added, null, dao.sources(), dao.links()))
            assertEquals(setOf(first.id), initialEpgPlaylists(added, first.id, dao.sources(), dao.links()))
            dao.saveSource(added)
            repository.setEpgPlaylists(added.id, setOf(first.id, second.id))
            assertEquals(listOf(primary.id, added.id), dao.links().filter { it.playlistId == first.id }.sortedBy { it.priority }.map { it.sourceId })
            repository.setEpgPlaylists(added.id, emptySet())
            assertEquals(listOf(PlaylistEpg(first.id, primary.id, 0)), dao.links())
            assertEquals(setOf(manual, disabled), dao.mappings().toSet())
        } finally { db.close() }
    }
}
