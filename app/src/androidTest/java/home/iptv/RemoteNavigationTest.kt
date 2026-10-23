package home.iptv

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import home.iptv.ui.Input
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import home.iptv.ui.TvButton
import home.iptv.ui.theme.IPTVTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** UI tests run in the isolated Compose test host and never clear a user's IPTV database. */
@OptIn(ExperimentalTestApi::class)
class RemoteNavigationTest {
    @get:Rule val compose = createComposeRule()
    @Test fun textFieldsAllowDpadToReachSave() {
        var saved = false
        compose.setContent {
            IPTVTheme {
                val focus = remember { FocusRequester() }
                Column {
                    Input("Название", "SkyNet", {}, Modifier.focusRequester(focus))
                    Input("URL", "http://example.org/list", {})
                    TvButton("Сохранить", { saved = true })
                }
                LaunchedEffect(Unit) { focus.requestFocus() }
            }
        }
        compose.onNodeWithText("SkyNet").assertIsFocused().performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithText("http://example.org/list").assertIsFocused().performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithText("Сохранить").assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals(true, saved) }
    }
    @Test fun dpadMovesFocusAndOkActivatesButton() {
        var chosen = ""
        compose.setContent {
            IPTVTheme {
                val focus = remember { FocusRequester() }
                Row {
                    TvButton("Первый", { chosen = "first" }, Modifier.focusRequester(focus))
                    TvButton("Второй", { chosen = "second" })
                }
                LaunchedEffect(Unit) { focus.requestFocus() }
            }
        }
        compose.onNodeWithText("Первый").assertIsFocused().performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNodeWithText("Второй").assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals("second", chosen) }
    }
}
