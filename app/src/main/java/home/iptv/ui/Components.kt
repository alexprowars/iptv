package home.iptv.ui

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import home.iptv.ui.theme.IptvColors
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.onLongClick
import androidx.tv.material3.*
import home.iptv.core.model.Program
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

val Muted = IptvColors.Muted
val Accent = IptvColors.Accent
fun time(value: Long): String = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(value))
fun dateTime(value: Long): String = if (value == 0L) "ещё не было" else DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(value))
fun timeRange(program: Program): String = "${time(program.start)}–${time(program.end)}"

@Composable
fun TvButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, onLongClick: (() -> Unit)? = null, contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 12.dp), containerColor: Color = IptvColors.Elevated, focusedContainerColor: Color = IptvColors.Focus, cornerRadius: androidx.compose.ui.unit.Dp = 12.dp, content: (@Composable RowScope.() -> Unit)? = null) {
    var longPressed by remember { mutableStateOf(false) }
    val pressedKeys = remember { mutableSetOf<Int>() }
    Button(onClick = { if (!longPressed) onClick(); longPressed = false },
        contentPadding = contentPadding,
        shape = ButtonDefaults.shape(shape = RoundedCornerShape(cornerRadius)),
        colors = ButtonDefaults.colors(containerColor = containerColor, contentColor = IptvColors.Text, focusedContainerColor = focusedContainerColor, focusedContentColor = IptvColors.OnAccent),
        scale = ButtonDefaults.scale(focusedScale = 1f),
        border = ButtonDefaults.border(focusedBorder = Border(BorderStroke(2.dp, IptvColors.Focus))), modifier = modifier.then(if (onLongClick != null) Modifier.semantics { onLongClick(label = "Действия канала") { onLongClick(); true } } else Modifier)
        .onFocusChanged { if (!it.isFocused) { pressedKeys.clear(); longPressed = false } }
        .onPreviewKeyEvent { event ->
        val key = event.nativeKeyEvent
        if (key.keyCode in listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER)) {
            when (key.action) {
                KeyEvent.ACTION_DOWN -> {
                    if (key.repeatCount == 0) { pressedKeys += key.keyCode; longPressed = false }
                    // A newly focused button must not inherit repeats from the button that opened it.
                    else if (key.keyCode !in pressedKeys) return@onPreviewKeyEvent true
                    else if (onLongClick != null) {
                        if (!longPressed) { longPressed = true; onLongClick() }
                        return@onPreviewKeyEvent true
                    }
                }
                // TV Material invokes onClick even for an orphan release after a focus/window change.
                KeyEvent.ACTION_UP -> if (!pressedKeys.remove(key.keyCode)) return@onPreviewKeyEvent true
            }
        }
        false
    }) { if (content != null) content() else Text(label, maxLines = 1) }
}

@Composable
fun Input(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, multiline: Boolean = false) {
    var focused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = Muted)
        BasicTextField(value = value, onValueChange = onChange, singleLine = !multiline,
            keyboardOptions = KeyboardOptions(imeAction = if (multiline) ImeAction.Done else ImeAction.Next),
            keyboardActions = KeyboardActions(
                onNext = { focusManager.moveFocus(FocusDirection.Down) },
                onDone = { keyboard?.hide(); focusManager.moveFocus(FocusDirection.Down) }
            ),
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = IptvColors.Text), cursorBrush = SolidColor(Accent),
            modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }.onPreviewKeyEvent { event ->
                val key = event.nativeKeyEvent
                if (key.keyCode == KeyEvent.KEYCODE_DPAD_UP || key.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    if (key.action == KeyEvent.ACTION_DOWN) focusManager.moveFocus(
                        if (key.keyCode == KeyEvent.KEYCODE_DPAD_UP) FocusDirection.Up else FocusDirection.Down
                    )
                    true
                } else false
            }
                .border(if (focused) 2.dp else 1.dp, if (focused) Accent else IptvColors.Outline, RoundedCornerShape(12.dp))
                .background(IptvColors.Surface, RoundedCornerShape(12.dp)).padding(14.dp))
    }
}

@Composable
fun SectionCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth()
        .background(IptvColors.Surface, RoundedCornerShape(16.dp))
        .border(1.dp, IptvColors.Outline.copy(alpha = .55f), RoundedCornerShape(16.dp))
        .padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
}

@Composable
fun SettingRow(title: String, description: String, value: String, onClick: () -> Unit) {
    TvButton(title, onClick, Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description, style = MaterialTheme.typography.bodySmall,
                color = LocalContentColor.current.copy(alpha = .8f))
        }
        Spacer(Modifier.width(20.dp))
        Text(value, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun Modal(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.widthIn(min = 420.dp, max = 720.dp).background(IptvColors.Surface, RoundedCornerShape(20.dp)).border(1.dp, IptvColors.Outline, RoundedCornerShape(20.dp)).padding(28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            content()
        }
    }
}
