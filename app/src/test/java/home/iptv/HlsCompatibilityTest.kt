package home.iptv

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import home.iptv.feature.player.CompatibleHlsPlaylistParserFactory
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(UnstableApi::class)
class HlsCompatibilityTest {
    @Test fun incompleteVideoCodecDeclarationFallsBackToSegmentInspection() {
        val master = CompatibleHlsPlaylistParserFactory().createPlaylistParser().parse(
            Uri.parse("https://example.org/live/master.m3u8"), """
                #EXTM3U
                #EXT-X-INDEPENDENT-SEGMENTS
                #EXT-X-CONTENT-STEERING:SERVER-URI="https://example.org/steering.json",PATHWAY-ID="primary"
                #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="sound",NAME="Russian",DEFAULT=YES,AUTOSELECT=YES,URI="audio.m3u8"
                #EXT-X-STREAM-INF:BANDWIDTH=4280000,RESOLUTION=1024x576,CODECS="mp4a.40.2",AUDIO="sound"
                broken.m3u8?token=keep
                #EXT-X-STREAM-INF:BANDWIDTH=4280000,RESOLUTION=1024x576,CODECS="avc1.4d401f,mp4a.40.2"
                valid.m3u8
                #EXT-X-STREAM-INF:BANDWIDTH=128000,CODECS="mp4a.40.2"
                radio.m3u8
            """.trimIndent().byteInputStream()
        ) as HlsMultivariantPlaylist
        assertNull(master.variants[0].format.codecs)
        assertEquals(1024, master.variants[0].format.width)
        assertEquals("https://example.org/live/broken.m3u8?token=keep", master.variants[0].url.toString())
        assertEquals("sound", master.variants[0].audioGroupId)
        assertEquals("https://example.org/live/audio.m3u8", master.audios.single().url.toString())
        assertTrue(master.hasIndependentSegments)
        assertEquals("https://example.org/steering.json", master.contentSteeringInfo?.serverUri.toString())
        assertEquals("primary", master.contentSteeringInfo?.pathwayId)
        assertEquals("avc1.4d401f,mp4a.40.2", master.variants[1].format.codecs)
        assertEquals("mp4a.40.2", master.variants[2].format.codecs)
    }

    @Test fun mediaPlaylistsKeepEncryptionAndRelativeSegmentUris() {
        val factory = CompatibleHlsPlaylistParserFactory()
        val playlist = factory.createPlaylistParser(HlsMultivariantPlaylist.EMPTY, null).parse(
            Uri.parse("https://example.org/live/media.m3u8"), """
                #EXTM3U
                #EXT-X-TARGETDURATION:6
                #EXT-X-MEDIA-SEQUENCE:42
                #EXT-X-KEY:METHOD=AES-128,URI="key.bin",IV=0x00000000000000000000000000000001
                #EXTINF:6,
                segment.ts?token=keep
                #EXT-X-ENDLIST
            """.trimIndent().byteInputStream()
        ) as HlsMediaPlaylist
        assertEquals(42L, playlist.mediaSequence)
        assertEquals("segment.ts?token=keep", playlist.segments.single().url)
        assertEquals("key.bin", playlist.segments.single().fullSegmentEncryptionKeyUri)
        assertTrue(playlist.hasEndTag)
    }
}
