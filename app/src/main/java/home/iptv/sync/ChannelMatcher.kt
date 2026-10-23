package home.iptv.sync

import home.iptv.core.model.*

object ChannelMatcher {
    fun reconcile(old: List<Channel>, fresh: List<Channel>): List<Channel> {
        val matches = mutableMapOf<String, String>() // fresh ID -> persistent ID
        val used = mutableSetOf<String>()
        // Count uniqueness across the entire generation, not only unmatched records.
        // Otherwise duplicate HD/SD identifiers can become falsely unique after the first match.
        val keys: List<(Channel) -> String> = listOf({ it.tvgId }, { it.streamUrl }, { "${it.name}\u0000${it.groupName}" })
        for (key in keys) {
            val previous = old.groupBy(key)
            val incoming = fresh.groupBy(key)
            for ((value, candidates) in incoming) {
                if (value.isBlank() || candidates.size != 1) continue
                val before = previous[value]?.singleOrNull() ?: continue
                val after = candidates.single()
                if (after.id !in matches && before.id !in used) { matches[after.id] = before.id; used += before.id }
            }
        }
        val result = fresh.map { it.copy(id = matches[it.id] ?: it.id) }
        return result + old.filter { it.id !in used }.map { it.copy(available = false, unavailableReason = "Канал исчез или изменился неоднозначно. Выберите замену в действиях канала.") }
    }
}
