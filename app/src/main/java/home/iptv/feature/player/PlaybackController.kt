package home.iptv.feature.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import home.iptv.core.model.Channel
import home.iptv.core.network.parseHeaders
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

@OptIn(UnstableApi::class)
class PlaybackController(private val context: Context, private val scope: CoroutineScope,
                         private val onPlayed: (String) -> Unit) {
    data class State(val player: ExoPlayer? = null, val buffering: Boolean = false, val playing: Boolean = false,
                     val error: String? = null, val firstFrameMs: Long? = null,
                     val videoFormat: Format? = null, val audioFormat: Format? = null)
    private val mutable = MutableStateFlow(State())
    val state = mutable.asStateFlow()
    private var session: MediaSession? = null
    private var selection: Channel? = null
    private var headers: String = ""
    private var switchJob: Job? = null
    private var retries = 0
    private var startedAt = 0L
    private var activeId: String? = null
    private var saved = false
    private var forceHls = false

    fun start() {
        if (mutable.value.player != null) return
        val player = ExoPlayer.Builder(context).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            setHandleAudioBecomingNoisy(true)
            addAnalyticsListener(object : AnalyticsListener {
                override fun onVideoInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format, decoderReuseEvaluation: DecoderReuseEvaluation?) {
                    if (activeId == selection?.id) mutable.update { it.copy(videoFormat = format) }
                }
                override fun onAudioInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format, decoderReuseEvaluation: DecoderReuseEvaluation?) {
                    if (activeId == selection?.id) mutable.update { it.copy(audioFormat = format) }
                }
            })
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) { mutable.update { it.copy(buffering = playbackState == Player.STATE_BUFFERING) } }
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    mutable.update { it.copy(playing = isPlaying) }
                    if (isPlaying && !saved && activeId == selection?.id) { saved = true; activeId?.let(onPlayed) }
                }
                override fun onRenderedFirstFrame() {
                    val elapsed = android.os.SystemClock.elapsedRealtime() - startedAt
                    mutable.update { it.copy(firstFrameMs = elapsed) }
                    android.util.Log.i("IPTV_METRIC", "first_frame_ms=$elapsed")
                }
                override fun onPlayerError(error: PlaybackException) {
                    if (activeId != selection?.id) return
                    // Some providers serve HLS through extensionless URLs. Progressive sniffing
                    // fails in that case; retry once with the HLS parser before showing an error.
                    if (!forceHls && error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED) {
                        forceHls = true
                        switchJob?.cancel()
                        switchJob = scope.launch { open() }
                        return
                    }
                    mutable.update { it.copy(error = "Не удалось открыть поток (${error.errorCodeName})", buffering = false, playing = false) }
                    if (error.errorCode in listOf(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT) && retries < 2) {
                        retries++
                        switchJob?.cancel()
                        switchJob = scope.launch { delay(retries * 1500L); open() }
                    }
                }
            })
        }
        session = MediaSession.Builder(context, player).build()
        mutable.value = State(player = player)
        open()
    }
    fun select(channel: Channel, sourceHeaders: String) {
        if (selection?.id == channel.id) return
        selection = channel; headers = sourceHeaders; retries = 0; forceHls = false
        switchJob?.cancel()
        mutable.value.player?.stop()
        mutable.update { it.copy(buffering = true, error = null, firstFrameMs = null, videoFormat = null, audioFormat = null) }
        switchJob = scope.launch { delay(140); open() }
    }
    private fun open() {
        val channel = selection ?: return
        val player = mutable.value.player ?: return
        activeId = channel.id; saved = false
        val requestHeaders = runCatching { parseHeaders(headers) + parseHeaders(channel.headers) }.getOrDefault(emptyMap())
        val http = DefaultHttpDataSource.Factory().setConnectTimeoutMs(15_000).setReadTimeoutMs(20_000)
            .setAllowCrossProtocolRedirects(true).setDefaultRequestProperties(requestHeaders)
        val item = MediaItem.Builder().setMediaId(channel.id).setUri(channel.streamUrl)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(channel.name).build())
            .apply { if (forceHls) setMimeType(MimeTypes.APPLICATION_M3U8) }.build()
        val source = if (forceHls || Util.inferContentType(android.net.Uri.parse(channel.streamUrl)) == C.CONTENT_TYPE_HLS) {
            HlsMediaSource.Factory(http).setPlaylistParserFactory(CompatibleHlsPlaylistParserFactory()).createMediaSource(item)
        } else DefaultMediaSourceFactory(context).setDataSourceFactory(http).createMediaSource(item)
        startedAt = android.os.SystemClock.elapsedRealtime()
        mutable.update { it.copy(error = null, buffering = true, firstFrameMs = null, videoFormat = null, audioFormat = null) }
        player.setMediaSource(source)
        player.prepare()
        player.play()
    }
    fun retry() { retries = 0; switchJob?.cancel(); open() }
    fun stop() {
        switchJob?.cancel()
        session?.release(); session = null
        mutable.value.player?.release()
        mutable.value = State()
        activeId = null
    }
}
