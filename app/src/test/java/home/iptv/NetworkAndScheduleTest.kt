package home.iptv

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.*
import androidx.work.testing.WorkManagerTestInitHelper
import home.iptv.core.database.IptvDatabase
import home.iptv.core.model.Source
import home.iptv.core.network.SourceLoader
import home.iptv.sync.ImportCoordinator
import home.iptv.sync.RefreshScheduler
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class NetworkAndScheduleTest {
    private lateinit var context: Application
    private lateinit var server: MockWebServer
    private lateinit var db: IptvDatabase
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        server = MockWebServer().apply { start() }
        db = Room.inMemoryDatabaseBuilder(context, IptvDatabase::class.java).build()
    }
    @After fun cleanup() { server.shutdown(); db.close() }
    @Test fun conditionalRefreshKeepsCatalogAndUsesValidators() = runBlocking {
        val source = Source(name = "test", location = server.url("/list").toString())
        db.dao().saveSource(source)
        val importer = ImportCoordinator(db, SourceLoader(context))
        server.enqueue(MockResponse().setHeader("ETag", "v1").setHeader("Last-Modified", "Fri, 23 Oct 2026 09:00:00 GMT").setBody("#EXTM3U\n#EXTINF:-1,One\nhttps://example.org/one"))
        assertTrue(importer.refresh(source.id))
        val before = db.dao().channels(source.id)
        server.takeRequest()
        server.enqueue(MockResponse().setResponseCode(304))
        assertTrue(importer.refresh(source.id))
        val request = server.takeRequest()
        assertEquals("v1", request.getHeader("If-None-Match"))
        assertEquals("Fri, 23 Oct 2026 09:00:00 GMT", request.getHeader("If-Modified-Since"))
        assertEquals(before, db.dao().channels(source.id))
        assertNull(db.dao().source(source.id)?.error)
    }
    @Test fun httpContentEncodingAndGzipFileAreDecodedExactlyOnce() = runBlocking {
        val body = "<tv>plain xml content</tv>"
        val gzip = ByteArrayOutputStream().apply { GZIPOutputStream(this).use { it.write(body.toByteArray()) } }.toByteArray()
        for (encoded in listOf(true, false)) {
            val response = MockResponse().setBody(Buffer().write(gzip))
            if (encoded) response.setHeader("Content-Encoding", "gzip")
            server.enqueue(response)
            SourceLoader(context).open(Source(kind = Source.EPG, name = "test", location = server.url("/epg.xml.gz").toString())).use {
                assertEquals(body, it.input!!.bufferedReader().readText())
            }
        }
    }
    @Test fun httpErrorDoesNotExposePrivateUrlOrLoseChannels() = runBlocking {
        val source = Source(name = "test", location = server.url("/private?token=secret-token").toString())
        db.dao().saveSource(source)
        server.enqueue(MockResponse().setResponseCode(403))
        assertFalse(ImportCoordinator(db, SourceLoader(context)).refresh(source.id))
        val error = db.dao().source(source.id)!!.error.orEmpty()
        assertTrue(error.contains("403")); assertFalse(error.contains("secret-token"))
    }
    @Test fun cancellationOfSlowBodyKeepsPublishedCatalog() = runBlocking {
        val source = Source(name = "test", location = server.url("/slow").toString())
        db.dao().saveSource(source)
        val importer = ImportCoordinator(db, SourceLoader(context))
        server.enqueue(MockResponse().setBody("#EXTM3U\n#EXTINF:-1,One\nhttps://example.org/one"))
        assertTrue(importer.refresh(source.id)); server.takeRequest()
        val before = db.dao().channels(source.id)
        server.enqueue(MockResponse().setBody("#EXTM3U\n#EXTINF:-1,Two\nhttps://example.org/two").setBodyDelay(2, TimeUnit.SECONDS))
        val job = launch(Dispatchers.IO) { importer.refresh(source.id) }
        withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) }
        job.cancelAndJoin()
        assertEquals(before, db.dao().channels(source.id))
        assertTrue(importer.progress.value.isEmpty())
    }
    @Test fun intervalChangeManualAndSnapshotLeaveNoDuplicateWork() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor { it.run() }.build())
        val scheduler = RefreshScheduler(context)
        val work = WorkManager.getInstance(context)
        val source = Source(name = "test", location = "https://example.org", intervalHours = 1)
        scheduler.schedule(source)
        fun active() = work.getWorkInfosForUniqueWork("source-${source.id}").get().filter { !it.state.isFinished }
        assertEquals(1, active().size)
        assertEquals(3_600_000L, active().single().periodicityInfo!!.repeatIntervalMillis)
        scheduler.schedule(source.copy(intervalHours = 3))
        assertEquals(1, active().size)
        assertEquals(10_800_000L, active().single().periodicityInfo!!.repeatIntervalMillis)
        scheduler.schedule(source.copy(intervalHours = 0))
        assertTrue(active().isEmpty())
        scheduler.schedule(source.copy(snapshot = true))
        assertTrue(active().isEmpty())
    }
}
