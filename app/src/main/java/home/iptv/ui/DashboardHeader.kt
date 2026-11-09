package home.iptv.ui

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import home.iptv.ui.theme.IptvColors
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import home.iptv.core.model.Channel
import home.iptv.core.model.Program
import home.iptv.feature.player.PlaybackController
import home.iptv.feature.guide.guideTime
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

fun guideDate(now: Long): String = DateTimeFormatter.ofPattern("EE, d MMM · HH:mm", Locale.forLanguageTag("ru"))
    .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(now))

@Composable
fun ChannelLogo(channel: Channel, modifier: Modifier = Modifier) {
    var failed by remember(channel.logo) { mutableStateOf(channel.logo.isBlank()) }
    Box(modifier, contentAlignment = Alignment.Center) {
        if (failed) Box(Modifier.size(28.dp).background(IptvColors.Elevated, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
            Text(channel.name.take(2).uppercase(), color = IptvColors.Text, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        if (channel.logo.isNotBlank()) AsyncImage(model = channel.logo, contentDescription = "Логотип ${channel.name}",
            contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize(), onError = { failed = true }, onSuccess = { failed = false })
    }
}

@Composable
fun DashboardHeader(controller: PlaybackController, playing: Channel?, selected: Channel?, program: Program?,
                    now: Long, onWatch: () -> Unit, onRetry: () -> Unit,
                    onPreviewBounds: (Rect) -> Unit = {}) {
    val state by controller.state.collectAsState()
    var previewFocused by remember { mutableStateOf(false) }
    val pressedKeys = remember { mutableSetOf<Int>() }
    Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 12.dp).height(180.dp), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
        Box(Modifier.width(320.dp).fillMaxHeight().testTag("dashboard-preview")
            .onGloballyPositioned { onPreviewBounds(it.boundsInRoot()) }
            .clip(RoundedCornerShape(14.dp))
            .onFocusChanged {
                previewFocused = it.isFocused
                if (!it.isFocused) pressedKeys.clear()
            }
            .onPreviewKeyEvent { event ->
                val key = event.nativeKeyEvent
                if (key.keyCode !in listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                        KeyEvent.KEYCODE_NUMPAD_ENTER)) return@onPreviewKeyEvent false
                when (key.action) {
                    KeyEvent.ACTION_DOWN -> {
                        if (key.repeatCount == 0) pressedKeys += key.keyCode
                        else if (key.keyCode !in pressedKeys) return@onPreviewKeyEvent true
                    }
                    KeyEvent.ACTION_UP -> if (!pressedKeys.remove(key.keyCode)) return@onPreviewKeyEvent true
                }
                false
            }
            .clickable(enabled = playing != null, onClick = onWatch)
            .border(if (previewFocused) 2.dp else 1.dp, if (previewFocused) Accent else IptvColors.Outline, RoundedCornerShape(14.dp))) {
            if (playing == null) Text("Эфир не выбран", Modifier.align(Alignment.Center).padding(20.dp), color = Muted, fontSize = 14.sp)
            if (state.buffering) Text("Открываем эфир…", Modifier.align(Alignment.BottomStart).background(IptvColors.Background.copy(alpha = .8f)).padding(8.dp), color = Color.White, fontSize = 12.sp)
            if (state.error != null) Column(Modifier.align(Alignment.Center).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Эфир временно недоступен", color = Muted, fontSize = 14.sp)
                TvButton("Повторить", onRetry)
            }

        }
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(if (program != null && now in program.start until program.end) "СЕЙЧАС В ЭФИРЕ" else "ТЕЛЕПРОГРАММА",
                color = Accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp)
            Text(program?.title ?: selected?.name ?: "Ваш эфир", fontSize = 24.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (program != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("${guideTime(program.start, now)}–${time(program.end)}", color = Muted, fontSize = 15.sp)
                    if (now in program.start until program.end) {
                        Box(Modifier.width(50.dp).height(3.dp).background(IptvColors.Outline, RoundedCornerShape(2.dp))) {
                            Box(Modifier.fillMaxWidth(((now - program.start).toFloat() / (program.end - program.start)).coerceIn(0f, 1f))
                                .fillMaxHeight().background(Accent))
                        }
                        Text("Ещё ${(program.end - now + 59_999) / 60_000} мин", color = Muted, fontSize = 14.sp)
                    } else Text(if (program.start > now) "Далее" else "В эфире ранее", color = Accent, fontSize = 14.sp)
                }
                Text(program.description.ifBlank { "Описание передачи отсутствует" }, color = Muted, fontSize = 15.sp,
                    lineHeight = 20.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
            } else {
                Text("Телепрограмма для этого канала пока отсутствует", color = Muted, fontSize = 15.sp)
            }
        }
    }
}
