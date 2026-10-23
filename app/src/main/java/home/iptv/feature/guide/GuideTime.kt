package home.iptv.feature.guide

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val russian = Locale.forLanguageTag("ru")
private val clockFormat = DateTimeFormatter.ofPattern("HH:mm")
private val dayFormat = DateTimeFormatter.ofPattern("EE, d", russian)
private val monthFormat = DateTimeFormatter.ofPattern("LLL", russian)

internal fun guideTime(value: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val date = Instant.ofEpochMilli(value).atZone(zone)
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val clock = clockFormat.format(date)
    return if (date.toLocalDate() == today) clock
    else "${dayFormat.format(date)} ${monthFormat.format(date).trimEnd('.')}, $clock"
}
