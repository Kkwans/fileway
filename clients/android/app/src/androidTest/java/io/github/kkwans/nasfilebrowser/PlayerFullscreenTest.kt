package io.github.kkwans.nasfilebrowser

import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import io.github.kkwans.nasfilebrowser.app.ClientModel
import io.github.kkwans.nasfilebrowser.data.BackendKind
import io.github.kkwans.nasfilebrowser.data.ClientDatabase
import io.github.kkwans.nasfilebrowser.data.CredentialVault
import io.github.kkwans.nasfilebrowser.data.ProfileStore
import io.github.kkwans.nasfilebrowser.data.ServerProfile
import io.github.kkwans.nasfilebrowser.player.PlaybackTraceAction
import io.github.kkwans.nasfilebrowser.player.MediaSubtitleLayer
import io.github.kkwans.nasfilebrowser.player.PlayerViewport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/** Real Activity/dialog windows and native Surface geometry, not just HUD semantics. */
@RunWith(AndroidJUnit4::class)
class PlayerFullscreenTest {
    private val trace = OwnedUiTraceRule()
    private val activity = ActivityScenarioRule(MainActivity::class.java)
    @get:Rule val rules: RuleChain = RuleChain.outerRule(trace).around(activity)

    @Test fun portraitAndLandscapeKeepTheirSurfaceWhenControlsOrSystemBarsAppear() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val media = instrumentation.context.assets.open("media/fixture.mkv").use { it.readBytes() }
        val source = NativePlaybackTest.Fixture(media)
        val database = ClientDatabase.get(instrumentation.targetContext)
        val previousSession = database.profiles().activeSession()
        val store = ProfileStore(database, CredentialVault(instrumentation.targetContext))
        val profile = store.save(ServerProfile(name = "Fullscreen fixture", address = source.url))
        lateinit var model: ClientModel
        activity.scenario.onActivity { model = ViewModelProvider(it)[ClientModel::class.java] }
        suspend fun main(action: () -> Unit) = withContext(Dispatchers.Main) { action() }
        fun traceWindow(stage: String) {
            activity.scenario.onActivity { host ->
                val decor = host.window.decorView
                OwnedUiTraceRule.trace("fullscreen=$stage orientation=${host.resources.configuration.orientation} " +
                    "requested=${host.requestedOrientation} decor=${decor.width}x${decor.height} " +
                    "focus=${host.hasWindowFocus()} generation=${model.player.state.value.mediaGeneration}")
            }
        }
        fun click(label: String) {
            val action = device.wait(Until.findObject(By.desc(label)), 5_000) ?: error("Missing player action $label")
            assertTrue("Player action must be enabled: $label", action.isEnabled)
            OwnedUiTraceRule.trace("fullscreen-action=$label bounds=${action.visibleBounds}")
            traceWindow("before-$label")
            action.click()
            traceWindow("after-$label")
        }
        fun detailsGone() {
            val gone = device.wait(Until.gone(By.desc("播放详情")), 5_000)
            if (!gone) {
                OwnedUiTraceRule.trace("fullscreen-entry-failure ${trace.snapshot()}")
                traceWindow("details-remain")
                device.executeShellCommand("mkdir -p /sdcard/Download/nfb-client-acceptance")
                device.executeShellCommand("screencap -p /sdcard/Download/nfb-client-acceptance/fullscreen-entry-failure-${SystemClock.uptimeMillis()}.png")
            }
            assertTrue("Fullscreen must hide details: exit=${device.hasObject(By.desc("退出全屏"))}, screen=${device.displayWidth}x${device.displayHeight}", gone)
        }
        fun capture(stage: String) {
            if (InstrumentationRegistry.getArguments().getString("nfbTraceUi") != "true") return
            val name = "player075-$stage-${SystemClock.uptimeMillis()}"
            device.executeShellCommand("mkdir -p /sdcard/Download/nfb-client-acceptance")
            device.executeShellCommand("screencap -p /sdcard/Download/nfb-client-acceptance/$name.png")
            OwnedUiTraceRule.trace("fullscreen-capture=$name")
        }
        fun findViewport(view: View): PlayerViewport? {
            if (view is PlayerViewport) return view
            if (view is ViewGroup) for (index in 0 until view.childCount) findViewport(view.getChildAt(index))?.let { return it }
            return null
        }
        fun geometry(): Pair<PlayerViewport, List<Rect>> {
            lateinit var viewport: PlayerViewport
            lateinit var rectangles: List<Rect>
            activity.scenario.onActivity { host ->
                viewport = findViewport(host.window.decorView) ?: error("Native viewport missing")
                val layers = mutableListOf<View>(viewport, viewport.video, viewport.text)
                fun collect(view: View) {
                    if (view is MediaSubtitleLayer) layers += view
                    if (view is ViewGroup) for (index in 0 until view.childCount) collect(view.getChildAt(index))
                }
                collect(viewport)
                rectangles = layers.map { view ->
                    Rect().also { assertTrue("Native view must remain visible", view.getGlobalVisibleRect(it)) }
                }
                assertTrue("Fullscreen must keep a valid native Surface", viewport.video.holder.surface.isValid)
            }
            return viewport to rectangles
        }
        suspend fun hiddenFocusedWindow() {
            withTimeout(5_000) {
                while (true) {
                    val hidden = withContext(Dispatchers.Main) {
                        val focused = WindowInspector.getGlobalWindowViews().filter { it.isShown && it.hasWindowFocus() }
                        focused.isNotEmpty() && focused.all { view ->
                            ViewCompat.getRootWindowInsets(view)?.let {
                                !it.isVisible(WindowInsetsCompat.Type.statusBars()) && !it.isVisible(WindowInsetsCompat.Type.navigationBars())
                            } == true
                        }
                    }
                    if (hidden) break
                    delay(50)
                }
            }
        }
        suspend fun checkFullscreen() {
            hiddenFocusedWindow()
            val before = geometry()
            assertTrue("Fullscreen viewport must occupy the full screen height", before.second.first().height() >= device.displayHeight * .98f)
            val generation = model.player.state.value.mediaGeneration
            val mediaSets = model.player.diagnosticSnapshot().count { it.action == PlaybackTraceAction.MEDIA_SET }
            val direction = if (device.displayWidth > device.displayHeight) "landscape" else "portrait"
            capture("$direction-controls")
            device.click(device.displayWidth / 2, device.displayHeight / 2)
            hiddenFocusedWindow()
            assertEquals("Ordinary touch must not resize video", before, geometry())
            for (panel in listOf("选择字幕", "播放速度")) {
                click(panel)
                assertTrue(device.wait(Until.hasObject(By.desc("播放设置")), 5_000))
                hiddenFocusedWindow()
                assertEquals("A focused settings window must not resize video", before, geometry())
                capture("$direction-${if (panel == "选择字幕") "subtitles" else "speed"}")
                if (panel == "播放速度") {
                    val field = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 5_000)
                        ?: error("Custom rate input missing")
                    field.click()
                    suspend fun keyboard(shown: Boolean) = withTimeout(5_000) {
                        while (true) {
                            val matches = withContext(Dispatchers.Main) {
                                WindowInspector.getGlobalWindowViews().any { view ->
                                    view.isShown && view.hasWindowFocus() &&
                                        ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == shown
                                }
                            }
                            if (matches) break
                            delay(50)
                        }
                    }
                    keyboard(true)
                    assertEquals("IME must not resize any video/subtitle layer", before, geometry())
                    device.pressBack()
                    keyboard(false)
                    assertTrue("Back closes the keyboard before its panel", device.hasObject(By.desc("播放设置")))
                    assertEquals(before, geometry())
                }
                click("关闭播放设置")
                assertTrue(device.wait(Until.gone(By.desc("播放设置")), 5_000))
                hiddenFocusedWindow()
                assertEquals(before, geometry())
            }
            // Exercise real inset changes as well as hidden bars. Manual shade
            // gestures remain a separate real-device interaction acceptance.
            activity.scenario.onActivity { host ->
                WindowCompat.getInsetsController(host.window, host.window.decorView).show(WindowInsetsCompat.Type.systemBars())
            }
            withTimeout(5_000) {
                while (true) {
                    var shown = false
                    activity.scenario.onActivity { host -> shown = ViewCompat.getRootWindowInsets(host.window.decorView)?.isVisible(WindowInsetsCompat.Type.statusBars()) == true }
                    if (shown) break
                    delay(50)
                }
            }
            assertEquals("Visible transient system bars must overlay the same Surface", before, geometry())
            activity.scenario.onActivity { host ->
                WindowCompat.getInsetsController(host.window, host.window.decorView).hide(WindowInsetsCompat.Type.systemBars())
            }
            hiddenFocusedWindow()
            assertEquals(before, geometry())
            // A real top-edge gesture must remain available. Do not capture the
            // owner's notification shade; inspect only our native geometry.
            assertTrue(device.swipe(device.displayWidth / 2, 1, device.displayWidth / 2, device.displayHeight / 5, 20))
            withTimeout(5_000) {
                while (true) {
                    var revealed = false
                    activity.scenario.onActivity { host -> revealed = !host.hasWindowFocus() ||
                        ViewCompat.getRootWindowInsets(host.window.decorView)?.isVisible(WindowInsetsCompat.Type.statusBars()) == true }
                    if (revealed) break
                    delay(50)
                }
            }
            assertEquals("A real system shade gesture must overlay the same native layers", before, geometry())
            if (device.currentPackageName != instrumentation.targetContext.packageName) device.pressBack()
            hiddenFocusedWindow()
            assertEquals(before, geometry())
            assertTrue("Closing system UI must keep fullscreen", device.hasObject(By.desc("退出全屏")))
            assertEquals(generation, model.player.state.value.mediaGeneration)
            assertEquals("Fullscreen and settings must not reopen media", mediaSets,
                model.player.diagnosticSnapshot().count { it.action == PlaybackTraceAction.MEDIA_SET })
            assertFalse("Layout changes must preserve the pause intent", model.player.state.value.playing)
        }
        try {
            device.setOrientationNatural()
            main { model.selectProfile(profile); model.connectDraft(profile.name, source.url, BackendKind.NAS, "fixture", "fixture-only", "direct") }
            withTimeout(15_000) { model.state.first { it.connected && !it.busy } }
            main { model.open(model.state.value.files.single()) }
            withTimeout(15_000) { model.player.state.first { it.durationMs > 0 && it.playing && it.canSavePosition } }
            main { model.player.pause() }
            click("竖屏全屏")
            detailsGone()
            checkFullscreen()
            click("退出全屏")
            assertTrue(device.wait(Until.hasObject(By.desc("播放详情")), 5_000))
            device.unfreezeRotation()
            click("横屏全屏")
            detailsGone()
            withTimeout(5_000) { while (device.displayWidth <= device.displayHeight) delay(50) }
            checkFullscreen()
            device.pressBack()
            assertTrue(device.wait(Until.hasObject(By.desc("播放详情")), 5_000))
            assertNotNull("Back exits fullscreen before leaving the video", model.state.value.selected)
        } finally {
            main { model.disconnect() }
            device.setOrientationNatural()
            device.unfreezeRotation()
            store.remove(profile)
            source.close()
            previousSession?.let { if (database.profiles().account(it.accountKey) != null) database.profiles().saveActiveSession(it) }
        }
    }
}
