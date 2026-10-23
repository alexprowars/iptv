package home.iptv.feature.guide

import androidx.compose.runtime.*
import home.iptv.core.model.Channel

private const val PAGE_SIZE = 16

internal fun guideQueryIds(channels: List<Channel>, firstIndex: Int): List<String> {
    val page = firstIndex.coerceAtLeast(0) / PAGE_SIZE * PAGE_SIZE
    return channels.subList((page - PAGE_SIZE).coerceIn(0, channels.size),
        (page + 2 * PAGE_SIZE).coerceIn(0, channels.size)).map { it.id }
}

@Composable
internal fun rememberGuidePrograms(cache: GuideProgramCache, ids: List<String>, from: Long, to: Long): GuidePrograms =
    key(cache, ids, from, to) {
        val initial = remember { cache.snapshot(ids, from, to) }
        val data by remember { cache.observe(ids, from, to) }.collectAsState(initial)
        data
    }
