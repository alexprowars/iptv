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
import home.iptv.ui.theme.IptvColors
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
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
import home.iptv.feature.guide.GuideProgramCell
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
                               modifier: Modifier = Modifier, interactive: Boolean = true, buildStage: Int = Int.MAX_VALUE) {
    val current = programs.firstOrNull { now in it.start until it.end }
    val next = programs.filter { it.start > now }.minByOrNull { it.start }
    val zone = java.time.ZoneId.systemDefault()
    val badges = remember(state.videoFormat, state.audioFormat) { streamBadges(state.videoFormat, state.audioFormat) }
    val currentTime = remember(current?.start, current?.end, zone) { current?.let(::timeRange) }
    val nextText = remember(next, zone) { next?.let { "Далее  ${timeRange(it)}  ${it.title}" } }
    val background = remember { Brush.verticalGradient(listOf(IptvColors.Background.copy(alpha = .82f), IptvColors.Background.copy(alpha = .95f), IptvColors.Background.copy(alpha = .98f))) }
    Column(modifier.fillMaxWidth().background(background)
        .padding(start = 32.dp, end = 32.dp, top = 54.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(22.dp), verticalAlignment = Alignment.CenterVertically) {
            ChannelLogo(channel, Modifier.width(118.dp).height(70.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(current?.title ?: channel.name, fontSize = 24.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (buildStage >= 2) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    current?.let {
                        Text(currentTime.orEmpty(), color = Muted, fontSize = 15.sp)
                        Text("Ещё ${(it.end - now + 59_999) / 60_000} мин", color = Muted, fontSize = 14.sp)
                    }
                    Text(channel.name, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (buildStage >= 2 && current == null) Text("Телепрограмма отсутствует", color = Muted, fontSize = 14.sp)
                if (buildStage >= 2) nextText?.let { Text(it, color = Muted, fontSize = 14.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
        if (buildStage >= 3) current?.description?.takeIf { it.isNotBlank() }?.let {
            Text(it, color = IptvColors.Muted, fontSize = 14.sp, lineHeight = 19.sp,
                maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 140.dp))
        }
        if (buildStage >= 4) {
            if (badges.isNotEmpty()) FlowRow(Modifier.padding(start = 140.dp), horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                badges.forEach { label -> Text(label, color = IptvColors.Text, fontSize = 11.sp,
                    modifier = Modifier.background(IptvColors.Elevated, RoundedCornerShape(3.dp)).padding(horizontal = 7.dp, vertical = 3.dp)) }
            } else Text(if (state.error != null) "Параметры потока недоступны" else "Определяем параметры потока…", color = Muted,
                fontSize = 12.sp, modifier = Modifier.padding(start = 140.dp))
            current?.let {
                Box(Modifier.fillMaxWidth().height(3.dp).background(IptvColors.Outline, RoundedCornerShape(2.dp))) {
                    Box(Modifier.fillMaxWidth(((now - it.start).toFloat() / (it.end - it.start)).coerceIn(0f, 1f))
                        .fillMaxHeight().background(Accent))
                }
            }
        }
        state.error?.let { Text(it, color = IptvColors.Error, fontSize = 13.sp, maxLines = 2) }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(9.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
            item(key = "guide") {
                LaunchedEffect(interactive) { if (interactive) { withFrameNanos { }; focus.requestFocus() } }
                GuideProgramCell({ if (interactive) onGuide() }, Modifier.width(128.dp).height(100.dp).focusRequester(focus).semantics { role = Role.Button },
                    onLongClick = null, containerColor = IptvColors.Elevated.copy(alpha = .9f), focusedContainerColor = IptvColors.Focus) {
                    Column(Modifier.fillMaxSize().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceEvenly) {
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
            items(recentChannels.take((buildStage - 4).coerceAtLeast(0)), key = { "recent:${it.id}" }) { recent ->
                val program = recentPrograms[recent.id]
                var focused by remember(recent.id) { mutableStateOf(false) }
                GuideProgramCell({ if (interactive) onRecent(recent) }, Modifier.width(128.dp).height(100.dp)
                    .onFocusChanged { focused = it.isFocused }.semantics { contentDescription = "Смотреть ${recent.name}"; role = Role.Button },
                    onLongClick = null, containerColor = IptvColors.Elevated.copy(alpha = .9f), focusedContainerColor = IptvColors.Focus) {
                    Column(Modifier.fillMaxSize().padding(vertical = 7.dp), verticalArrangement = Arrangement.SpaceBetween, horizontalAlignment = Alignment.CenterHorizontally) {
                        ChannelLogo(recent, Modifier.fillMaxWidth().height(53.dp))
                        Text(program?.title ?: "Нет информации", color = if (focused) IptvColors.OnAccent else if (program != null) Accent else Muted,
                            fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Box(Modifier.fillMaxWidth().height(2.dp).background(IptvColors.Outline)) {
                            program?.let { p -> Box(Modifier.fillMaxWidth(((now - p.start).toFloat() / (p.end - p.start)).coerceIn(0f, 1f))
                                .fillMaxHeight().background(Accent)) }
                        }
                    }
                }
            }
            if (state.error != null) item(key = "retry") {
                GuideProgramCell({ if (interactive) onRetry() }, Modifier.width(128.dp).height(100.dp).semantics { role = Role.Button }, onLongClick = null,
                    containerColor = IptvColors.Elevated.copy(alpha = .9f), focusedContainerColor = IptvColors.Focus) { Text("Повторить") }
            }
        }
    }
}
