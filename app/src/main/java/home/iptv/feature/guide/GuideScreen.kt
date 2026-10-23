@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package home.iptv.feature.guide

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import home.iptv.IptvViewModel
import home.iptv.core.model.*
import home.iptv.ui.*

private const val HOUR = 3_600_000L
// The TV default animates every focused row to a 30% pivot, even when it is already visible.
private val GuideBringIntoViewSpec = object : BringIntoViewSpec {}

private suspend fun LazyListState.revealGuideChannel(index: Int) {
    val info = layoutInfo
    val row = info.visibleItemsInfo.firstOrNull { it.index == index }
    val top = info.viewportStartOffset + info.beforeContentPadding
    val bottom = info.viewportEndOffset - info.afterContentPadding
    if (row != null && row.offset >= top && row.offset + row.size <= bottom) return
    val first = info.visibleItemsInfo.firstOrNull()
    if (first == null || index <= first.index) scrollToItem(index)
    else scrollToItem(index, -(bottom - (row?.size ?: first.size)).coerceAtLeast(0))
}

class GuidePosition(channel: String, time: Long) {
    val channelState = mutableStateOf(channel)
    val timeState = mutableLongStateOf(time)
    companion object {
        val Saver = listSaver<GuidePosition, Any>(
            save = { listOf(it.channelState.value, it.timeState.longValue) },
            restore = { GuidePosition(it[0] as String, it[1] as Long) }
        )
    }
}

@Composable
fun GuideScreen(vm: IptvViewModel, channels: List<Channel>, listState: LazyListState, now: Long,
                start: Long, onStart: (Long) -> Unit, position: GuidePosition,
                onMenu: (() -> Unit)? = null, onActions: ((Channel) -> Unit)? = null,
                suspended: Boolean = false, playingId: String? = null, translucent: Boolean = false,
                header: (@Composable (Channel?, Program?) -> Unit)? = null, interactive: Boolean = true,
                onProgram: (Channel, Program?) -> Unit) {
    val requestedEnd = start + 2 * HOUR
    var selectedChannel by position.channelState
    val selectedTime by position.timeState
    var requestSerial by remember { mutableIntStateOf(0) }
    var focusedInitially by remember { mutableStateOf(false) }
    var gridFocused by remember { mutableStateOf(false) }
    var handledSerial by remember { mutableIntStateOf(-1) }
    val focus = remember { FocusRequester() }
    val channelWidth = 250.dp
    val channelIndices = remember(channels) { channels.withIndex().associate { it.value.id to it.index } }
    val selectedIndex = channelIndices[selectedChannel] ?: 0
    val queryIds by remember(channels, listState) { derivedStateOf {
        val visible = listState.layoutInfo.visibleItemsInfo
        val target = channelIndices[position.channelState.value] ?: 0
        // Query a buffered page, not a new set of rows on every D-pad press.
        val first = visible.firstOrNull()?.index ?: target
        val last = visible.lastOrNull()?.index ?: target
        guideQueryIds(channels, if (target in first..last) first else target)
    } }
    val requestedData = rememberGuidePrograms(vm.guideCache, queryIds, start, requestedEnd)
    var retainedData by remember(vm.guideCache) { mutableStateOf(requestedData) }
    // Keep the measured rows and focus attached until the next window has its real EPG.
    val data = if (requestedData.loadedIds.isNotEmpty()) requestedData else retainedData
    SideEffect { if (requestedData.loadedIds.isNotEmpty()) retainedData = requestedData }
    val gridStart = data.from
    val end = data.to
    LaunchedEffect(queryIds, start, requestedData.loadedIds.isNotEmpty()) {
        if (requestedData.loadedIds.isEmpty()) return@LaunchedEffect
        if (start > now / HOUR * HOUR) vm.guideCache.preload(queryIds, start - HOUR, start + HOUR)
        vm.guideCache.preload(queryIds, requestedEnd, requestedEnd + 2 * HOUR)
    }
    val programs = data.programs
    val selectedLoaded = data.from == start && selectedChannel in data.loadedIds
    var initialGridReady by remember(channels) { mutableStateOf(false) }
    var initialRowCount by remember(channels) { mutableIntStateOf(2) }
    val initialRowIndex = if (initialGridReady) 0 else listState.firstVisibleItemIndex
    LaunchedEffect(channels, data.loadedIds.isNotEmpty()) {
        if (data.loadedIds.isEmpty()) return@LaunchedEffect
        // Spread the first viewport's text and logo composition across frames while video keeps playing.
        while (!initialGridReady) {
            withFrameNanos { }
            val visibleCount = listState.layoutInfo.visibleItemsInfo.size
            if ((visibleCount > 0 && initialRowCount >= visibleCount) || initialRowCount >= 32) initialGridReady = true
            else initialRowCount += 2
        }
    }
    val currentNow = rememberUpdatedState(now)
    val onSeek: (Long) -> Unit = remember(gridStart, end, onStart, position) { { time ->
        val bounded = time.coerceIn(currentNow.value, currentNow.value + 7 * 86_400_000L - 1)
        position.timeState.longValue = bounded
        if (bounded < gridStart || bounded >= end) onStart((bounded / HOUR) * HOUR)
        requestSerial++
    } }
    val onMove: (String) -> Unit = remember(position) { { id ->
        position.channelState.value = id
        requestSerial++
    } }
    val onFocused: (Channel, GuideSlot) -> Unit = remember(vm, position) { { channel, slot ->
        position.channelState.value = channel.id
        vm.lastFocused = channel.id
        if (slot.end > currentNow.value) position.timeState.longValue = position.timeState.longValue
            .coerceIn(maxOf(slot.start, currentNow.value), slot.end - 1)
    } }
    val onGridExit: () -> Unit = remember { { gridFocused = false } }
    LaunchedEffect(now, selectedTime) {
        if (selectedTime < now) {
            position.timeState.longValue = now
            if (now !in start until requestedEnd) onStart(now / HOUR * HOUR)
            requestSerial++
        }
    }
    LaunchedEffect(channels, selectedChannel) {
        if (channels.none { it.id == selectedChannel }) selectedChannel =
            channels.find { it.id == vm.playback.value?.channel?.id }?.id ?: channels.firstOrNull()?.id.orEmpty()
    }
    // Position the list immediately; loading EPG must not delay the jump to the channel.
    LaunchedEffect(selectedChannel, channels) {
        if (selectedChannel.isNotBlank()) listState.revealGuideChannel(selectedIndex)
    }
    val targetProgramId = programs[selectedChannel]?.firstOrNull { selectedTime in it.start until it.end }?.id
    LaunchedEffect(requestSerial, selectedChannel, start, selectedLoaded, targetProgramId, suspended, initialGridReady) {
        if (suspended) { focusedInitially = false; return@LaunchedEffect }
        if (selectedLoaded && initialGridReady && selectedChannel.isNotBlank() &&
            (requestSerial != handledSerial || !focusedInitially || gridFocused)) {
            listState.revealGuideChannel(selectedIndex)
            withFrameNanos { }
            if (runCatching { focus.requestFocus() }.getOrDefault(false)) {
                focusedInitially = true; handledSerial = requestSerial
            }
        }
    }
    val selected = channels.getOrNull(selectedIndex)
    val selectedProgram = programs[selectedChannel]?.firstOrNull { selectedTime in it.start until it.end }
    BoxWithConstraints(Modifier.fillMaxSize().testTag("guide-grid")) {
        val gridWidth = (maxWidth - channelWidth).coerceAtLeast(0.dp)
        Column(Modifier.fillMaxSize()) {
            header?.invoke(selected, selectedProgram)
            Row(Modifier.fillMaxWidth().height(38.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.width(channelWidth), verticalAlignment = Alignment.CenterVertically) {
                    Text(guideDate(now), color = Accent, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                }
                BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
                    val labels = remember(gridStart, end, now) {
                        val count = if (guideTime(gridStart, now).length > 5 || guideTime(end, now).length > 5) 3 else 5
                        List(count) { guideTime(gridStart + it * (end - gridStart) / (count - 1), now) }
                    }
                    labels.forEachIndexed { index, label ->
                        Text(label, color = Muted, fontSize = 14.sp, maxLines = 1,
                            modifier = if (index == labels.lastIndex) Modifier.align(Alignment.CenterEnd)
                            else Modifier.align(Alignment.CenterStart).offset(x = maxWidth * (index.toFloat() / (labels.size - 1))))
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF303538)))
            if (channels.isEmpty()) Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Здесь пока нет каналов", style = MaterialTheme.typography.headlineSmall)
                onMenu?.let { TvButton("Источники", it) }
            }
            Box(Modifier.weight(1f)) {
                // Build the grid once with its first EPG page, avoiding a throwaway grid of gap cells.
                if (data.loadedIds.isNotEmpty()) CompositionLocalProvider(LocalBringIntoViewSpec provides GuideBringIntoViewSpec) {
                    LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(3.dp),
                        contentPadding = PaddingValues(vertical = 4.dp), modifier = Modifier.fillMaxSize().onFocusChanged {
                            if (it.hasFocus) gridFocused = true
                        }.focusGroup()) {
                        itemsIndexed(channels, key = { _, channel -> channel.id }) { channelIndex, channel ->
                            if (initialGridReady || channelIndex in initialRowIndex until initialRowIndex + initialRowCount) {
                                GuideChannelRow(channelIndex, channel, channels, data.slots[channel.id],
                                    gridWidth, channelWidth, position, gridStart, end, now, focus, channel.id == playingId,
                                    translucent, interactive, onMenu, onActions, onProgram, onSeek, onMove, onFocused, onGridExit)
                            } else Spacer(Modifier.fillMaxWidth().height(38.dp))
                        }
                    }
                }
                // One continuous line stays aligned while the rows scroll beneath it.
                if (now in gridStart until end && channels.isNotEmpty()) BoxWithConstraints(Modifier.fillMaxSize().padding(start = channelWidth)) {
                    Box(Modifier.offset(x = maxWidth * ((now - gridStart).toFloat() / (end - gridStart)))
                        .width(1.dp).fillMaxHeight().background(Accent.copy(alpha = .55f)))
                    Box(Modifier.offset(x = maxWidth * ((now - gridStart).toFloat() / (end - gridStart)) - 2.dp)
                        .size(5.dp).background(Accent, androidx.compose.foundation.shape.CircleShape))
                }
            }
        }
    }
}

@Composable
private fun GuideChannelRow(channelIndex: Int, channel: Channel, channels: List<Channel>,
                            preparedSlots: List<GuideSlot>?, gridWidth: Dp,
                            channelWidth: Dp, position: GuidePosition,
                            start: Long, end: Long, now: Long, focus: FocusRequester, active: Boolean,
                            translucent: Boolean, interactive: Boolean, onMenu: (() -> Unit)?, onActions: ((Channel) -> Unit)?,
                            onProgram: (Channel, Program?) -> Unit, onSeek: (Long) -> Unit,
                            onMove: (String) -> Unit, onFocused: (Channel, GuideSlot) -> Unit,
                            onGridExit: () -> Unit) {
    // Only the old and new selected rows observe a changed value when the cursor moves.
    val targetTime by remember(position, channel.id) { derivedStateOf {
        if (position.channelState.value == channel.id) position.timeState.longValue else null
    } }
    Row(Modifier.fillMaxWidth().height(38.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.width(channelWidth).padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${channelIndex + 1}", color = if (active) Accent else Color.White, fontSize = 14.sp,
                modifier = Modifier.width(34.dp).padding(start = 8.dp))
            ChannelLogo(channel, Modifier.width(50.dp).height(30.dp))
            Text(channel.name, Modifier.weight(1f).padding(start = 12.dp), maxLines = 1,
                overflow = TextOverflow.Ellipsis, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                color = if (active) Accent else Color(0xFFF0F1F2))
            if (active) Text("▶", color = Accent, fontSize = 12.sp)
        }
        Box(Modifier.weight(1f).fillMaxHeight()) {
            val slots = preparedSlots ?: remember(start, end) { guideSlots(emptyList(), start, end) }
            slots.forEachIndexed { slotIndex, slot ->
                val isTarget = targetTime?.coerceIn(start, end - 1)?.let { it in slot.start until slot.end } == true
                val width = gridWidth * ((slot.end - slot.start).toFloat() / (end - start))
                val left = gridWidth * ((slot.start - start).toFloat() / (end - start))
                // Reuse the measured cells when a different time window fills the same row.
                key(slotIndex) {
                    GuideProgramCell({ onProgram(channel, slot.program) },
                        Modifier.offset(x = left).width(width).fillMaxHeight().padding(end = 3.dp)
                            .then(if (isTarget) Modifier.focusRequester(focus) else Modifier)
                            .onFocusChanged { if (it.isFocused) {
                                onFocused(channel, slot)
                            } }
                            .onPreviewKeyEvent { event ->
                                val key = event.nativeKeyEvent
                                if (key.keyCode in listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)) {
                                    if (key.keyCode == KeyEvent.KEYCODE_DPAD_LEFT && slot.start <= now) {
                                        if (key.action == KeyEvent.ACTION_DOWN && key.repeatCount == 0) {
                                            onGridExit(); onMenu?.invoke()
                                        }
                                        true
                                    } else if (key.keyCode == KeyEvent.KEYCODE_DPAD_UP && channelIndex == 0 && onMenu != null) {
                                        onGridExit(); false
                                    } else {
                                        if (key.action == KeyEvent.ACTION_DOWN) when (key.keyCode) {
                                            KeyEvent.KEYCODE_DPAD_LEFT -> onSeek(slots.getOrNull(slotIndex - 1)?.let { (it.start + it.end) / 2 } ?: start - 1)
                                            KeyEvent.KEYCODE_DPAD_RIGHT -> onSeek(slots.getOrNull(slotIndex + 1)?.let { (it.start + it.end) / 2 } ?: end)
                                            else -> {
                                                val delta = if (key.keyCode == KeyEvent.KEYCODE_DPAD_UP) -1 else 1
                                                onMove(channels[(channelIndex + delta).coerceIn(0, channels.lastIndex)].id)
                                            }
                                        }
                                        true
                                    }
                                } else false
                            }, onLongClick = onActions?.let { { it(channel) } }, enabled = interactive && slot.end > now,
                        contentKey = slot.program?.id ?: slot.start,
                        containerColor = if (translucent) {
                            if (slot.program == null) Color(0x4D1C2023) else Color(0x6623282C)
                        } else if (slot.program == null) Color(0xFF1C2023) else Color(0xFF23282C),
                        focusedContainerColor = if (translucent) Color(0xCCDFE2E5) else Color(0xFFDFE2E5)) {
                        Text(slot.program?.title ?: "Нет программы", maxLines = 1,
                            overflow = TextOverflow.Ellipsis, fontSize = 14.sp, fontWeight = FontWeight.Normal)
                    }
                }
            }
        }
    }
}
