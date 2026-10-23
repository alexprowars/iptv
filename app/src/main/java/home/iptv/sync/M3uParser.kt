package home.iptv.sync

import home.iptv.core.model.*
import java.io.Reader
import java.net.URI

class ImportException(message: String) : Exception(message)

object M3uParser {
    private val attribute = Regex("([\\w-]+)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s]+))")
    private fun attributes(line: String): Map<String, String> = attribute.findAll(line).associate {
        it.groupValues[1].lowercase() to it.groupValues.drop(2).firstOrNull(String::isNotEmpty).orEmpty()
    }
    private fun splitInfo(line: String): Pair<String, String> {
        var quote: Char? = null
        line.forEachIndexed { index, char ->
            if (char == '"' || char == '\'') { if (quote == char) quote = null else if (quote == null) quote = char }
            if (char == ',' && quote == null) return line.substring(0, index) to line.substring(index + 1).trim()
        }
        throw ImportException("В EXTINF отсутствует название после запятой")
    }
    fun resolve(base: String, value: String): String = runCatching { URI(base).resolve(value.trim()).toString() }.getOrDefault(value.trim())
    fun streamError(url: String): String? = when (runCatching { URI(url).scheme?.lowercase() }.getOrNull()) {
        "http", "https" -> if (runCatching { URI(url).host }.getOrNull().isNullOrBlank()) "Некорректный адрес потока" else null
        else -> "Поддерживаются только HTTP(S)-потоки"
    }
    fun parse(reader: Reader, playlistId: String, base: String): ParsedPlaylist {
        val channels = mutableListOf<Channel>()
        val epg = linkedSetOf<String>()
        var pending: Pair<Map<String, String>, String>? = null
        var headers = linkedMapOf<String, String>()
        var sawHeader = false
        // Bound lines as well as total bytes (the downloader enforces the latter).
        boundedLines(reader, 32_768).forEach { original ->
            val line = original.trim().removePrefix("\uFEFF")
            if (line.isEmpty()) return@forEach
            if (!sawHeader) {
                if (!line.startsWith("#EXTM3U")) throw ImportException("Файл не является плейлистом M3U")
                sawHeader = true
            }
            when {
                line.startsWith("#EXTM3U") -> {
                    val attrs = attributes(line)
                    listOf("url-tvg", "x-tvg-url", "tvg-url").forEach { key ->
                        attrs[key]?.split(',')?.filter(String::isNotBlank)?.forEach { epg += resolve(base, it) }
                    }
                }
                line.startsWith("#EXT-X-") -> throw ImportException("Это HLS-поток, а не каталог каналов. Откройте его через «Открыть поток».")
                line.startsWith("#EXTINF:") -> {
                    if (pending != null) throw ImportException("У канала отсутствует адрес потока")
                    val (info, name) = splitInfo(line)
                    pending = attributes(info) to name
                }
                line.startsWith("#EXTGRP:") -> pending = pending?.let { (attrs, name) -> (attrs + ("group-title" to line.substringAfter(':'))) to name }
                line.startsWith("#EXTVLCOPT:") -> {
                    val option = line.substringAfter(':').substringBefore('=')
                    val key = when (option) { "http-user-agent" -> "User-Agent"; "http-referrer" -> "Referer"; else -> null }
                    if (key != null) headers[key] = line.substringAfter('=')
                }
                line.startsWith('#') -> Unit
                else -> {
                    val (attrs, name) = pending ?: throw ImportException("Адрес без EXTINF: повреждённый каталог")
                    val url = resolve(base, line)
                    val error = streamError(url)
                    channels += Channel(playlistId = playlistId, name = name.ifBlank { attrs["tvg-name"] ?: "Канал ${channels.size + 1}" },
                        groupName = attrs["group-title"].orEmpty(), streamUrl = url, tvgId = attrs["tvg-id"].orEmpty(),
                        tvgName = attrs["tvg-name"].orEmpty(), logo = attrs["tvg-logo"]?.let { resolve(base, it) }.orEmpty(),
                        position = channels.size, available = error == null, unavailableReason = error,
                        headers = headers.entries.joinToString("\n") { "${it.key}: ${it.value}" })
                    if (channels.size > 50_000) throw ImportException("Лимит: 50 000 каналов в плейлисте")
                    pending = null; headers = linkedMapOf()
                }
            }
        }
        if (pending != null || channels.isEmpty()) throw ImportException("Плейлист пуст или не завершён; прежние каналы сохранены")
        return ParsedPlaylist(channels, epg.toList())
    }
}

internal fun boundedLines(reader: Reader, limit: Int): Sequence<String> = sequence {
    val buffered = reader.buffered()
    val line = StringBuilder()
    while (true) {
        val char = buffered.read()
        if (char == -1) { if (line.isNotEmpty()) yield(line.toString()); break }
        if (char == 10) { yield(line.toString()); line.setLength(0) }
        else { line.append(char.toChar()); if (line.length > limit) throw ImportException("Слишком длинная строка источника") }
    }
}
