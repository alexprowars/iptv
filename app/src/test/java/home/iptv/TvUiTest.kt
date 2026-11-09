package home.iptv

import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import home.iptv.core.model.*
import home.iptv.ui.IptvApp
import home.iptv.ui.theme.IPTVTheme
import home.iptv.feature.guide.GuideScreen
import home.iptv.feature.guide.GuidePosition
import home.iptv.feature.player.PlayerGuideOverlay
import home.iptv.core.preferences.Preferences
import kotlinx.coroutines.flow.first
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import home.iptv.feature.player.PlayerContent
import home.iptv.feature.player.PlaybackController
import home.iptv.feature.player.streamBadges
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.focusable
import home.iptv.ui.RetainedDashboard
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = IptvApplication::class, qualifiers = "w960dp-h540dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class TvUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val store = ViewModelStore()
    private lateinit var vm: IptvViewModel
    @Before fun setup() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<IptvApplication>()
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(app, androidx.work.Configuration.Builder().setExecutor { it.run() }.build())
        app.graph.preferences.autoResume(false)
        val dao = app.graph.db.dao()
        val playlist = Source(id = "playlist", name = "Тестовый плейлист", location = "https://example.org", intervalHours = 0)
        val epg = Source(id = "epg", kind = Source.EPG, name = "Тестовая программа", location = "https://example.org", intervalHours = 0, generation = "g")
        dao.saveSource(playlist); dao.saveSource(epg)
        val channels = listOf("A", "B").mapIndexed { index, name -> Channel(id = name, playlistId = playlist.id, name = "Канал $name", streamUrl = "http://127.0.0.1:1/$name", groupName = "Новости", position = index) }
        dao.saveChannels(channels)
        dao.saveMappings(channels.map { ChannelEpgMapping(it.id, epg.id, it.id, ChannelEpgMapping.MANUAL) })
        val now = System.currentTimeMillis()
        dao.savePrograms(channels.flatMap { listOf(
            Program(sourceId = epg.id, generation = "g", epgId = it.id, start = now - 600_000, end = now + 600_000, title = "Сейчас ${it.id}"),
            Program(sourceId = epg.id, generation = "g", epgId = it.id, start = now + 600_000, end = now + 3_600_000, title = "Далее ${it.id}", description = "Описание будущей передачи")
        ) })
        vm = IptvViewModel(app, SavedStateHandle())
        store.put("vm", vm)
    }
    @After fun cleanup() { store.clear() }
    @Test fun newEpgSelectsUnlinkedPlaylistAndRemoteCanReachIt() {
        compose.setContent {
            IPTVTheme {
                home.iptv.feature.sources.SourceEditor(vm,
                    Source(kind = Source.EPG, name = "Новая программа", location = ""), null, {})
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("✓ Тестовый плейлист").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("0").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithText("0").performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithText("✓ Тестовый плейлист").assertIsFocused()
    }
    @Test fun newPlaylistSelectsExistingEpgButEditingKeepsEmptySelection() {
        var source by mutableStateOf(Source(name = "Новый плейлист", location = ""))
        compose.setContent {
            IPTVTheme { home.iptv.feature.sources.SourceEditor(vm, source, null, {}) }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("1. Тестовая программа").fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle { source = Source(id = "playlist", name = "Тестовый плейлист", location = "") }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("+ Тестовая программа").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("1. Тестовая программа").assertDoesNotExist()
        compose.onNodeWithText("+ Тестовая программа").assertExists()
    }
    private fun show() {
        compose.setContent { IPTVTheme { IptvApp(vm) } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Канал A").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun openGuideMenu() {
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithText("Все каналы").assertIsDisplayed()
            .performKeyInput { pressKey(Key.DirectionLeft) }
        compose.onNodeWithText("Настройки").assertIsDisplayed()
    }
    @Test fun guideRemoteNavigationOpensFutureDescription() {
        show()
        openGuideMenu()
        compose.onNodeWithText("Телепрограмма").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Сейчас A").fetchSemanticsNodes().isNotEmpty() }
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        compose.onNode(hasText("Сейчас A") and hasClickAction()).assertIsFocused().performKeyInput { pressKey(Key.DirectionRight) }
        compose.mainClock.advanceTimeBy(100)
        compose.onNode(hasText("Далее A") and hasClickAction()).assertIsFocused().performKeyInput { pressKey(Key.DirectionDown) }
        compose.mainClock.advanceTimeBy(100)
        compose.onNode(hasText("Далее B") and hasClickAction()).assertIsFocused().performKeyInput { pressKey(Key.DirectionUp) }
        compose.mainClock.advanceTimeBy(100)
        compose.onNode(hasText("Далее A") and hasClickAction()).assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.onAllNodesWithText("Описание будущей передачи").assertCountEquals(2)
        compose.onNodeWithText("Смотреть канал в эфире").assertExists()
        compose.onNodeWithText("Закрыть").performSemanticsAction(SemanticsActions.OnClick) { it() }
        capture("guide.png")
    }
    @Test fun guideOkChangesPreviewBeforeExpandingTheSameChannel() {
        show()
        compose.mainClock.advanceTimeBy(500)
        val a = hasText("Сейчас A") and hasClickAction()
        val b = hasText("Сейчас B") and hasClickAction()
        val initialCell = compose.onNode(a).assertIsFocused().fetchSemanticsNode().id
        compose.onNode(a).performKeyInput { pressKey(Key.DirectionCenter) }
        compose.mainClock.advanceTimeBy(200)
        compose.runOnIdle { Assert.assertEquals("A", vm.playback.value?.channel?.id) }
        compose.onNodeWithTag("guide-grid").assertIsDisplayed()
        compose.onNodeWithTag("player-remote").assertDoesNotExist()
        compose.onNode(a).assertIsFocused().performKeyInput { pressKey(Key.DirectionDown) }
        compose.mainClock.advanceTimeBy(100)
        // Browsing alone keeps the previous channel in the preview.
        compose.runOnIdle { Assert.assertEquals("A", vm.playback.value?.channel?.id) }
        compose.onNode(b).assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.mainClock.advanceTimeBy(200)
        compose.runOnIdle { Assert.assertEquals("B", vm.playback.value?.channel?.id) }
        compose.onNodeWithTag("guide-grid").assertIsDisplayed()
        compose.onNodeWithTag("player-remote").assertDoesNotExist()
        val preview = vm.playback.value
        compose.onNode(b).assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithTag("player-remote").assertIsFocused()
        compose.onNodeWithTag("guide-grid").assertDoesNotExist()
        compose.runOnIdle {
            Assert.assertSame(preview, vm.playback.value)
            compose.activity.onBackPressedDispatcher.onBackPressed()
        }
        compose.mainClock.advanceTimeBy(500)
        compose.onNode(b).assertIsFocused().performKeyInput { pressKey(Key.DirectionUp) }
        compose.mainClock.advanceTimeBy(100)
        compose.onNode(a).assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.mainClock.advanceTimeBy(200)
        compose.runOnIdle { Assert.assertEquals("A", vm.playback.value?.channel?.id) }
        compose.onNodeWithTag("guide-grid").assertIsDisplayed()
        compose.onNodeWithTag("player-remote").assertDoesNotExist()
        Assert.assertEquals(initialCell, compose.onNode(a).assertIsFocused().fetchSemanticsNode().id)
    }
    @Test fun guideWithoutEpgAlsoRequiresSecondOkToExpand() {
        runBlocking {
            vm.dao.saveChannels(listOf(Channel(id = "no-epg", playlistId = "playlist", name = "Без EPG",
                streamUrl = "http://127.0.0.1:1/no-epg", position = 2)))
        }
        show()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Нет программы").fetchSemanticsNodes().isNotEmpty() }
        compose.mainClock.advanceTimeBy(500)
        compose.onNode(hasText("Сейчас A") and hasClickAction()).performKeyInput { pressKey(Key.DirectionDown) }
        compose.mainClock.advanceTimeBy(100)
        compose.onNode(hasText("Сейчас B") and hasClickAction()).performKeyInput { pressKey(Key.DirectionDown) }
        compose.mainClock.advanceTimeBy(100)
        val gap = hasText("Нет программы") and hasClickAction() and isFocused()
        compose.onNode(gap).performKeyInput { pressKey(Key.DirectionCenter) }
        compose.mainClock.advanceTimeBy(200)
        compose.runOnIdle { Assert.assertEquals("no-epg", vm.playback.value?.channel?.id) }
        compose.onNodeWithTag("guide-grid").assertIsDisplayed()
        compose.onNodeWithTag("player-remote").assertDoesNotExist()
        compose.onNode(gap).assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithTag("player-remote").assertIsFocused()
    }
    @Test fun leftAtLiveOpensMenuAndRightReturnsToTheSameProgramWithoutAButton() {
        show()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("Меню").assertDoesNotExist()
        val cell = hasText("Сейчас A") and hasClickAction()
        val initialId = compose.onNode(cell).assertIsFocused().fetchSemanticsNode().id
        compose.onNode(cell).performKeyInput { pressKey(Key.DirectionRight) }
        compose.mainClock.advanceTimeBy(100)
        compose.onNode(hasText("Далее A") and hasClickAction()).assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionLeft) }
        compose.mainClock.advanceTimeBy(100)
        compose.onNode(cell).assertIsFocused().performKeyInput { pressKey(Key.DirectionLeft) }
        compose.onNodeWithText("Все каналы").assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionRight) }
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithText("Все каналы").assertDoesNotExist()
        compose.onNode(cell).assertIsFocused()
        Assert.assertEquals(initialId, compose.onNode(cell).fetchSemanticsNode().id)
    }
    @Test fun channelActionsAddFavoriteAndSettingsAreReachable() {
        show()
        compose.onNode(hasText("Сейчас A") and hasClickAction()).performSemanticsAction(SemanticsActions.OnLongClick) { it() }
        compose.onNodeWithText("Добавить в избранное").performSemanticsAction(SemanticsActions.OnClick) { it() }
        openGuideMenu()
        compose.onNodeWithText("Избранное").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitUntil(10_000) { vm.visible.value.size == 1 && compose.onAllNodesWithText("Канал B").fetchSemanticsNodes().isEmpty() }
        openGuideMenu()
        compose.onNodeWithText("Настройки").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("Продолжать последний канал").assertExists()
        compose.onNodeWithText("Выключено").assertExists()
        capture("settings.png")
        compose.onNodeWithText("Источники").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("+ Плейлист").assertExists()
        compose.onNodeWithText("+ Плейлист").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Выбрать файл").fetchSemanticsNodes().size == 1
        }
        compose.onNodeWithText("Выбрать файл").assertExists()
        capture("source-editor.png")
        // A new playlist starts with the existing EPG selected.
        compose.onNodeWithText("1. Тестовая программа").assertExists()
        compose.onNodeWithText("Убрать").performScrollTo()
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("1. Тестовая программа").assertDoesNotExist()
        compose.onNodeWithText("+ Тестовая программа").performScrollTo()
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithText("Сохранить и обновить").assertIsFocused()
    }
    private fun sendConfirm(action: Int, repeats: Int = 0, dialog: Boolean = false,
                            keyCode: Int = android.view.KeyEvent.KEYCODE_DPAD_CENTER) {
        compose.runOnIdle {
            val now = android.os.SystemClock.uptimeMillis()
            val event = android.view.KeyEvent(now, now, action, keyCode, repeats)
            if (dialog) org.robolectric.shadows.ShadowDialog.getLatestDialog().dispatchKeyEvent(event)
            else compose.activity.dispatchKeyEvent(event)
        }
    }
    @Test fun heldOkReleaseDoesNotChooseAnActionInNewDialog() {
        show()
        compose.mainClock.advanceTimeBy(500)
        compose.onNode(hasText("Сейчас A") and hasClickAction()).assertIsFocused()
        sendConfirm(android.view.KeyEvent.ACTION_DOWN)
        compose.mainClock.advanceTimeBy(700)
        compose.onNodeWithText("Добавить в избранное").assertExists()
            .performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithText("Добавить в избранное").assertIsFocused()
        compose.runOnIdle { Assert.assertNull(vm.playback.value) }
        sendConfirm(android.view.KeyEvent.ACTION_DOWN, repeats = 2, dialog = true)
        sendConfirm(android.view.KeyEvent.ACTION_UP, dialog = true)
        compose.onNodeWithText("Добавить в избранное").assertIsDisplayed().assertIsFocused()
        compose.runOnIdle { Assert.assertTrue(vm.favorites.value.isEmpty()); Assert.assertNull(vm.playback.value) }
        compose.onNodeWithText("Добавить в избранное").performKeyInput { pressKey(Key.DirectionDown) }
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithText("Сопоставление EPG").assertIsFocused().performKeyInput { pressKey(Key.DirectionDown) }
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithText("Закрыть").assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithText("Добавить в избранное").assertDoesNotExist()
        compose.onNode(hasText("Сейчас A") and hasClickAction()).assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionCenter) }
        compose.mainClock.advanceTimeBy(100)
        compose.runOnIdle { Assert.assertEquals("A", vm.playback.value?.channel?.id) }
        compose.onNodeWithTag("guide-grid").assertIsDisplayed()
        compose.onNodeWithTag("player-remote").assertDoesNotExist()
        compose.onNode(hasText("Сейчас A") and hasClickAction()).assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionCenter) }
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithTag("player-remote").assertIsFocused()
    }
    @Test fun tvButtonLongPressDoesNotSuppressTheNextShortPress() {
        var clicks = 0
        var longClicks = 0
        val focus = androidx.compose.ui.focus.FocusRequester()
        compose.setContent { IPTVTheme {
            home.iptv.ui.TvButton("Кнопка", { clicks++ }, Modifier.focusRequester(focus), onLongClick = { longClicks++ })
            LaunchedEffect(Unit) { withFrameNanos {}; focus.requestFocus() }
        } }
        compose.mainClock.advanceTimeBy(100)
        repeat(2) {
            sendConfirm(android.view.KeyEvent.ACTION_DOWN)
            sendConfirm(android.view.KeyEvent.ACTION_DOWN, repeats = 1)
            sendConfirm(android.view.KeyEvent.ACTION_DOWN, repeats = 2)
            sendConfirm(android.view.KeyEvent.ACTION_UP)
            compose.runOnIdle { Assert.assertEquals(0, clicks); Assert.assertEquals(it + 1, longClicks) }
        }
        compose.onNodeWithText("Кнопка").performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { Assert.assertEquals(1, clicks) }
    }
    @Test fun guideLongPressConsumesRepeatsAndReleaseWhenFocusReturns() {
        var clicks = 0
        var longClicks = 0
        val focus = androidx.compose.ui.focus.FocusRequester()
        compose.setContent { IPTVTheme {
            home.iptv.feature.guide.GuideProgramCell({ clicks++ }, Modifier.focusRequester(focus),
                onLongClick = { longClicks++ }, containerColor = androidx.compose.ui.graphics.Color.Black,
                focusedContainerColor = androidx.compose.ui.graphics.Color.White) {
                androidx.tv.material3.Text("Передача")
            }
            LaunchedEffect(Unit) { withFrameNanos {}; focus.requestFocus() }
        } }
        compose.mainClock.advanceTimeBy(100)
        listOf(android.view.KeyEvent.KEYCODE_DPAD_CENTER, android.view.KeyEvent.KEYCODE_ENTER,
            android.view.KeyEvent.KEYCODE_NUMPAD_ENTER).forEachIndexed { gesture, keyCode ->
            sendConfirm(android.view.KeyEvent.ACTION_DOWN, keyCode = keyCode)
            compose.mainClock.advanceTimeBy(700)
            compose.runOnIdle { Assert.assertEquals(gesture + 1, longClicks); focus.requestFocus() }
            // Repeats can still reach the original window while the menu takes focus.
            sendConfirm(android.view.KeyEvent.ACTION_DOWN, repeats = 1, keyCode = keyCode)
            sendConfirm(android.view.KeyEvent.ACTION_DOWN, repeats = 2, keyCode = keyCode)
            compose.mainClock.advanceTimeBy(700)
            sendConfirm(android.view.KeyEvent.ACTION_UP, keyCode = keyCode)
            compose.runOnIdle { Assert.assertEquals(0, clicks); Assert.assertEquals(gesture + 1, longClicks) }
        }
        compose.onNodeWithText("Передача").performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { Assert.assertEquals(1, clicks) }
    }
    @Test fun heldOkCannotExpandPlayingPreviewBehindChannelMenu() {
        show()
        compose.runOnIdle { vm.play(vm.visible.value.first(), vm.visible.value) }
        compose.mainClock.advanceTimeBy(500)
        compose.onNode(hasText("Сейчас A") and hasClickAction())
            .performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        sendConfirm(android.view.KeyEvent.ACTION_DOWN)
        compose.mainClock.advanceTimeBy(700)
        compose.onNodeWithText("Добавить в избранное").assertIsDisplayed()
        // The original window can restore focus to the preview while the dialog opens.
        compose.onNodeWithTag("dashboard-preview")
            .performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        sendConfirm(android.view.KeyEvent.ACTION_DOWN, repeats = 1)
        sendConfirm(android.view.KeyEvent.ACTION_UP)
        // A delayed callback from the original window must also respect the open modal.
        compose.onNodeWithTag("dashboard-preview").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.mainClock.advanceTimeBy(200)
        compose.onNodeWithText("Добавить в избранное").assertIsDisplayed()
        compose.onNodeWithTag("guide-grid").assertIsDisplayed()
        compose.onNodeWithTag("player-remote").assertDoesNotExist()
        compose.runOnIdle {
            (org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.activity.ComponentDialog)
                .onBackPressedDispatcher.onBackPressed()
        }
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithTag("dashboard-preview").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.mainClock.advanceTimeBy(200)
        compose.onNodeWithTag("player-remote").assertIsFocused()
    }
    @Test fun previewIgnoresConfirmGestureStartedOnProgramme() {
        var expanded = 0
        var menuOpened = 0
        val focus = androidx.compose.ui.focus.FocusRequester()
        val channel = Channel(id = "preview", playlistId = "playlist", name = "Превью", streamUrl = "http://127.0.0.1:1/preview")
        compose.setContent { IPTVTheme {
            val inputMode = androidx.compose.ui.platform.LocalInputModeManager.current
            SideEffect { inputMode.requestInputMode(androidx.compose.ui.input.InputMode.Keyboard) }
            val controller = home.iptv.feature.player.rememberPlaybackController(null, "", false, {})
            androidx.compose.foundation.layout.Column {
                home.iptv.ui.DashboardHeader(controller, channel, null, null,
                    System.currentTimeMillis(), onWatch = { expanded++ }, onRetry = {})
                home.iptv.feature.guide.GuideProgramCell({}, Modifier.focusRequester(focus),
                    onLongClick = { menuOpened++ },
                    containerColor = androidx.compose.ui.graphics.Color.Black,
                    focusedContainerColor = androidx.compose.ui.graphics.Color.White) {
                    androidx.tv.material3.Text("Передача")
                }
            }
            LaunchedEffect(Unit) { withFrameNanos {}; focus.requestFocus() }
        } }
        compose.mainClock.advanceTimeBy(100)
        sendConfirm(android.view.KeyEvent.ACTION_DOWN)
        compose.mainClock.advanceTimeBy(700)
        compose.runOnIdle { Assert.assertEquals(1, menuOpened) }
        compose.onNodeWithTag("dashboard-preview")
            .performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithTag("dashboard-preview").assertIsFocused()
        sendConfirm(android.view.KeyEvent.ACTION_DOWN, repeats = 1)
        sendConfirm(android.view.KeyEvent.ACTION_UP)
        compose.runOnIdle { Assert.assertEquals(0, expanded) }
        compose.onNodeWithTag("dashboard-preview").performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { Assert.assertEquals(1, expanded) }
    }
    @Test fun rightClosesMenuAndBackReturnsFromEditorThenOpensMenuBeforeExit() {
        show()
        openGuideMenu()
        compose.onNodeWithText("Все каналы").performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNodeWithText("Источники").assertDoesNotExist()
        compose.onNodeWithText("Все каналы").performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNodeWithText("Все каналы").assertDoesNotExist()
        compose.runOnIdle { Assert.assertFalse(compose.activity.isFinishing) }
        openGuideMenu()
        compose.onNodeWithText("Источники").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("+ Плейлист").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Плейлист M3U").fetchSemanticsNodes().size == 1
        }
        compose.onNodeWithText("Плейлист M3U").assertExists()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("+ Плейлист").assertExists()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("guide-grid").assertExists()
        compose.runOnIdle {
            Assert.assertFalse(compose.activity.isFinishing)
            compose.activity.onBackPressedDispatcher.onBackPressed()
            Assert.assertFalse(compose.activity.isFinishing)
        }
        compose.onNodeWithText("Все каналы").assertIsDisplayed()
        compose.runOnIdle {
            (org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.activity.ComponentDialog)
                .onBackPressedDispatcher.onBackPressed()
            Assert.assertFalse(compose.activity.isFinishing)
        }
        compose.onNodeWithText("Настройки").assertIsDisplayed()
        compose.runOnIdle {
            (org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.activity.ComponentDialog)
                .onBackPressedDispatcher.onBackPressed()
            Assert.assertTrue(compose.activity.isFinishing)
        }
    }
    @Test fun sidebarKeepsSelectionWhileRemoteFocusMoves() {
        show()
        openGuideMenu()
        compose.onNodeWithText("Все каналы").assertIsSelected().assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithText("Избранное").assertIsFocused().assertIsNotSelected()
        compose.onNodeWithText("Все каналы").assertIsSelected()
        val folder = File("build/reports/ui").apply { mkdirs() }
        compose.onNode(isDialog()).captureToImage().asAndroidBitmap().let { bitmap ->
            File(folder, "navigation-menu.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        compose.onNodeWithText("Избранное").performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNodeWithText("Все каналы").assertIsSelected().assertIsFocused()
        compose.onNode(isDialog()).captureToImage().asAndroidBitmap().let { bitmap ->
            File(folder, "channel-menu.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }
    @Test fun renderChannelListForVisualReview() {
        show()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Сейчас A").fetchSemanticsNodes().isNotEmpty() }
        compose.mainClock.advanceTimeBy(500)
        capture("channels.png")
    }
    @Test fun guideRestoresProgramFocusAfterLeavingAndReturning() {
        var shown by mutableStateOf(true)
        var captureReopen = false
        var firstReopenedProgram: Program? = null
        val now = System.currentTimeMillis()
        val position = GuidePosition("A", now)
        compose.setContent {
            val channels by vm.channels.collectAsState()
            val state = rememberLazyListState()
            IPTVTheme {
                if (shown) GuideScreen(vm, channels, state, now, now / 3_600_000L * 3_600_000L, {}, position,
                    header = { _, program ->
                        if (captureReopen) SideEffect { firstReopenedProgram = program; captureReopen = false }
                    }) { _, _ -> }
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Сейчас A").fetchSemanticsNodes().isNotEmpty() }
        compose.mainClock.advanceTimeBy(100)
        compose.onNode(hasText("Сейчас A") and hasClickAction()).assertIsFocused().performKeyInput { pressKey(Key.DirectionRight) }
        compose.mainClock.advanceTimeBy(100)
        compose.onNode(hasText("Далее A") and hasClickAction()).assertIsFocused()
        compose.runOnIdle { shown = false }
        compose.waitForIdle()
        compose.runOnIdle { captureReopen = true; shown = true }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Далее A").fetchSemanticsNodes().isNotEmpty() }
        compose.mainClock.advanceTimeBy(100)
        compose.onNode(hasText("Далее A") and hasClickAction()).assertIsFocused()
        compose.runOnIdle { Assert.assertEquals("Далее A", firstReopenedProgram?.title) }
    }
    @Test fun guideOpensAtDistantChannelAndNavigatesAcrossBufferedPagesWithoutEpg() {
        val now = System.currentTimeMillis()
        val channels = (0 until 1000).map { index ->
            Channel(id = "large-$index", playlistId = "playlist", name = "Канал $index",
                streamUrl = "http://127.0.0.1:1/$index", position = index)
        }
        compose.setContent { IPTVTheme {
            PlayerGuideOverlay(vm, channels, channels[900].id, now, {}, {})
        } }
        compose.waitUntil(10_000) {
            compose.onAllNodes(isFocused() and hasClickAction()).fetchSemanticsNodes().size == 1
        }
        compose.onAllNodesWithText("Канал 900").assertCountEquals(2).onLast().assertIsDisplayed()
        compose.onNodeWithText("Канал 0").assertDoesNotExist()
        repeat(20) {
            compose.onNode(isFocused() and hasClickAction()).performKeyInput { pressKey(Key.DirectionDown) }
            compose.mainClock.advanceTimeBy(50)
        }
        compose.onAllNodesWithText("Канал 920").assertCountEquals(2).onLast().assertIsDisplayed()
        compose.onNode(isFocused() and hasClickAction()).performKeyInput { pressKey(Key.DirectionUp) }
        compose.mainClock.advanceTimeBy(100)
        compose.onAllNodesWithText("Канал 919").assertCountEquals(2).onLast().assertIsDisplayed()
        compose.onNode(isFocused() and hasClickAction()).assertExists()
    }
    @Test fun guideKeepsVisibleRowsStillOnTvAndScrollsOnlyAtTheEdge() {
        val context = ApplicationProvider.getApplicationContext<IptvApplication>()
        org.robolectric.Shadows.shadowOf(context.packageManager)
            .setSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK, true)
        val now = System.currentTimeMillis()
        val channels = (0 until 1000).map { index ->
            Channel(id = "tv-$index", playlistId = "playlist", name = "TV $index",
                streamUrl = "http://127.0.0.1:1/$index", position = index)
        }
        val position = GuidePosition(channels[500].id, now)
        val list = androidx.compose.foundation.lazy.LazyListState(500)
        compose.setContent { IPTVTheme {
            GuideScreen(vm, channels, list, now, now / 3_600_000L * 3_600_000L, {}, position) { _, _ -> }
        } }
        compose.waitUntil(10_000) {
            compose.onAllNodes(isFocused() and hasClickAction()).fetchSemanticsNodes().size == 1
        }
        compose.mainClock.advanceTimeBy(1000)
        compose.runOnIdle { Assert.assertEquals(500, list.firstVisibleItemIndex) }
        compose.onNode(isFocused() and hasClickAction()).performKeyInput { pressKey(Key.DirectionDown) }
        compose.mainClock.advanceTimeBy(1000)
        compose.runOnIdle {
            Assert.assertEquals(channels[501].id, position.channelState.value)
            Assert.assertEquals(500, list.firstVisibleItemIndex)
        }
        repeat(20) {
            compose.onNode(isFocused() and hasClickAction()).performKeyInput { pressKey(Key.DirectionDown) }
            compose.mainClock.advanceTimeBy(50)
        }
        compose.runOnIdle {
            Assert.assertEquals(channels[521].id, position.channelState.value)
            Assert.assertTrue(list.firstVisibleItemIndex in 501 until 521)
            val row = list.layoutInfo.visibleItemsInfo.single { it.index == 521 }
            Assert.assertTrue(row.offset + row.size <= list.layoutInfo.viewportEndOffset)
        }
    }
    @Test fun guidePagesThroughFutureAndOpensMenuAtLiveWithoutRecreatingChannelRows() {
        val zone = java.time.ZoneId.systemDefault()
        val now = java.time.LocalDateTime.of(2026, 10, 23, 12, 0).atZone(zone).toInstant().toEpochMilli()
        val hour = 3_600_000L
        val initialStart = now + 11 * hour
        var start by mutableLongStateOf(initialStart)
        var menus = 0
        val channels = listOf(Channel(id = "no-epg", playlistId = "playlist", name = "Без EPG",
            streamUrl = "http://127.0.0.1:1/no-epg"))
        val position = GuidePosition(channels.single().id, initialStart)
        compose.setContent { IPTVTheme {
            GuideScreen(vm, channels, rememberLazyListState(), now, start, { start = it }, position,
                onMenu = { menus++ }) { _, _ -> }
        } }
        compose.waitUntil(10_000) {
            compose.onAllNodes(isFocused() and hasText("Нет программы")).fetchSemanticsNodes().size == 1
        }
        val rowId = compose.onNodeWithText("Без EPG").fetchSemanticsNode().id
        val currentClock = home.iptv.ui.guideDate(now)
        compose.onNodeWithText(currentClock).assertIsDisplayed()
        fun page(key: Key, expectedStart: Long) {
            compose.onNode(isFocused() and hasClickAction()).performKeyInput { pressKey(key) }
            compose.waitUntil(10_000) {
                start == expectedStart && compose.onAllNodes(isFocused() and hasText("Нет программы"))
                    .fetchSemanticsNodes().size == 1
            }
            compose.mainClock.advanceTimeBy(200)
            compose.onNodeWithText("Без EPG").assertIsDisplayed()
            Assert.assertEquals(rowId, compose.onNodeWithText("Без EPG").fetchSemanticsNode().id)
            compose.onNodeWithText(currentClock).assertIsDisplayed()
            compose.runOnIdle { Assert.assertEquals(0, menus) }
        }
        page(Key.DirectionRight, initialStart + 2 * hour)
        compose.onNodeWithText("сб, 24 окт, 01:00").assertIsDisplayed()
        page(Key.DirectionLeft, initialStart + hour)
        compose.onNodeWithText("сб, 24 окт, 00:00").assertIsDisplayed()
        page(Key.DirectionLeft, initialStart)
        compose.onNodeWithText("23:00").assertIsDisplayed()
        // A cold window outside the prefetched neighbours must also retain the measured rows.
        compose.runOnIdle {
            start = now + 24 * hour
            position.timeState.longValue = start
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("сб, 24 окт, 12:00").fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodes(isFocused() and hasText("Нет программы")).fetchSemanticsNodes().size == 1
        }
        Assert.assertEquals(rowId, compose.onNodeWithText("Без EPG").fetchSemanticsNode().id)
        compose.onNodeWithText(currentClock).assertIsDisplayed()
        compose.runOnIdle {
            start = now / hour * hour
            position.timeState.longValue = now
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("сб, 24 окт, 12:00").fetchSemanticsNodes().isEmpty() &&
            compose.onAllNodes(isFocused() and hasText("Нет программы")).fetchSemanticsNodes().size == 1
        }
        compose.onNode(isFocused() and hasClickAction()).performKeyInput { pressKey(Key.DirectionLeft) }
        compose.runOnIdle { Assert.assertEquals(1, menus); Assert.assertEquals(now, position.timeState.longValue) }
        compose.onNodeWithText("Меню").assertDoesNotExist()
        capture("guide-dated-timeline.png")
    }
    @Test fun guideCannotSelectExpiredProgramsAndMovesToLiveWhenTheClockAdvances() {
        val hour = 3_600_000L
        val start = System.currentTimeMillis() / hour * hour
        var now by mutableLongStateOf(start + 45 * 60_000)
        val channel = Channel(id = "expires", playlistId = "playlist", name = "Смена передачи",
            streamUrl = "http://127.0.0.1:1/expires")
        val channels = listOf(channel)
        val position = GuidePosition(channel.id, start + 15 * 60_000)
        var menus = 0
        runBlocking {
            vm.dao.saveChannels(channels)
            vm.dao.saveMappings(listOf(ChannelEpgMapping(channel.id, "epg", channel.id, ChannelEpgMapping.MANUAL)))
            vm.dao.savePrograms((0..2).map { index ->
                Program(id = "expires-$index", sourceId = "epg", generation = "g", epgId = channel.id,
                    start = start + index * hour / 2, end = start + (index + 1) * hour / 2,
                    title = "Интервал $index")
            })
        }
        compose.setContent { IPTVTheme {
            GuideScreen(vm, channels, rememberLazyListState(), now, start, {}, position,
                onMenu = { menus++ }) { _, _ -> }
        } }
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasText("Интервал 1") and isFocused()).fetchSemanticsNodes().size == 1
        }
        compose.onNodeWithText("Интервал 0").assertIsNotEnabled()
        compose.onNodeWithText("Интервал 1").performKeyInput { pressKey(Key.DirectionLeft) }
        compose.runOnIdle {
            Assert.assertEquals(1, menus)
            Assert.assertEquals(now, position.timeState.longValue)
            now = start + 65 * 60_000
        }
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasText("Интервал 2") and isFocused()).fetchSemanticsNodes().size == 1
        }
        compose.onNodeWithText("Интервал 1").assertIsNotEnabled()
        compose.onNodeWithText("Интервал 2").performKeyInput { pressKey(Key.DirectionLeft) }
        compose.runOnIdle {
            Assert.assertEquals(2, menus)
            Assert.assertEquals(now, position.timeState.longValue)
        }
    }
    @Test fun reusedGuideCellRejectsConfirmStartedOnPreviousProgramAndAcceptsTheNextPress() {
        val focus = androidx.compose.ui.focus.FocusRequester()
        var identity by mutableIntStateOf(0)
        var clicks = 0
        compose.setContent { IPTVTheme {
            home.iptv.feature.guide.GuideProgramCell({ clicks++ }, Modifier.focusRequester(focus),
                onLongClick = {}, containerColor = androidx.compose.ui.graphics.Color.DarkGray,
                focusedContainerColor = androidx.compose.ui.graphics.Color.LightGray, contentKey = identity) {
                androidx.tv.material3.Text("Обновляемая передача")
            }
            LaunchedEffect(Unit) { focus.requestFocus() }
        } }
        compose.waitForIdle()
        sendConfirm(android.view.KeyEvent.ACTION_DOWN)
        compose.runOnIdle { identity++ }
        sendConfirm(android.view.KeyEvent.ACTION_UP)
        compose.runOnIdle { Assert.assertEquals(0, clicks) }
        sendConfirm(android.view.KeyEvent.ACTION_DOWN)
        sendConfirm(android.view.KeyEvent.ACTION_UP)
        compose.runOnIdle { Assert.assertEquals(1, clicks) }
    }
    @Test fun guideReusesCellsAcrossProgramWindowsAndKeepsTheSelectedProgram() {
        val hour = 3_600_000L
        val now = System.currentTimeMillis()
        val initialStart = (now / hour + 1) * hour
        var start by mutableLongStateOf(initialStart)
        val channel = Channel(id = "paged", playlistId = "playlist", name = "Постраничный EPG",
            streamUrl = "http://127.0.0.1:1/paged")
        val position = GuidePosition(channel.id, initialStart + hour / 4)
        var selectedTitle: String? = null
        runBlocking {
            vm.dao.saveChannels(listOf(channel))
            vm.dao.saveMappings(listOf(ChannelEpgMapping(channel.id, "epg", channel.id, ChannelEpgMapping.MANUAL)))
            vm.dao.savePrograms((-4..15).map { index ->
                Program(id = "paged-$index", sourceId = "epg", generation = "g", epgId = channel.id,
                    start = initialStart + index * hour / 2, end = initialStart + (index + 1) * hour / 2,
                    title = "Передача $index")
            })
        }
        val channels = listOf(channel)
        compose.setContent { IPTVTheme {
            GuideScreen(vm, channels, rememberLazyListState(), now, start, { start = it }, position,
                onMenu = { Assert.fail("Arrow navigation must not open the menu") },
                header = { _, program -> SideEffect { selectedTitle = program?.title } }) { _, _ -> }
        } }
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasText("Передача 0") and isFocused()).fetchSemanticsNodes().size == 1
        }
        val firstCellId = compose.onNodeWithText("Передача 0").fetchSemanticsNode().id
        repeat(4) { step ->
            compose.onNode(isFocused() and hasClickAction()).performKeyInput { pressKey(Key.DirectionRight) }
            compose.waitUntil(10_000) {
                compose.onAllNodes(hasText("Передача ${step + 1}") and isFocused()).fetchSemanticsNodes().size == 1
            }
        }
        Assert.assertEquals(firstCellId, compose.onNodeWithText("Передача 4").fetchSemanticsNode().id)
        compose.runOnIdle {
            Assert.assertEquals("Передача 4", selectedTitle)
            Assert.assertEquals(initialStart + 2 * hour, position.timeState.longValue)
        }
        repeat(4) { step ->
            compose.onNode(isFocused() and hasClickAction()).performKeyInput { pressKey(Key.DirectionLeft) }
            compose.waitUntil(10_000) {
                compose.onAllNodes(hasText("Передача ${3 - step}") and isFocused()).fetchSemanticsNodes().size == 1
            }
        }
        compose.runOnIdle { Assert.assertEquals("Передача 0", selectedTitle) }
    }
    @Test fun fullscreenSwitchReturnsToLiveProgramAndScrollsToPlayingChannel() {
        val now = System.currentTimeMillis()
        runBlocking {
            val extra = ('C'..'L').mapIndexed { index, name ->
                Channel(id = name.toString(), playlistId = "playlist", name = "Канал $name",
                    streamUrl = "http://127.0.0.1:1/$name", position = index + 2)
            }
            vm.dao.saveChannels(extra)
            vm.dao.saveMappings(extra.map { ChannelEpgMapping(it.id, "epg", it.id, ChannelEpgMapping.MANUAL) })
            vm.dao.savePrograms(extra.map {
                Program(sourceId = "epg", generation = "g", epgId = it.id, start = now - 60_000,
                    end = now + 3_600_000, title = "Эфир ${it.id}", description = "Описание эфира ${it.id}")
            })
        }
        show()
        compose.waitUntil(10_000) { vm.visible.value.size == 12 }
        compose.mainClock.advanceTimeBy(500)
        // Enter fullscreen from a future programme: returning must also reset the selected time.
        compose.onNode(hasText("Сейчас A") and hasClickAction()).performKeyInput { pressKey(Key.DirectionRight) }
        compose.mainClock.advanceTimeBy(100)
        compose.onNode(hasText("Далее A") and hasClickAction()).performKeyInput { pressKey(Key.DirectionCenter) }
        compose.onNodeWithText("Смотреть канал в эфире").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("guide-grid").fetchSemanticsNodes().isEmpty() }
        compose.runOnIdle { repeat(10) { vm.step(1) } }
        compose.waitUntil(10_000) { vm.playback.value?.channel?.id == "K" }
        compose.mainClock.advanceTimeBy(100)
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("guide-grid").fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Описание эфира K").fetchSemanticsNodes().isNotEmpty() }
        compose.mainClock.advanceTimeBy(200)
        compose.onNode(hasText("Эфир K") and hasClickAction()).assertIsDisplayed().assertIsFocused()
        compose.onAllNodesWithText("Эфир K").assertCountEquals(2)
        compose.onNodeWithText("Описание эфира K").assertIsDisplayed()
    }
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    @Test fun previewAndFullscreenKeepVideoViewAndTextureSize() {
        fun findVideo(view: android.view.View): androidx.media3.ui.PlayerView? {
            if (view is androidx.media3.ui.PlayerView) return view
            if (view is android.view.ViewGroup) for (index in 0 until view.childCount) {
                findVideo(view.getChildAt(index))?.let { return it }
            }
            return null
        }
        show()
        compose.mainClock.advanceTimeBy(500)
        val initialCellId = compose.onNode(hasText("Сейчас A") and hasClickAction()).fetchSemanticsNode().id
        lateinit var video: androidx.media3.ui.PlayerView
        lateinit var texture: android.view.View
        var textureWidth = 0
        var textureHeight = 0
        compose.runOnIdle {
            video = requireNotNull(findVideo(compose.activity.window.decorView))
            texture = requireNotNull(video.videoSurfaceView)
            textureWidth = texture.width
            textureHeight = texture.height
            Assert.assertTrue(textureWidth > 0 && textureHeight > 0)
        }
        compose.onNode(hasText("Сейчас A") and hasClickAction()).performKeyInput { pressKey(Key.DirectionCenter) }
        compose.mainClock.advanceTimeBy(200)
        compose.onNodeWithTag("guide-grid").assertIsDisplayed()
        compose.onNodeWithTag("player-remote").assertDoesNotExist()
        repeat(3) { attempt ->
            if (attempt < 2) compose.onNode(hasText("Сейчас A") and hasClickAction())
                .performSemanticsAction(SemanticsActions.OnClick) { it() }
            else {
                compose.onNode(hasText("Сейчас A") and hasClickAction()).performKeyInput { pressKey(Key.DirectionUp) }
                compose.mainClock.advanceTimeBy(100)
                compose.onNodeWithTag("dashboard-preview").performSemanticsAction(SemanticsActions.OnClick) { it() }
            }
            compose.mainClock.advanceTimeBy(300)
            compose.onNodeWithTag("player-remote").assertIsFocused()
            compose.onNode(hasText("Сейчас A") and hasClickAction()).assertDoesNotExist()
            compose.runOnIdle {
                Assert.assertSame(video, findVideo(compose.activity.window.decorView))
                Assert.assertSame(texture, video.videoSurfaceView)
                Assert.assertEquals(textureWidth, texture.width)
                Assert.assertEquals(textureHeight, texture.height)
                compose.activity.onBackPressedDispatcher.onBackPressed()
            }
            compose.mainClock.advanceTimeBy(500)
            compose.onNodeWithTag("guide-grid").assertExists()
            compose.onNode(hasText("Сейчас A") and hasClickAction()).assertIsFocused()
            Assert.assertEquals(initialCellId,
                compose.onNode(hasText("Сейчас A") and hasClickAction()).fetchSemanticsNode().id)
            compose.runOnIdle {
                Assert.assertSame(video, findVideo(compose.activity.window.decorView))
                Assert.assertSame(texture, video.videoSurfaceView)
                Assert.assertEquals(textureWidth, texture.width)
                Assert.assertEquals(textureHeight, texture.height)
            }
        }
    }
    @Test fun retainedDashboardRejectsHiddenFocusAndKeepsItsContent() {
        var visible by mutableStateOf(true)
        var mounted = 0
        var disposed = 0
        var clicked = 0
        val dashboardFocus = androidx.compose.ui.focus.FocusRequester()
        val playerFocus = androidx.compose.ui.focus.FocusRequester()
        compose.setContent { IPTVTheme {
            Box {
                RetainedDashboard(visible) {
                    DisposableEffect(Unit) { mounted++; onDispose { disposed++ } }
                    home.iptv.ui.TvButton("Дашборд", { clicked++ }, Modifier.focusRequester(dashboardFocus))
                }
                if (!visible) Box(Modifier.focusRequester(playerFocus).focusable().testTag("test-player"))
            }
            LaunchedEffect(visible) {
                withFrameNanos {}
                if (visible) dashboardFocus.requestFocus() else playerFocus.requestFocus()
            }
        } }
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithText("Дашборд").assertIsFocused()
        val initialId = compose.onNodeWithText("Дашборд").fetchSemanticsNode().id
        repeat(2) {
            compose.runOnIdle { visible = false }
            compose.mainClock.advanceTimeBy(100)
            compose.onNodeWithText("Дашборд").assertDoesNotExist()
            compose.onNodeWithTag("test-player").assertIsFocused()
            compose.runOnIdle { Assert.assertFalse(dashboardFocus.requestFocus()) }
            compose.onNodeWithTag("test-player").performKeyInput {
                pressKey(Key.DirectionDown); pressKey(Key.DirectionCenter)
            }.assertIsFocused()
            compose.onRoot().performTouchInput { click(androidx.compose.ui.geometry.Offset(10f, 10f)) }
            compose.runOnIdle { Assert.assertEquals(0, clicked); visible = true }
            compose.mainClock.advanceTimeBy(100)
            compose.onNodeWithText("Дашборд").assertIsFocused()
            Assert.assertEquals(initialId, compose.onNodeWithText("Дашборд").fetchSemanticsNode().id)
        }
        compose.onNodeWithText("Дашборд").performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle {
            Assert.assertEquals(1, clicked)
            Assert.assertEquals(1, mounted)
            Assert.assertEquals(0, disposed)
        }
    }
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    @Test fun fullscreenArrowsShowInformationAndOnlyChannelKeysSwitch() {
        var steps = 0
        var exits = 0
        val now = System.currentTimeMillis()
        val video = Format.Builder().setWidth(1920).setHeight(1080).setFrameRate(25f).setSampleMimeType(MimeTypes.VIDEO_H264).build()
        val audio = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).setChannelCount(2).build()
        val channel = Channel(id = "player", playlistId = "playlist", name = "Тестовый эфир", streamUrl = "http://127.0.0.1:1/stream")
        val programs = listOf(
            Program(sourceId = "epg", generation = "g", epgId = "player", start = now - 600_000, end = now + 600_000,
                title = "Передача в эфире", description = "Описание текущей передачи"),
            Program(sourceId = "epg", generation = "g", epgId = "player", start = now + 600_000, end = now + 3_600_000, title = "Следующая передача"))
        compose.setContent { IPTVTheme {
            PlayerContent(channel, programs, PlaybackController.State(videoFormat = video, audioFormat = audio),
                onStep = { steps += it }, onRecent = {}, onBack = { exits++ }, onRetry = {}, video = { Box(Modifier) })
        } }
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithTag("player-remote").assertIsFocused().performKeyInput { pressKey(Key.DirectionDown) }
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithText("Передача в эфире").assertIsDisplayed()
        compose.onNodeWithText("Тестовый эфир").assertIsDisplayed()
        compose.onNodeWithText("Описание текущей передачи").assertIsDisplayed()
        compose.onNodeWithText("1920×1080").assertIsDisplayed()
        compose.onNodeWithText("25 FPS").assertIsDisplayed()
        compose.onNodeWithText("СТЕРЕО").assertIsDisplayed()
        compose.onNodeWithText("Следующий").assertDoesNotExist()
        capture("player-overlay.png")
        compose.onRoot().performKeyInput { pressKey(Key.DirectionDown); pressKey(Key.DirectionUp) }
        compose.runOnIdle { org.junit.Assert.assertEquals(0, steps) }
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithText("Передача в эфире").assertDoesNotExist()
        compose.runOnIdle { org.junit.Assert.assertEquals(0, exits) }
        compose.onNodeWithTag("player-remote").assertIsFocused().performKeyInput { pressKey(Key.DirectionUp) }
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithText("Передача в эфире").assertIsDisplayed()
        compose.onRoot().performKeyInput { pressKey(Key.ChannelUp) }
        compose.runOnIdle { org.junit.Assert.assertEquals(1, steps) }
        compose.onRoot().performKeyInput { pressKey(Key.ChannelDown) }
        compose.runOnIdle { org.junit.Assert.assertEquals(0, steps) }
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.mainClock.advanceTimeBy(100)
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.runOnIdle { org.junit.Assert.assertEquals(1, exits) }
    }
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    @Test fun unknownStreamMetadataDoesNotInventResolutionOrAudioChannels() {
        org.junit.Assert.assertTrue(streamBadges(Format.Builder().build(), Format.Builder().build()).isEmpty())
        val video = Format.Builder().setFrameRate(29.97f).build()
        val audio = Format.Builder().setChannelCount(6).build()
        org.junit.Assert.assertEquals(listOf("29.97 FPS", "5.1"), streamBadges(video, audio))
    }
    @Test fun recentCardsReplaceActionsAndExcludeCurrentChannel() {
        val now = System.currentTimeMillis()
        val a = Channel(id = "A", playlistId = "playlist", name = "Канал A", streamUrl = "http://127.0.0.1:1/a")
        val b = a.copy(id = "B", name = "Канал B")
        var chosen = ""
        val current = Program(sourceId = "epg", generation = "g", epgId = "B", start = now - 1000, end = now + 600_000, title = "Сейчас на B")
        compose.setContent { IPTVTheme {
            PlayerContent(a, emptyList(), PlaybackController.State(), {}, { chosen = it.id }, {}, {},
                recentChannels = listOf(a, b), recentPrograms = mapOf("B" to current), video = {})
        } }
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithTag("player-remote").performKeyInput { pressKey(Key.DirectionDown) }
        compose.mainClock.advanceTimeBy(300)
        compose.waitForIdle()
        compose.onNodeWithText("Телегид").assertIsFocused()
        compose.onNodeWithText("История").assertDoesNotExist()
        compose.onNodeWithText("Скрыть").assertDoesNotExist()
        compose.onNodeWithText("Вписать").assertDoesNotExist()
        compose.onNodeWithContentDescription("Смотреть Канал A").assertDoesNotExist()
        compose.onNodeWithText("Сейчас на B").assertIsDisplayed()
        compose.onNodeWithContentDescription("Смотреть Канал B").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.runOnIdle { org.junit.Assert.assertEquals("B", chosen) }
    }
    @Test fun overlayTimeoutRestartsOnRemoteInput() {
        fun elapse(ms: Long) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(ms, java.util.concurrent.TimeUnit.MILLISECONDS)
            compose.mainClock.advanceTimeBy(ms)
            compose.waitForIdle()
        }
        val channel = Channel(id = "A", playlistId = "playlist", name = "Канал A", streamUrl = "http://127.0.0.1:1/a")
        compose.setContent { IPTVTheme { PlayerContent(channel, emptyList(), PlaybackController.State(), {}, {}, {}, {}, video = {}) } }
        compose.mainClock.advanceTimeBy(100)
        compose.waitForIdle()
        compose.onNodeWithTag("player-remote").assertIsFocused()
        compose.onNodeWithTag("player-remote").performKeyInput { pressKey(Key.DirectionDown) }
        compose.mainClock.advanceTimeBy(100)
        compose.waitForIdle()
        compose.onNodeWithText("Телегид").assertExists()
        elapse(8000)
        compose.onNodeWithText("Телегид").assertExists()
        compose.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        elapse(2000)
        compose.onNodeWithText("Телегид").assertExists()
        elapse(6000)
        compose.onNodeWithText("Телегид").assertExists()
        elapse(2500)
        compose.onNodeWithText("Телегид").assertDoesNotExist()
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithTag("player-remote").assertIsFocused()
    }
    @Test fun playerLoadsGuideOnDemandAndRemovesClosedGridBeforeChannelChanges() {
        var guideMounted = 0
        var guideDisposed = 0
        var guideRendered = 0
        val now = System.currentTimeMillis()
        var channel by mutableStateOf(Channel(id = "direct", playlistId = "", name = "Прямой поток", streamUrl = "http://127.0.0.1:1/a"))
        compose.setContent {
            val channels by vm.channels.collectAsState()
            val selected = channel
            IPTVTheme {
                PlayerContent(selected, emptyList(), PlaybackController.State(playing = true), {}, {}, {}, {},
                    guide = { active, close ->
                        SideEffect { guideRendered++ }
                        DisposableEffect(Unit) { guideMounted++; onDispose { guideDisposed++ } }
                        PlayerGuideOverlay(vm, channels, selected.id, now, {}, close, active)
                    }, video = { Box(Modifier) })
            }
        }
        compose.waitUntil(10_000) { vm.channels.value.size == 2 }
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithTag("player-remote").assertIsFocused()
        compose.onNodeWithTag("player-guide-overlay").assertDoesNotExist()
        compose.onAllNodesWithText("Сейчас A").assertCountEquals(0)
        compose.runOnIdle { Assert.assertEquals(0, guideMounted) }
        fun openGuide(title: String = "Сейчас A") {
            compose.onNodeWithTag("player-remote").performKeyInput { pressKey(Key.DirectionDown) }
            compose.mainClock.advanceTimeBy(100)
            compose.onNodeWithText("Телегид").performKeyInput { pressKey(Key.DirectionCenter) }
            compose.waitUntil(10_000) {
                compose.onAllNodes(hasText(title) and hasClickAction() and isFocused()).fetchSemanticsNodes().size == 1
            }
        }
        openGuide()
        compose.onNode(hasText("Сейчас A") and hasClickAction()).performKeyInput { pressKey(Key.DirectionRight) }
        compose.mainClock.advanceTimeBy(100)
        compose.onNode(hasText("Далее A") and hasClickAction()).assertIsFocused()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithTag("player-guide-overlay").assertDoesNotExist()
        compose.onNodeWithTag("player-remote").assertIsFocused()
        compose.runOnIdle { Assert.assertEquals(1, guideDisposed) }
        val closedRenders = guideRendered
        compose.runOnIdle { channel = vm.channels.value.single { it.id == "B" } }
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithTag("player-remote").assertIsFocused()
        compose.runOnIdle {
            Assert.assertEquals(1, guideMounted); Assert.assertEquals(1, guideDisposed)
            Assert.assertEquals("A closed guide must not recompose on channel changes", closedRenders, guideRendered)
        }
        openGuide("Сейчас B")
        compose.runOnIdle {
            Assert.assertEquals(2, guideMounted)
            Assert.assertEquals(1, guideDisposed)
        }
    }
    @Test fun inactivePlayerDoesNotBuildGuideOrTakeFocusOrBack() {
        var active by mutableStateOf(false)
        var outerBack = 0
        var playerBack = 0
        var mounted = 0
        var disposed = 0
        val channel = Channel(id = "A", playlistId = "playlist", name = "Канал A", streamUrl = "http://127.0.0.1:1/a")
        val outsideFocus = androidx.compose.ui.focus.FocusRequester()
        compose.setContent { IPTVTheme {
            androidx.activity.compose.BackHandler { outerBack++ }
            Box {
                PlayerContent(channel, emptyList(), PlaybackController.State(playing = true), {}, {}, { playerBack++ }, {},
                    active = active, guide = { _, _ ->
                        DisposableEffect(Unit) { mounted++; onDispose { disposed++ } }
                    }, video = {})
                if (!active) home.iptv.ui.TvButton("Список каналов", {},
                    Modifier.focusRequester(outsideFocus))
            }
            LaunchedEffect(active) {
                if (!active) { withFrameNanos {}; outsideFocus.requestFocus() }
            }
        } }
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithText("Список каналов").assertIsFocused()
        compose.runOnIdle {
            Assert.assertEquals(0, mounted)
            compose.activity.onBackPressedDispatcher.onBackPressed()
            Assert.assertEquals(1, outerBack)
            Assert.assertEquals(0, playerBack)
            active = true
        }
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithTag("player-remote").assertIsFocused()
        compose.runOnIdle {
            compose.activity.onBackPressedDispatcher.onBackPressed()
            Assert.assertEquals(1, playerBack)
            active = false
        }
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithText("Список каналов").assertIsFocused()
        compose.runOnIdle { Assert.assertEquals(0, mounted); Assert.assertEquals(0, disposed) }
    }
    @Test fun playerQueriesProgrammesOnlyWhileInformationPanelIsVisible() {
        val a = Channel(id = "A", playlistId = "playlist", name = "Канал A", streamUrl = "http://127.0.0.1:1/a")
        val b = a.copy(id = "B", name = "Канал B")
        var channel by mutableStateOf(a)
        val requests = mutableListOf<List<String>>()
        var subscriptions = 0
        compose.setContent { IPTVTheme {
            val controller = home.iptv.feature.player.rememberPlaybackController(null, "", false, {})
            home.iptv.feature.player.PlayerScreen(channel, listOf(a, b), { ids, _, _ ->
                kotlinx.coroutines.flow.flow {
                    requests += ids
                    subscriptions++
                    try {
                        emit(emptyList<ChannelProgram>())
                        kotlinx.coroutines.awaitCancellation()
                    } finally { subscriptions-- }
                }
            }, {}, {}, {}, controller, active = true, guide = { _, _ -> })
        } }
        compose.mainClock.advanceTimeBy(1000)
        repeat(4) { step ->
            compose.runOnIdle { channel = if (step % 2 == 0) b else a }
            compose.mainClock.advanceTimeBy(500)
        }
        compose.runOnIdle { Assert.assertTrue(requests.isEmpty()); Assert.assertEquals(0, subscriptions) }
        compose.onNodeWithTag("player-remote").performKeyInput { pressKey(Key.DirectionDown) }
        compose.mainClock.advanceTimeBy(100)
        compose.runOnIdle {
            Assert.assertEquals(listOf(listOf("A"), listOf("B")), requests)
            Assert.assertEquals(2, subscriptions)
            compose.activity.onBackPressedDispatcher.onBackPressed()
        }
        compose.mainClock.advanceTimeBy(100)
        compose.runOnIdle {
            Assert.assertEquals(0, subscriptions)
            channel = b
        }
        compose.mainClock.advanceTimeBy(1000)
        compose.runOnIdle { Assert.assertEquals(2, requests.size); Assert.assertEquals(0, subscriptions) }
        compose.onNodeWithTag("player-remote").performKeyInput { pressKey(Key.DirectionDown) }
        compose.mainClock.advanceTimeBy(100)
        compose.runOnIdle {
            Assert.assertEquals(listOf(listOf("B"), listOf("A")), requests.takeLast(2))
            Assert.assertEquals(2, subscriptions)
        }
    }
    @Test fun informationPanelUsesCachedProgramsBeforeTheDatabaseResponds() {
        val now = System.currentTimeMillis()
        val a = Channel(id = "A", playlistId = "playlist", name = "Канал A", streamUrl = "http://127.0.0.1:1/a")
        val b = a.copy(id = "B", name = "Канал B")
        val current = Program(sourceId = "epg", generation = "g", epgId = "A", start = now - 60_000,
            end = now + 600_000, title = "Из кеша A", description = "Описание из кеша")
        val next = current.copy(id = "next", start = current.end, end = now + 3_600_000, title = "Далее из кеша")
        val history = current.copy(id = "history", epgId = "B", title = "Из кеша B")
        val updates = kotlinx.coroutines.flow.MutableSharedFlow<List<ChannelProgram>>(replay = 1)
        compose.setContent { IPTVTheme {
            val controller = home.iptv.feature.player.rememberPlaybackController(null, "", false, {})
            home.iptv.feature.player.PlayerScreen(a, listOf(a, b), { _, _, _ -> updates }, {}, {}, {},
                controller, active = true, initialPrograms = { _, _, _ -> mapOf("A" to listOf(current, next), "B" to listOf(history)) },
                guide = { _, _ -> })
        } }
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithTag("player-remote").performKeyInput { pressKey(Key.DirectionDown) }
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithText("Из кеша A").assertIsDisplayed()
        compose.onNodeWithText("Описание из кеша").assertIsDisplayed()
        compose.onNodeWithText("Далее из кеша", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Из кеша B").assertIsDisplayed()
        compose.onNodeWithText("Телепрограмма отсутствует").assertDoesNotExist()
        compose.runOnIdle {
            updates.tryEmit(listOf(ChannelProgram(current.copy(title = "Обновлённая передача"), "A"),
                ChannelProgram(next, "A"), ChannelProgram(history, "B")))
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Обновлённая передача").fetchSemanticsNodes().size == 1 }
        compose.onNodeWithText("Телегид").assertIsFocused()
    }
    @Test fun closedInformationDoesNotPrepareDuringOrAfterChannelChanges() {
        val a = Channel(id = "A", playlistId = "playlist", name = "Канал A", streamUrl = "http://127.0.0.1:1/a")
        val b = a.copy(id = "B", name = "Канал B")
        var channel by mutableStateOf(a)
        var requests = 0
        var collectors = 0
        compose.setContent { IPTVTheme {
            PlayerContent(channel, emptyList(), PlaybackController.State(playing = true, firstFrameMs = 100),
                {}, {}, {}, {}, infoPrograms = { _ ->
                    DisposableEffect(Unit) {
                        requests++
                        collectors++
                        onDispose { collectors-- }
                    }
                    emptyList<Program>() to emptyMap<String, Program>()
                }, video = {})
        } }
        repeat(4) { step ->
            compose.mainClock.advanceTimeBy(400)
            compose.runOnIdle { channel = if (step % 2 == 0) b else a }
        }
        compose.runOnIdle { Assert.assertEquals(0, requests) }
        compose.mainClock.advanceTimeBy(1500)
        compose.onNodeWithTag("player-remote").assertIsFocused()
        compose.onNodeWithText("Телегид").assertDoesNotExist()
        compose.runOnIdle {
            Assert.assertEquals(0, requests)
            Assert.assertEquals(0, collectors)
        }
        compose.onNodeWithTag("player-remote").performKeyInput { pressKey(Key.DirectionDown) }
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithText("Телегид").assertIsFocused()
        compose.runOnIdle {
            Assert.assertEquals(1, requests)
            compose.activity.onBackPressedDispatcher.onBackPressed()
        }
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("player-remote").assertIsFocused()
        compose.runOnIdle { Assert.assertEquals(0, collectors); Assert.assertEquals(1, requests) }
    }
    @Test fun confirmOpeningInformationDoesNotAlsoOpenTheGuide() {
        val channel = Channel(id = "A", playlistId = "playlist", name = "Канал A", streamUrl = "http://127.0.0.1:1/a")
        compose.setContent { IPTVTheme {
            PlayerContent(channel, emptyList(), PlaybackController.State(), {}, {}, {}, {},
                guide = { _, _ -> androidx.tv.material3.Text("Открытый телегид") }, video = {})
        } }
        compose.mainClock.advanceTimeBy(100)
        sendConfirm(android.view.KeyEvent.ACTION_DOWN)
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithText("Телегид").assertIsFocused()
        sendConfirm(android.view.KeyEvent.ACTION_UP)
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithText("Телегид").assertIsDisplayed()
        compose.onNodeWithText("Открытый телегид").assertDoesNotExist()
        compose.onNodeWithText("Телегид").performKeyInput { pressKey(Key.DirectionCenter) }
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithText("Открытый телегид").assertIsDisplayed()
    }
    @Test fun retainedInformationShowsHistoryLoadedAfterTheFirstOpen() {
        val a = Channel(id = "A", playlistId = "playlist", name = "Канал A", streamUrl = "http://127.0.0.1:1/a")
        val b = a.copy(id = "B", name = "Канал B")
        val c = a.copy(id = "C", name = "Канал C")
        var history by mutableStateOf(emptyList<Channel>())
        compose.setContent { IPTVTheme {
            PlayerContent(a, emptyList(), PlaybackController.State(), {}, {}, {}, {},
                recentChannels = history, video = {})
        } }
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithTag("player-remote").performKeyInput { pressKey(Key.DirectionDown) }
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithText("Телегид").assertIsFocused()
        compose.runOnIdle { history = listOf(a, b) }
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithContentDescription("Смотреть Канал B").assertIsDisplayed()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.mainClock.advanceTimeBy(100)
        compose.runOnIdle { history = listOf(a, b, c) }
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithContentDescription("Смотреть Канал C").assertDoesNotExist()
        compose.onNodeWithTag("player-remote").performKeyInput { pressKey(Key.DirectionDown) }
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithContentDescription("Смотреть Канал C").assertIsDisplayed()
        compose.onNodeWithText("Телегид").assertIsFocused()
    }
    @Test fun overlayGuideKeepsVideoMountedAndSupportsFutureProgramsAndChannelSelection() {
        var chosen = ""
        var videoDisposed = 0
        val now = System.currentTimeMillis()
        val channel = Channel(id = "A", playlistId = "playlist", name = "Канал A", streamUrl = "http://127.0.0.1:1/a")
        compose.setContent {
            val channels by vm.channels.collectAsState()
            IPTVTheme {
                PlayerContent(channel, emptyList(), PlaybackController.State(), {}, {}, {}, {},
                    guide = { active, close -> PlayerGuideOverlay(vm, channels, "A", now, { chosen = it.id }, close, active) },
                    video = {
                        Box(Modifier.testTag("persistent-video"))
                        DisposableEffect(Unit) { onDispose { videoDisposed++ } }
                    })
            }
        }
        compose.waitUntil(10_000) { vm.channels.value.size == 2 }
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithTag("player-remote").performKeyInput { pressKey(Key.DirectionDown) }
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithText("Телегид").performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Сейчас A").fetchSemanticsNodes().isNotEmpty() }
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithTag("player-guide-overlay").assertExists()
        val screenBounds = compose.onRoot().getUnclippedBoundsInRoot()
        val guideBounds = compose.onNodeWithTag("player-guide-panel").getUnclippedBoundsInRoot()
        org.junit.Assert.assertEquals(screenBounds.bottom - screenBounds.top, guideBounds.bottom - guideBounds.top)
        compose.onAllNodes(hasText("Back", substring = true)).assertCountEquals(0)
        compose.onAllNodes(hasText("OK —", substring = true)).assertCountEquals(0)
        // The guide must stay open beyond the information panel's inactivity timeout.
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(30, java.util.concurrent.TimeUnit.SECONDS)
        compose.mainClock.advanceTimeBy(30_000)
        compose.waitForIdle()
        compose.onNodeWithTag("player-guide-overlay").assertExists()
        compose.onNodeWithTag("persistent-video").assertExists()
        compose.runOnIdle { org.junit.Assert.assertEquals(0, videoDisposed) }
        compose.onNode(hasText("Сейчас A") and hasClickAction()).assertIsFocused().performKeyInput { pressKey(Key.DirectionRight) }
        compose.mainClock.advanceTimeBy(100)
        compose.onNode(hasText("Далее A") and hasClickAction()).performKeyInput { pressKey(Key.DirectionCenter) }
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithText("Смотреть канал в эфире").assertIsFocused()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.mainClock.advanceTimeBy(100)
        compose.onNode(hasText("Далее A") and hasClickAction()).assertIsFocused().performKeyInput { pressKey(Key.DirectionLeft) }
        compose.mainClock.advanceTimeBy(100)
        compose.onNode(hasText("Сейчас A") and hasClickAction()).performKeyInput { pressKey(Key.DirectionDown) }
        compose.mainClock.advanceTimeBy(100)
        compose.onNode(hasText("Сейчас B") and hasClickAction()).assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithTag("player-guide-overlay").assertDoesNotExist()
        compose.onNodeWithTag("persistent-video").assertExists()
        compose.runOnIdle {
            org.junit.Assert.assertEquals("B", chosen)
            org.junit.Assert.assertEquals(0, videoDisposed)
        }
    }
    @Test fun recentChannelsPersistInOrderWithoutDuplicatesAndStayBounded() = runBlocking {
        vm.graph.preferences.played("A", "all")
        vm.graph.preferences.played("B", "all")
        vm.graph.preferences.played("A", "all")
        val reloaded = Preferences(ApplicationProvider.getApplicationContext<IptvApplication>()).flow.first()
        org.junit.Assert.assertEquals(listOf("A", "B"), reloaded.recentChannelIds)
        repeat(25) { vm.graph.preferences.played("channel-$it", "all") }
        val bounded = vm.graph.preferences.flow.first()
        org.junit.Assert.assertEquals(10, bounded.recentChannelIds.size)
        org.junit.Assert.assertEquals("channel-24", bounded.recentChannelIds.first())
        org.junit.Assert.assertEquals("channel-15", bounded.recentChannelIds.last())
        org.junit.Assert.assertFalse(bounded.autoResume)
    }
    private fun capture(name: String) {
        val folder = File("build/reports/ui").apply { mkdirs() }
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap))
            File(folder, name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
