package home.iptv

import home.iptv.core.model.*
import home.iptv.core.network.*
import home.iptv.feature.guide.guideSlots
import home.iptv.sync.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.zip.GZIPOutputStream

class ParserTest {
    private fun m3u(text: String) = M3uParser.parse(text.reader(), "playlist", "https://example.org/path/list.m3u")
    private fun channel(id: String, tvg: String = "", url: String = "https://example.org/$id", name: String = id) = Channel(id = id, playlistId = "p", name = name, streamUrl = url, tvgId = tvg)
    @Test fun bomQuotesCommasCrLfAndRelativeUrls() {
        val parsed = m3u("\uFEFF#EXTM3U x-tvg-url=\"guide.xml.gz\"\r\n#EXTINF:-1 tvg-id=\"1\" tvg-name=\"One\" group-title=\"News, world\" tvg-logo=\"logo.png\",News, HD\r\nstream.m3u8?token=abc\r\n")
        val item = parsed.channels.single()
        assertEquals("News, HD", item.name)
        assertEquals("News, world", item.groupName)
        assertEquals("https://example.org/path/stream.m3u8?token=abc", item.streamUrl)
        assertEquals("https://example.org/path/logo.png", item.logo)
        assertEquals(listOf("https://example.org/path/guide.xml.gz"), parsed.epgUrls)
    }
    @Test fun supportsSingleQuotedAttributesAndHeaders() {
        val item = m3u("#EXTM3U\n#EXTINF:-1 tvg-id='abc' group-title='Two words',One\n#EXTVLCOPT:http-user-agent=Provider TV\n#EXTVLCOPT:http-referrer=https://example.org\nhttps://example.org/one\n").channels.single()
        assertEquals("abc", item.tvgId)
        assertEquals("Two words", item.groupName)
        assertEquals("Provider TV", parseHeaders(item.headers)["User-Agent"])
    }
    @Test fun rejectsHlsMasterAndMediaManifests() {
        for (tag in listOf("#EXT-X-STREAM-INF:BANDWIDTH=100", "#EXT-X-TARGETDURATION:6")) {
            assertThrows(ImportException::class.java) { m3u("#EXTM3U\n$tag\n#EXTINF:6,segment\nsegment.ts") }
        }
    }
    @Test fun rejectsEmptyTruncatedAndInvalidCatalogs() {
        for (text in listOf("", "#EXTM3U", "<html>error</html>", "#EXTM3U\n#EXTINF:-1,One", "#EXTM3U\nurl")) {
            assertThrows(ImportException::class.java) { m3u(text) }
        }
    }
    @Test fun unsupportedStreamIsRetainedWithReason() {
        val item = m3u("#EXTM3U\n#EXTINF:-1,Multicast\nudp://@239.0.0.1:1234").channels.single()
        assertFalse(item.available); assertNotNull(item.unavailableReason)
    }
    @Test fun rejectsOversizedLine() { assertThrows(ImportException::class.java) { m3u("#EXTM3U\n#EXTINF:-1," + "a".repeat(40_000)) } }
    @Test fun reorderingAndTokenChangesKeepIds() {
        val old = listOf(channel("old-a", "a"), channel("old-b", "b"))
        val fresh = listOf(channel("new-b", "b", "https://new/b?token=2"), channel("new-a", "a", "https://new/a?token=2"))
        assertEquals(listOf("old-b", "old-a"), ChannelMatcher.reconcile(old, fresh).map { it.id })
    }
    @Test fun duplicateTvgIdsUseUniqueUrlsWithoutMergingHdSd() {
        val old = listOf(channel("hd", "same", name = "News HD"), channel("sd", "same", name = "News SD"))
        val fresh = old.reversed().map { it.copy(id = "new-${it.id}") }
        assertEquals(listOf("sd", "hd"), ChannelMatcher.reconcile(old, fresh).map { it.id })
    }
    @Test fun ambiguousChannelsDoNotInheritIdentity() {
        val old = listOf(channel("a", "duplicate", name = "News"), channel("b", "duplicate", name = "News"))
        val fresh = listOf(channel("x", "duplicate", name = "News"), channel("y", "duplicate", name = "News"))
        val result = ChannelMatcher.reconcile(old, fresh)
        assertEquals(setOf("x", "y"), result.filter { it.available }.map { it.id }.toSet())
        assertEquals(setOf("a", "b"), result.filterNot { it.available }.map { it.id }.toSet())
    }
    @Test fun removedAndReturnedChannelRecoversOriginalId() {
        val old = channel("original", "id")
        val deleted = ChannelMatcher.reconcile(listOf(old), emptyList())
        assertFalse(deleted.single().available)
        val returned = ChannelMatcher.reconcile(deleted, listOf(channel("new", "id")))
        assertEquals("original", returned.single().id); assertTrue(returned.single().available)
    }
    @Test fun uniqueNameAndGroupCanRecoverTokenChange() {
        val old = channel("a", name = "One").copy(groupName = "Group")
        val fresh = channel("new", name = "One").copy(groupName = "Group")
        assertEquals("a", ChannelMatcher.reconcile(listOf(old), listOf(fresh)).single().id)
    }
    @Test fun normalizesRussianSearch() { assertEquals("еще новости", normalize("  ЕЩЁ   Новости ")) }
    @Test fun handlesXmlTimeOffsetsAndUtcDefault() {
        val utc = Instant.parse("2026-10-23T09:00:00Z").toEpochMilli()
        assertEquals(utc, XmlTvParser.timestamp("20261023120000 +0300"))
        assertEquals(utc, XmlTvParser.timestamp("20261023050000 -0400"))
        assertEquals(utc, XmlTvParser.timestamp("20261023090000"))
        assertEquals(utc, XmlTvParser.timestamp("202610230900 Z"))
    }
    private val now = Instant.parse("2026-10-23T09:00:00Z").toEpochMilli()
    private val xml = """<?xml version="1.0"?><tv><channel id="one"><display-name>Ёлка &amp; TV</display-name></channel><programme channel="one" start="20261023120000 +0300" stop="20261023130000 +0300"><title>Новости</title><desc>Описание</desc></programme></tv>"""
    @Test fun parsesXmlAndGzipWithoutDoubleInflation() = runTest {
        val compressed = ByteArrayOutputStream().apply { GZIPOutputStream(this).use { it.write(xml.toByteArray()) } }.toByteArray()
        for (bytes in listOf(xml.toByteArray(), compressed)) {
            val channels = mutableListOf<EpgChannel>(); val programs = mutableListOf<Program>()
            decodedStream(ByteArrayInputStream(bytes), 100_000).use {
                XmlTvParser.parse(it, Source(kind = Source.EPG, name = "test", location = "file:///test"), "generation", now, { channels += it }, { programs += it })
            }
            assertEquals("Ёлка & TV", channels.single().names)
            assertEquals(now, programs.single().start)
            assertEquals("Описание", programs.single().description)
        }
    }
    @Test fun ignoresPlainExternalDoctypeAndCombinesDuplicateChannelAliases() = runTest {
        val input = xml.replace("<tv>", "<!DOCTYPE tv SYSTEM \"http://127.0.0.1:1/xmltv.dtd\"><tv>")
            .replace("</channel>", "</channel><channel id=\"one\"><display-name>Ёлка HD</display-name></channel>")
        val channels = mutableListOf<EpgChannel>()
        XmlTvParser.parse(input.byteInputStream(), Source(name = "test", location = ""), "g", now, { channels += it }, {})
        assertEquals("Ёлка & TV\nЁлка HD", channels.last().names)
    }
    @Test fun channelIconsResolveRelativeUrlsAndSurviveDuplicateAliases() = runTest {
        val input = xml.replace("</channel>", "<icon src=\"../logos/one.png\"/></channel><channel id=\"one\"><display-name>Ёлка HD</display-name></channel>")
            .replace("</programme>", "<icon src=\"https://example.org/poster.jpg\"/></programme>")
        val channels = mutableListOf<EpgChannel>()
        XmlTvParser.parse(input.byteInputStream(), Source(name = "test", location = "https://example.org/epg/guide.xml"), "g", now, { channels += it }, {})
        assertEquals("https://example.org/logos/one.png", channels.last().logo)
        assertEquals("Ёлка & TV\nЁлка HD", channels.last().names)
    }
    @Test fun appliesCorrectionAndFiltersOutsideWindow() = runTest {
        val programs = mutableListOf<Program>()
        val input = xml.replace("</tv>", "<programme channel=\"one\" start=\"20251023000000 Z\" stop=\"20251023010000 Z\"><title>Old</title></programme></tv>")
        XmlTvParser.parse(input.byteInputStream(), Source(name = "test", location = "", timeShiftMinutes = -60), "g", now, {}, { programs += it })
        assertEquals(now - 3_600_000, programs.single().start)
    }
    @Test fun skipsNonPositiveDurationsAndContinuesParsing() = runTest {
        val input = xml.replace("</tv>", """
            <programme channel="one" start="20261023130000 +0300" stop="20261023130000 +0300"><title>Нулевая длительность</title></programme>
            <programme channel="one" start="20261023150000 +0300" stop="20261023140000 +0300"><title>Обратный интервал</title></programme>
            <programme channel="one" start="20261023130000 +0300" stop="20261023120000 +0200"><title>Один момент в разных поясах</title></programme>
            <programme channel="one" start="20261023130000 +0300" stop="20261023140000 +0300"><title>Следующая передача</title></programme>
            </tv>
        """.trimIndent())
        val programs = mutableListOf<Program>()
        val count = XmlTvParser.parse(input.byteInputStream(), Source(name = "test", location = "", timeShiftMinutes = -60),
            "g", now, {}, { programs += it })
        assertEquals(2, count)
        assertEquals(listOf("Новости", "Следующая передача"), programs.map { it.title })
        assertTrue(programs.all { it.end > it.start })
        assertEquals(now - 3_600_000, programs.first().start)
    }
    @Test fun rejectsFeedContainingOnlyNonPositiveDurations() = runTest {
        val input = xml.replace("20261023130000 +0300", "20261023120000 +0300")
        var failure: ImportException? = null
        try { XmlTvParser.parse(input.byteInputStream(), Source(name = "test", location = ""), "g", now, {}, {}) }
        catch (e: ImportException) { failure = e }
        assertNotNull(failure)
        assertTrue(failure!!.message.orEmpty().contains("прежняя программа сохранена"))
    }
    @Test fun rejectsMalformedXmlDtdAndEmptyPeriod() = runTest {
        for (input in listOf(xml.dropLast(8), "<tv/>", xml.replace("<tv>", "<!DOCTYPE tv [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><tv>"), xml.replace("20261023", "20251023"))) {
            var failed = false
            try { XmlTvParser.parse(input.byteInputStream(), Source(name = "test", location = ""), "g", now, {}, {}) } catch (_: Exception) { failed = true }
            assertTrue(input.take(50), failed)
        }
    }
    @Test fun boundsDecompressedBytes() {
        val compressed = ByteArrayOutputStream().apply { GZIPOutputStream(this).use { it.write(ByteArray(20_000)) } }.toByteArray()
        assertThrows(ImportException::class.java) { decodedStream(compressed.inputStream(), 1_000).use { it.readBytes() } }
    }
    @Test fun invalidGzipFails() { assertThrows(java.io.IOException::class.java) { decodedStream(byteArrayOf(0x1f, 0x8b.toByte(), 0).inputStream(), 1000).readBytes() } }
    @Test fun mappingUsesPriorityButExactIdBeatsName() {
        val candidates = listOf(EpgChannel("first", "g", "other", "News"), EpgChannel("second", "g", "id", "Something"))
        val links = listOf(PlaylistEpg("p", "first", 0), PlaylistEpg("p", "second", 1))
        assertEquals("second", EpgIndex(candidates).match(channel("channel", "id", name = "News"), links)?.sourceId)
    }
    @Test fun mappingDoesNotGuessAmbiguousNames() {
        val epg = listOf(EpgChannel("epg", "g", "one", "News"), EpgChannel("epg", "g", "two", "NEWS"))
        assertNull(EpgIndex(epg).match(channel("c", name = "news"), listOf(PlaylistEpg("p", "epg", 0))))
    }
    @Test fun guideFillsGapsAndClipsOverlap() {
        fun p(start: Long, end: Long) = Program(sourceId = "s", generation = "g", epgId = "e", start = start, end = end, title = "t")
        val slots = guideSlots(listOf(p(-5, 20), p(10, 30), p(50, 70)), 0, 100)
        assertEquals(listOf(0L to 20L, 20L to 30L, 30L to 50L, 50L to 70L, 70L to 100L), slots.map { it.start to it.end })
        assertNull(slots[2].program); assertNull(slots.last().program)
    }
}
