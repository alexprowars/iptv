package home.iptv.feature.player

import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.hls.playlist.*
import androidx.media3.exoplayer.upstream.ParsingLoadable

/** Some IPTV masters declare a video resolution but list only the audio codec. */
@OptIn(UnstableApi::class)
class CompatibleHlsPlaylistParserFactory : HlsPlaylistParserFactory {
    private val delegate = DefaultHlsPlaylistParserFactory()
    override fun createPlaylistParser() = wrap(delegate.createPlaylistParser())
    override fun createPlaylistParser(master: HlsMultivariantPlaylist, previous: HlsMediaPlaylist?) =
        wrap(delegate.createPlaylistParser(master, previous))

    private fun wrap(parser: ParsingLoadable.Parser<HlsPlaylist>) = ParsingLoadable.Parser<HlsPlaylist> { uri, input ->
        val playlist = parser.parse(uri, input)
        if (playlist !is HlsMultivariantPlaylist) playlist else {
            var changed = false
            val variants = playlist.variants.map { variant ->
                val format = variant.format
                val codecs = format.codecs
                val audioOnly = !codecs.isNullOrBlank() && codecs.split(',').all {
                    MimeTypes.getMediaMimeType(it.trim())?.let(MimeTypes::isAudio) == true
                }
                if (format.width > 0 && format.height > 0 && audioOnly) {
                    // Unknown codecs make Media3 inspect the media segments instead of preparing
                    // an audio-only track group and discarding the undeclared H.264 video.
                    changed = true
                    variant.copyWithFormat(format.buildUpon().setCodecs(null).build())
                } else variant
            }
            if (!changed) playlist else HlsMultivariantPlaylist(
                playlist.baseUri, playlist.tags, variants, playlist.videos, playlist.audios,
                playlist.subtitles, playlist.closedCaptions, playlist.muxedAudioFormat,
                playlist.muxedCaptionFormats, playlist.hasIndependentSegments,
                playlist.variableDefinitions, playlist.sessionKeyDrmInitData, playlist.contentSteeringInfo
            )
        }
    }
}
