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

val Muted = Color(0xFFA0A5AA)
val Accent = Color(0xFF90CAF9)
fun time(value: Long): String = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(value))
fun dateTime(value: Long): String = if (value == 0L) "ещё не было" else DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(value))
fun timeRange(program: Program): String = "${time(program.start)}–${time(program.end)}"

@Composable
fun TvButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, onLongClick: (() -> Unit)? = null, contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 8.dp), containerColor: Color = Color(0xFF222629), focusedContainerColor: Color = Color(0xFFDFE2E5), content: (@Composable RowScope.() -> Unit)? = null) {
    var longPressed by remember { mutableStateOf(false) }
    val pressedKeys = remember { mutableSetOf<Int>() }
    Button(onClick = { if (!longPressed) onClick(); longPressed = false },
        contentPadding = contentPadding,
        shape = ButtonDefaults.shape(shape = RoundedCornerShape(4.dp)),
        colors = ButtonDefaults.colors(containerColor = containerColor, contentColor = Color(0xFFE3E5E7), focusedContainerColor = focusedContainerColor, focusedContentColor = Color(0xFF111518)),
        scale = ButtonDefaults.scale(focusedScale = 1f),
        border = ButtonDefaults.border(focusedBorder = Border(BorderStroke(0.dp, Color.Transparent))), modifier = modifier.then(if (onLongClick != null) Modifier.semantics { onLongClick(label = "Действия канала") { onLongClick(); true } } else Modifier)
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
            textStyle = TextStyle(color = Color.White, fontSize = 18.sp), cursorBrush = SolidColor(Accent),
            modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }.onPreviewKeyEvent { event ->
                val key = event.nativeKeyEvent
                if (key.keyCode == KeyEvent.KEYCODE_DPAD_UP || key.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    if (key.action == KeyEvent.ACTION_DOWN) focusManager.moveFocus(
                        if (key.keyCode == KeyEvent.KEYCODE_DPAD_UP) FocusDirection.Up else FocusDirection.Down
                    )
                    true
                } else false
            }
                .border(if (focused) 2.dp else 1.dp, if (focused) Accent else Color(0xFF425461), RoundedCornerShape(8.dp))
                .background(Color(0xFF15222D), RoundedCornerShape(8.dp)).padding(14.dp))
    }
}

@Composable
fun Modal(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.widthIn(min = 420.dp, max = 720.dp).background(Color(0xFF1C2934), RoundedCornerShape(16.dp)).padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            content()
        }
    }
}
