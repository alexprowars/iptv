package home.iptv.feature.guide

import home.iptv.core.model.ChannelProgram
import home.iptv.core.model.Program
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal data class GuidePrograms(
    val from: Long,
    val to: Long,
    val programs: Map<String, List<Program>> = emptyMap(),
    val slots: Map<String, List<GuideSlot>> = emptyMap(),
    val loadedIds: Set<String> = emptySet()
)

/** Shared by dashboard and player guide for the lifetime of the ViewModel. */
internal class GuideProgramCache(
    private val scope: CoroutineScope,
    private val load: (List<String>, Long, Long) -> Flow<List<ChannelProgram>>,
    private val maxEntries: Int = 4,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    private data class Query(val ids: List<String>, val from: Long, val to: Long)
    private class Entry(val state: MutableStateFlow<GuidePrograms>, var users: Int = 0,
                        var job: Job? = null, var revision: Long = 0)
    private val entries = LinkedHashMap<Query, Entry>(16, .75f, true)
    private val lock = Any()
    private var revision = 0L

    init { require(maxEntries > 0) }

    private fun query(ids: List<String>, from: Long, to: Long) = Query(ids.distinct().sorted(), from, to)

    fun snapshot(ids: List<String>, from: Long, to: Long): GuidePrograms = synchronized(lock) {
        snapshot(query(ids, from, to))
    }

    private fun snapshot(query: Query): GuidePrograms {
        entries[query]?.let { return it.state.value }
        // A new page can immediately reuse the overlapping channels of already loaded pages.
        val requested = query.ids.toSet()
        val programs = mutableMapOf<String, List<Program>>()
        val slots = mutableMapOf<String, List<GuideSlot>>()
        val loaded = mutableSetOf<String>()
        entries.values.sortedByDescending { it.revision }.forEach { entry ->
            val data = entry.state.value
            if (data.from == query.from && data.to == query.to) {
                val known = data.loadedIds.filter { it in requested && it !in loaded }.toSet()
                programs.putAll(data.programs.filterKeys { it in known })
                slots.putAll(data.slots.filterKeys { it in known })
                loaded += known
            }
        }
        return GuidePrograms(query.from, query.to, programs, slots, loaded)
    }

    fun preload(ids: List<String>, from: Long, to: Long) {
        if (ids.isEmpty()) return
        synchronized(lock) { getOrCreate(query(ids, from, to)); trim() }
    }

    fun observe(ids: List<String>, from: Long, to: Long): Flow<GuidePrograms> {
        if (ids.isEmpty()) return flowOf(GuidePrograms(from, to))
        val query = query(ids, from, to)
        return flow {
            val entry = synchronized(lock) {
                getOrCreate(query).also { it.users++; trim() }
            }
            try { emitAll(entry.state) }
            finally { synchronized(lock) { entry.users--; trim() } }
        }
    }

    private fun getOrCreate(query: Query): Entry {
        entries[query]?.let { return it }
        val entry = Entry(MutableStateFlow(snapshot(query)))
        entries[query] = entry
        entry.job = scope.launch {
            load(query.ids, query.from, query.to).map { rows ->
                val programs = rows.groupBy({ it.channelId }, { it.program })
                val slots = query.ids.associateWith { guideSlots(programs[it].orEmpty(), query.from, query.to) }
                GuidePrograms(query.from, query.to, programs, slots, query.ids.toSet())
            }.flowOn(dispatcher).collect { data ->
                synchronized(lock) { entry.revision = ++revision; entry.state.value = data }
            }
        }
        return entry
    }

    private fun trim() {
        val iterator = entries.entries.iterator()
        while (entries.size > maxEntries && iterator.hasNext()) {
            val entry = iterator.next().value
            // Active screens stay live even while another screen or playback preloads its page.
            if (entry.users == 0) {
                iterator.remove()
                entry.job?.cancel()
            }
        }
    }
}
