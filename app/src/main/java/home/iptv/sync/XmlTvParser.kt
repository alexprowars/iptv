package home.iptv.sync

import home.iptv.core.model.*
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import java.io.FilterInputStream
import java.time.*
import java.time.format.DateTimeFormatter

object XmlTvParser {
    private val stamp = Regex("^(\\d{14}|\\d{12}|\\d{10}|\\d{8})(?:\\s*([+-]\\d{4}|Z))?$")
    private val ignoredDoctype = Regex("""\s*tv(?:\s+SYSTEM\s+(?:"[^"<>\[\]]*"|'[^'<>\[\]]*')|\s+PUBLIC\s+(?:"[^"<>\[\]]*"|'[^'<>\[\]]*')\s+(?:"[^"<>\[\]]*"|'[^'<>\[\]]*'))?\s*""")
    fun timestamp(value: String): Long {
        val match = stamp.matchEntire(value.trim()) ?: throw ImportException("Некорректное время XMLTV")
        val local = LocalDateTime.parse(match.groupValues[1].padEnd(14, '0'), DateTimeFormatter.ofPattern("uuuuMMddHHmmss"))
        // XMLTV specifies UTC when the timezone is absent.
        val zone = match.groupValues[2].ifBlank { "Z" }
        return local.toInstant(ZoneOffset.of(zone)).toEpochMilli()
    }
    suspend fun parse(input: InputStream, source: Source, generation: String, now: Long,
                      onChannel: suspend (EpgChannel) -> Unit, onProgram: suspend (Program) -> Unit): Int {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        // Some XmlPull implementations cannot set this optional feature, but report it disabled.
        runCatching { parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false) }
        if (parser.getFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL)) throw ImportException("Нельзя безопасно отключить DTD")
        parser.setInput(XmlTokenLimitStream(input), null)
        val from = now - 86_400_000L
        val to = now + 7 * 86_400_000L
        var root = false
        var closed = false
        var count = 0
        val channelNames = HashMap<String, List<String>>()
        var aliasCharacters = 0
        val programmeChannels = HashSet<String>()
        var channelId = ""
        var channelLogo = ""
        val channelLogos = HashMap<String, String>()
        var names = mutableListOf<String>()
        var programId = ""
        var start = 0L; var end = 0L
        var title = ""; var description = ""
        var field: String? = null
        val text = StringBuilder()
        while (true) {
            when (parser.nextToken()) {
                XmlPullParser.END_DOCUMENT -> break
                XmlPullParser.DOCDECL -> {
                    // XMLTV commonly declares an external DTD. With PROCESS_DOCDECL disabled,
                    // it is never fetched. Only a plain declaration is accepted; internal subsets
                    // and entity declarations are rejected rather than expanded.
                    if (root || !ignoredDoctype.matches(parser.text.orEmpty())) throw ImportException("Внутренние DTD и XML-сущности запрещены")
                }
                XmlPullParser.START_TAG -> {
                    if (parser.depth > 16) throw ImportException("Слишком глубокий XMLTV")
                    if (parser.depth == 1) { if (parser.name != "tv") throw ImportException("Ожидается XMLTV с корнем tv"); root = true }
                    when (parser.name) {
                        "channel" -> if (parser.depth == 2) { channelId = parser.getAttributeValue(null, "id").orEmpty(); names = mutableListOf(); channelLogo = "" }
                        "programme" -> if (parser.depth == 2) {
                            programId = parser.getAttributeValue(null, "channel").orEmpty()
                            val shift = source.timeShiftMinutes * 60_000L
                            start = timestamp(parser.getAttributeValue(null, "start").orEmpty()) + shift
                            end = timestamp(parser.getAttributeValue(null, "stop").orEmpty()) + shift
                            title = ""; description = ""
                        }
                        "icon" -> if (parser.depth == 3 && channelId.isNotBlank()) {
                            val raw = parser.getAttributeValue(null, "src").orEmpty()
                            val resolved = runCatching { java.net.URI(source.location).resolve(raw) }.getOrNull()
                            if (raw.isNotBlank() && resolved?.scheme in listOf("http", "https") && !resolved?.host.isNullOrBlank()) channelLogo = resolved.toString()
                        }
                        "display-name", "title", "desc" -> if (parser.depth == 3) { field = parser.name; text.setLength(0) }
                    }
                }
                XmlPullParser.ENTITY_REF -> {
                    val entity = parser.name.orEmpty()
                    if (entity !in setOf("amp", "lt", "gt", "apos", "quot") && !entity.startsWith('#')) throw ImportException("XML-сущности запрещены")
                    if (field != null) text.append(parser.text.orEmpty())
                }
                XmlPullParser.TEXT, XmlPullParser.CDSECT -> if (field != null) text.append(parser.text)
                XmlPullParser.END_TAG -> {
                    if (parser.depth == 3 && parser.name == field) {
                        val value = text.toString().trim()
                        when (field) { "display-name" -> if (names.size < 30) names += value; "title" -> if (title.isBlank()) title = value; "desc" -> if (description.isBlank()) description = value }
                        field = null
                    }
                    if (parser.depth == 2 && parser.name == "channel") {
                        if (channelId.isBlank() || channelId.length > 1024) throw ImportException("XMLTV: некорректный ID канала")
                        val previous = channelNames[channelId].orEmpty()
                        val merged = (previous + names.ifEmpty { listOf(channelId) }).distinct()
                        if (merged.size > 64 || merged.any { it.length > 1024 }) throw ImportException("XMLTV: слишком много или слишком длинные имена канала")
                        aliasCharacters += merged.sumOf { it.length } - previous.sumOf { it.length }
                        if (aliasCharacters > 8_000_000) throw ImportException("XMLTV: превышен объём имён каналов")
                        channelNames[channelId] = merged
                        if (channelNames.size > 100_000) throw ImportException("XMLTV: лимит 100 000 каналов")
                        if (channelLogo.isNotBlank()) channelLogos[channelId] = channelLogo
                        onChannel(EpgChannel(source.id, generation, channelId, merged.joinToString("\n"), logo = channelLogos[channelId].orEmpty()))
                        channelId = ""
                    }
                    if (parser.depth == 2 && parser.name == "programme") {
                        if (programId.isBlank() || programId.length > 1024 || title.isBlank()) throw ImportException("XMLTV: передача без канала или названия")
                        // Providers sometimes include zero-length markers or reversed intervals.
                        // Discard those records without inventing a duration or aborting the feed.
                        if (end > start && end > from && start < to) { programmeChannels += programId; onProgram(Program(sourceId = source.id, generation = generation, epgId = programId, start = start, end = end, title = title, description = description)); count++ }
                    }
                    if (parser.depth == 1 && parser.name == "tv") closed = true
                }
            }
            if (text.length > 32_768) throw ImportException("Слишком длинное поле XMLTV")
        }
        if (!root || !closed) throw ImportException("XMLTV повреждён или загружен не полностью; прежняя программа сохранена")
        if (count == 0 || channelNames.isEmpty()) throw ImportException("В XMLTV нет передач за выбранный период; прежняя программа сохранена")
        if (!channelNames.keys.containsAll(programmeChannels)) throw ImportException("XMLTV: передача ссылается на неизвестный канал")
        return count
    }
}

/** Reject a huge XML token before the pull parser can allocate an unbounded text buffer. */
private class XmlTokenLimitStream(input: InputStream) : FilterInputStream(input) {
    private var length = 0
    private fun check(value: Int) {
        if (value == '<'.code || value == '>'.code) length = 0
        else if (value >= 0 && ++length > 65_536) throw ImportException("Слишком длинное поле или атрибут XMLTV")
    }
    override fun read(): Int = `in`.read().also(::check)
    override fun read(buffer: ByteArray, offset: Int, count: Int): Int {
        val size = `in`.read(buffer, offset, count)
        for (index in offset until offset + size.coerceAtLeast(0)) check(buffer[index].toInt() and 255)
        return size
    }
}
