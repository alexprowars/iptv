package home.iptv.feature.guide

import home.iptv.core.model.Program
import kotlin.math.max
import kotlin.math.min

data class GuideSlot(val start: Long, val end: Long, val program: Program?)
fun guideSlots(programs: List<Program>, from: Long, to: Long): List<GuideSlot> {
    val result = mutableListOf<GuideSlot>()
    var cursor = from
    for (program in programs.sortedBy { it.start }) {
        if (program.end <= cursor || program.start >= to) continue
        if (program.start > cursor) result += GuideSlot(cursor, min(program.start, to), null)
        val start = max(cursor, program.start)
        val end = min(to, program.end)
        if (end > start) result += GuideSlot(start, end, program)
        cursor = max(cursor, end)
    }
    if (cursor < to) result += GuideSlot(cursor, to, null)
    return result
}

