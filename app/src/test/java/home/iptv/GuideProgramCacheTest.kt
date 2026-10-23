package home.iptv

import home.iptv.core.model.ChannelProgram
import home.iptv.core.model.Program
import home.iptv.feature.guide.GuideProgramCache
import home.iptv.feature.guide.GuidePrograms
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GuideProgramCacheTest {
    private data class Query(val ids: List<String>, val from: Long, val to: Long)
    private class Fixture(scope: CoroutineScope, dispatcher: CoroutineDispatcher, limit: Int = 4) {
        val requests = mutableListOf<Query>()
        private val streams = mutableMapOf<Query, MutableStateFlow<List<ChannelProgram>>>()
        fun rows(ids: List<String>, from: Long = 0, to: Long = 10) =
            streams.getOrPut(Query(ids.sorted(), from, to)) { MutableStateFlow(emptyList()) }
        val cache = GuideProgramCache(scope, { ids, from, to ->
            requests += Query(ids, from, to)
            rows(ids, from, to)
        }, limit, dispatcher)
    }
    private fun program(title: String = "Live", channel: String = "A") = ChannelProgram(
        Program(id = "$channel-$title", sourceId = "epg", generation = "g", epgId = channel,
            start = 0, end = 10, title = title), channel)

    @Test fun dashboardAndReopenedGuideShareAlreadyLoadedDataWithoutAnotherQuery() = runTest {
        val f = Fixture(backgroundScope, StandardTestDispatcher(testScheduler))
        val ids = listOf("A", "B")
        f.rows(ids).value = listOf(program())
        val dashboard = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            f.cache.observe(ids, 0, 10).collect()
        }
        runCurrent()
        dashboard.cancel(); runCurrent()
        assertEquals("Live", f.cache.snapshot(ids, 0, 10).programs["A"]!!.single().title)
        val reopened = mutableListOf<GuidePrograms>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            f.cache.observe(ids.reversed(), 0, 10).collect { reopened += it }
        }
        runCurrent()
        assertEquals(1, f.requests.size)
        assertEquals("Live", reopened.first().slots["A"]!!.single().program!!.title)
        assertTrue("B" in reopened.first().loadedIds)
        assertNull(reopened.first().slots["B"]!!.single().program)
    }

    @Test fun preloadIsReadyBeforeTheGuideHasAnyCollectors() = runTest {
        val f = Fixture(backgroundScope, StandardTestDispatcher(testScheduler))
        f.rows(listOf("A")).value = listOf(program())
        f.cache.preload(listOf("A"), 0, 10)
        runCurrent()
        val initial = f.cache.snapshot(listOf("A"), 0, 10)
        assertEquals(setOf("A"), initial.loadedIds)
        assertEquals("Live", initial.slots["A"]!!.single().program!!.title)
    }

    @Test fun epgRefreshAndMappingRemovalUpdateTheCacheWhileTheGuideIsClosed() = runTest {
        val f = Fixture(backgroundScope, StandardTestDispatcher(testScheduler))
        val rows = f.rows(listOf("A"))
        rows.value = listOf(program())
        f.cache.preload(listOf("A"), 0, 10); runCurrent()
        rows.value = listOf(program("Updated")); runCurrent()
        assertEquals("Updated", f.cache.snapshot(listOf("A"), 0, 10).programs["A"]!!.single().title)
        rows.value = emptyList(); runCurrent()
        val cleared = f.cache.snapshot(listOf("A"), 0, 10)
        assertTrue(cleared.programs.isEmpty())
        assertEquals(setOf("A"), cleared.loadedIds)
        assertNull(cleared.slots["A"]!!.single().program)
        assertEquals(1, f.requests.size)
    }

    @Test fun aDifferentTimeWindowCannotReuseOutdatedCoverage() = runTest {
        val f = Fixture(backgroundScope, StandardTestDispatcher(testScheduler))
        f.rows(listOf("A")).value = listOf(program())
        f.cache.preload(listOf("A"), 0, 10); runCurrent()
        val future = f.cache.snapshot(listOf("A"), 10, 20)
        assertTrue(future.loadedIds.isEmpty())
        assertTrue(future.slots.isEmpty())
        f.cache.preload(listOf("A"), 10, 20); runCurrent()
        assertNull(f.cache.snapshot(listOf("A"), 10, 20).slots["A"]!!.single().program)
        assertEquals("Live", f.cache.snapshot(listOf("A"), 0, 10).programs["A"]!!.single().title)
    }

    @Test fun overlappingPagesReuseOnlyChannelsWhoseCoverageIsKnown() = runTest {
        val f = Fixture(backgroundScope, StandardTestDispatcher(testScheduler))
        f.rows(listOf("A", "B")).value = listOf(program(channel = "B"))
        f.cache.preload(listOf("A", "B"), 0, 10); runCurrent()
        val next = f.cache.snapshot(listOf("B", "C"), 0, 10)
        assertEquals(setOf("B"), next.loadedIds)
        assertEquals("Live", next.slots["B"]!!.single().program!!.title)
        assertFalse("C" in next.slots)
    }

    @Test fun inactiveLeastRecentlyUsedPagesAreEvictedAndTheirQueriesStop() = runTest {
        val f = Fixture(backgroundScope, StandardTestDispatcher(testScheduler), limit = 2)
        for (id in listOf("A", "B", "C")) { f.cache.preload(listOf(id), 0, 10); runCurrent() }
        assertTrue(f.cache.snapshot(listOf("A"), 0, 10).loadedIds.isEmpty())
        assertEquals(0, f.rows(listOf("A")).subscriptionCount.value)
        assertEquals(setOf("B"), f.cache.snapshot(listOf("B"), 0, 10).loadedIds)
        assertEquals(setOf("C"), f.cache.snapshot(listOf("C"), 0, 10).loadedIds)
    }

    @Test fun anOverlappingPageWithRemovedProgramsTakesPrecedenceOverOlderCachedRows() = runTest {
        val f = Fixture(backgroundScope, StandardTestDispatcher(testScheduler))
        f.rows(listOf("A", "B")).value = listOf(program())
        f.cache.preload(listOf("A", "B"), 0, 10); runCurrent()
        f.cache.preload(listOf("A", "C"), 0, 10); runCurrent()
        // Touching an older page must not make its stale programme more recent than a DB update.
        f.cache.snapshot(listOf("A", "B"), 0, 10)
        val merged = f.cache.snapshot(listOf("A", "D"), 0, 10)
        assertEquals(setOf("A"), merged.loadedIds)
        assertFalse("A" in merged.programs)
        assertNull(merged.slots["A"]!!.single().program)
    }

    @Test fun preloadingOtherPagesDoesNotEvictThePageUsedByAnActiveScreen() = runTest {
        val f = Fixture(backgroundScope, StandardTestDispatcher(testScheduler), limit = 1)
        val observed = mutableListOf<GuidePrograms>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            f.cache.observe(listOf("A"), 0, 10).collect { observed += it }
        }
        runCurrent()
        f.cache.preload(listOf("B"), 0, 10); runCurrent()
        f.rows(listOf("A")).value = listOf(program("Refreshed")); runCurrent()
        assertEquals("Refreshed", observed.last().programs["A"]!!.single().title)
        assertEquals(1, f.rows(listOf("A")).subscriptionCount.value)
        assertEquals(0, f.rows(listOf("B")).subscriptionCount.value)
    }
}
