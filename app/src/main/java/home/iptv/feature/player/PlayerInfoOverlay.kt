package home.iptv.feature.player

import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.tv.material3.LocalContentColor
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.tv.material3.Text
import home.iptv.core.model.Channel
import home.iptv.core.model.Program
import home.iptv.ui.*
import java.util.Locale

@OptIn(UnstableApi::class)
internal fun streamBadges(video: Format?, audio: Format?): List<String> = buildList {
    video?.let {
        if (it.width > 0 && it.height > 0) add("${it.width}×${it.height}")
        if (it.frameRate > 0) add("${String.format(Locale.ROOT, "%.2f", it.frameRate).trimEnd('0').trimEnd('.')} FPS")
        it.sampleMimeType?.let { mime -> add(when (mime) {
            MimeTypes.VIDEO_H264 -> "AVC"
            MimeTypes.VIDEO_H265 -> "HEVC"
            MimeTypes.VIDEO_AV1 -> "AV1"
            MimeTypes.VIDEO_VP9 -> "VP9"
            MimeTypes.VIDEO_MPEG2 -> "MPEG-2"
            else -> mime.substringAfter('/').uppercase(Locale.ROOT)
        }) }
        when (it.colorInfo?.colorTransfer) {
            C.COLOR_TRANSFER_ST2084 -> add("HDR10")
            C.COLOR_TRANSFER_HLG -> add("HLG")
        }
        if (it.bitrate > 0) add(String.format(Locale.ROOT, "%.1f Мбит/с", it.bitrate / 1_000_000f))
    }
    audio?.let {
        it.sampleMimeType?.let { mime -> add(when (mime) {
            MimeTypes.AUDIO_AAC -> "AAC"
            MimeTypes.AUDIO_AC3 -> "AC-3"
            MimeTypes.AUDIO_E_AC3 -> "E-AC-3"
            MimeTypes.AUDIO_MPEG -> "MP3"
            else -> mime.substringAfter('/').uppercase(Locale.ROOT)
        }) }
        when (it.channelCount) {
            1 -> add("МОНО")
            2 -> add("СТЕРЕО")
            6 -> add("5.1")
            8 -> add("7.1")
            in 3..Int.MAX_VALUE -> add("${it.channelCount} каналов")
        }
    }
}

@Composable
internal fun PlayerInfoOverlay(channel: Channel, programs: List<Program>, state: PlaybackController.State,
                               now: Long, focus: FocusRequester, recentChannels: List<Channel>, recentPrograms: Map<String, Program>,
                               onRecent: (Channel) -> Unit, onRetry: () -> Unit, onGuide: () -> Unit,
                               modifier: Modifier = Modifier) {
    val current = programs.firstOrNull { now in it.start until it.end }
    val next = programs.filter { it.start > now }.minByOrNull { it.start }
    val badges = streamBadges(state.videoFormat, state.audioFormat)
    Column(modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xEC0C1013), Color(0xFA0C1013))))
        .padding(start = 32.dp, end = 32.dp, top = 54.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(22.dp), verticalAlignment = Alignment.CenterVertically) {
            ChannelLogo(channel, Modifier.width(118.dp).height(70.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(current?.title ?: channel.name, fontSize = 24.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    current?.let {
                        Text(timeRange(it), color = Muted, fontSize = 15.sp)
                        Text("Ещё ${(it.end - now + 59_999) / 60_000} мин", color = Muted, fontSize = 14.sp)
                    }
                    Text(channel.name, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (current == null) Text("Телепрограмма отсутствует", color = Muted, fontSize = 14.sp)
                next?.let { Text("Далее  ${timeRange(it)}  ${it.title}", color = Muted, fontSize = 14.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
        current?.description?.takeIf { it.isNotBlank() }?.let {
            Text(it, color = Color(0xFFC6CCD1), fontSize = 14.sp, lineHeight = 19.sp,
                maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 140.dp))
        }
        if (badges.isNotEmpty()) FlowRow(Modifier.padding(start = 140.dp), horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            badges.forEach { label -> Text(label, color = Color(0xFFDFE5E9), fontSize = 11.sp,
                modifier = Modifier.background(Color(0xFF3D454C), RoundedCornerShape(3.dp)).padding(horizontal = 7.dp, vertical = 3.dp)) }
        } else Text(if (state.error != null) "Параметры потока недоступны" else "Определяем параметры потока…", color = Muted,
            fontSize = 12.sp, modifier = Modifier.padding(start = 140.dp))
        current?.let {
            Box(Modifier.fillMaxWidth().height(3.dp).background(Color(0xFF57616A), RoundedCornerShape(2.dp))) {
                Box(Modifier.fillMaxWidth(((now - it.start).toFloat() / (it.end - it.start)).coerceIn(0f, 1f))
                    .fillMaxHeight().background(Accent))
            }
        }
        state.error?.let { Text(it, color = Color(0xFFFFB6AA), fontSize = 13.sp, maxLines = 2) }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(9.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
            item(key = "guide") {
                LaunchedEffect(Unit) { withFrameNanos { }; focus.requestFocus() }
                TvButton("", onGuide, Modifier.width(128.dp).height(100.dp).focusRequester(focus),
                    contentPadding = PaddingValues(10.dp), containerColor = Color(0xE6222629)) {
                    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceEvenly) {
                        val ink = LocalContentColor.current
                        Canvas(Modifier.size(32.dp)) {
                            repeat(3) { row -> repeat(3) { col ->
                                drawRect(ink, Offset(size.width * col / 3, size.height * row / 3), Size(size.width / 3 - 2.dp.toPx(), size.height / 3 - 2.dp.toPx()))
                            } }
                        }
                        Text("Телегид", fontSize = 16.sp)
                    }
                }
            }
            items(recentChannels, key = { "recent:${it.id}" }) { recent ->
                val program = recentPrograms[recent.id]
                var focused by remember(recent.id) { mutableStateOf(false) }
                TvButton("", { onRecent(recent) }, Modifier.width(128.dp).height(100.dp)
                    .onFocusChanged { focused = it.isFocused }.semantics { contentDescription = "Смотреть ${recent.name}" },
                    contentPadding = PaddingValues(9.dp), containerColor = Color(0xE6222629)) {
                    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween, horizontalAlignment = Alignment.CenterHorizontally) {
                        ChannelLogo(recent, Modifier.fillMaxWidth().height(53.dp))
                        Text(program?.title ?: "Нет информации", color = if (focused) Color(0xFF205574) else if (program != null) Accent else Muted,
                            fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Box(Modifier.fillMaxWidth().height(2.dp).background(Color(0xFF535B61))) {
                            program?.let { p -> Box(Modifier.fillMaxWidth(((now - p.start).toFloat() / (p.end - p.start)).coerceIn(0f, 1f))
                                .fillMaxHeight().background(Accent)) }
                        }
                    }
                }
            }
            if (state.error != null) item(key = "retry") { TvButton("Повторить", onRetry, Modifier.height(100.dp)) }
        }
    }
}
