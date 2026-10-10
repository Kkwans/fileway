package io.github.kkwans.nasfilebrowser

import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.InputDevice
import android.view.MotionEvent
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
import kotlinx.coroutines.TimeoutCancellationException
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
        val inputDriver = InstrumentationRegistry.getArguments().getString("nfbInputDriver", "automation")
        require(inputDriver in setOf("automation", "shell"))
        var inspectDown = InstrumentationRegistry.getArguments().getString("nfbInspectDown") == "true"
        fun rejectedInput(stage: String) {
            activity.scenario.onActivity { host -> OwnedUiTraceRule.trace("rejected-input=$stage " +
                "requested=${host.requestedOrientation} focus=${host.hasWindowFocus()} task=${host.taskId}") }
            val input = device.executeShellCommand("dumpsys input").lines()
            val start = input.indexOfFirst { it.contains("Input Dispatcher State:") }
            if (start >= 0) {
                input.drop(start).takeWhile { it.trim() != "Windows:" }.take(60)
                    .forEach { OwnedUiTraceRule.trace("input-state=${it.trim()}") }
                // Identify only potential interceptors above this activity.
                // These are input-window identities/regions, never view text.
                val windows = input.drop(start).dropWhile { it.trim() != "Windows:" }.drop(1)
                    .takeWhile { !it.contains("RecentQueue:") }
                    .filter { it.trim().firstOrNull()?.isDigit() == true && it.contains(": name=") }.take(14)
                for (line in windows) {
                    OwnedUiTraceRule.trace("input-above=${line.trim()}")
                    if (line.contains("nasfilebrowser.MainActivity")) break
                }
                input.filter { it.contains("io.github.kkwans.nasfilebrowser") }.take(12)
                    .forEach { OwnedUiTraceRule.trace("input-owned=${it.trim()}") }
                input.dropWhile { !it.trim().startsWith("RecentQueue:") }.drop(1)
                    .takeWhile { it.trim().isNotEmpty() }.filter { it.contains("MotionEvent") }.take(10)
                    .forEach { OwnedUiTraceRule.trace("input-recent=${it.trim()}") }
            }
        }
        fun physicalTap(bounds: Rect) {
            OwnedUiTraceRule.trace("fullscreen-tap-driver=$inputDriver bounds=$bounds")
            if (inputDriver == "shell") {
                // Controlled comparison of two real input-injection paths on OEM devices.
                // Both hit the actual button; no semantic click, retry or model callback.
                // executeShellCommand uses Runtime.exec, not a shell parser.
                // Completion is transport evidence only; callers still verify
                // delivered touches and the resulting UI/IME state.
                device.executeShellCommand("input touchscreen tap ${bounds.centerX()} ${bounds.centerY()}")
                return
            }
            val downTime = SystemClock.uptimeMillis()
            fun inject(action: Int): Boolean {
                val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
                    bounds.centerX().toFloat(), bounds.centerY().toFloat(), 0)
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                return try { instrumentation.uiAutomation.injectInputEvent(event, true) }
                finally { event.recycle() }
            }
            val down = inject(MotionEvent.ACTION_DOWN)
            if (!down) rejectedInput("DOWN")
            assertTrue("Owned tap DOWN must be injected at $bounds", down)
            if (inspectDown) { inspectDown = false; rejectedInput("DOWN-observed-before-UP") }
            var released = false
            try {
                SystemClock.sleep(100) // Same normal tap duration as UiDevice's click.
                released = inject(MotionEvent.ACTION_UP)
                if (!released) rejectedInput("UP")
                assertTrue("Owned tap UP must be injected at $bounds", released)
            } finally { if (!released) inject(MotionEvent.ACTION_CANCEL) }
        }
        fun traceWindow(stage: String) {
            activity.scenario.onActivity { host ->
                val decor = host.window.decorView
                OwnedUiTraceRule.trace("fullscreen=$stage orientation=${host.resources.configuration.orientation} " +
                    "requested=${host.requestedOrientation} decor=${decor.width}x${decor.height} " +
                    "focus=${host.hasWindowFocus()} display=${decor.display?.displayId} flags=${host.window.attributes.flags} " +
                    "generation=${model.player.state.value.mediaGeneration}")
            }
        }
        fun click(label: String) {
            val action = device.wait(Until.findObject(By.desc(label)), 5_000) ?: error("Missing player action $label")
            assertTrue("Player action must be enabled: $label", action.isEnabled)
            assertTrue("Player action must be clickable: $label", action.isClickable)
            val bounds = action.visibleBounds
            assertFalse("Player action must have visible bounds: $label", bounds.isEmpty)
            OwnedUiTraceRule.trace("fullscreen-action=$label bounds=$bounds")
            traceWindow("before-$label")
            val touches = trace.deliveredTouches()
            val inputState = "screen=${device.displayWidth}x${device.displayHeight}, on=${device.isScreenOn}, rotation=${device.displayRotation}"
            OwnedUiTraceRule.trace("fullscreen-input=$label $inputState")
            // Reuse the checked event path exercised by PlayerGestureControlsTest.
            // This targets the same actual button, never a semantic/model shortcut.
            val started = SystemClock.uptimeMillis()
            physicalTap(bounds)
            OwnedUiTraceRule.trace("fullscreen-tap=$label elapsedMs=${SystemClock.uptimeMillis() - started}")
            traceWindow("after-$label")
            if (label in listOf("竖屏全屏", "横屏全屏", "退出全屏")) {
                assertTrue("Fullscreen tap must reach the owned Activity: $label; ${trace.snapshot()}",
                    trace.deliveredTouches() >= touches + 2)
            }
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
        var stage = "entry"
        suspend fun awaitStage(name: String, ready: suspend () -> Boolean) {
            stage = name
            OwnedUiTraceRule.trace("fullscreen-stage=$stage")
            try { withTimeout(5_000) {
                while (!ready()) delay(50)
            } } catch (failure: TimeoutCancellationException) {
                val windows = withContext(Dispatchers.Main) {
                    WindowInspector.getGlobalWindowViews().filter { it.isShown }.map { root ->
                        val insets = ViewCompat.getRootWindowInsets(root)
                        "${root.javaClass.simpleName}:focus=${root.hasWindowFocus()}," +
                            "status=${insets?.isVisible(WindowInsetsCompat.Type.statusBars())}," +
                            "navigation=${insets?.isVisible(WindowInsetsCompat.Type.navigationBars())}," +
                            "ime=${insets?.isVisible(WindowInsetsCompat.Type.ime())}"
                    }
                }
                throw AssertionError("Fullscreen wait failed at $stage; windows=$windows; ${trace.snapshot()}", failure)
            }
        }
        suspend fun hiddenFocusedWindow(name: String) {
            awaitStage(name) {
                withContext(Dispatchers.Main) {
                    val focused = WindowInspector.getGlobalWindowViews().filter { it.isShown && it.hasWindowFocus() }
                    focused.isNotEmpty() && focused.all { root ->
                        ViewCompat.getRootWindowInsets(root)?.let {
                            !it.isVisible(WindowInsetsCompat.Type.statusBars()) && !it.isVisible(WindowInsetsCompat.Type.navigationBars())
                        } == true
                    }
                }
            }
        }
        suspend fun checkFullscreen() {
            hiddenFocusedWindow("entry-hidden")
            val before = geometry()
            assertTrue("Fullscreen viewport must occupy the full screen height", before.second.first().height() >= device.displayHeight * .98f)
            val generation = model.player.state.value.mediaGeneration
            val mediaSets = model.player.diagnosticSnapshot().count { it.action == PlaybackTraceAction.MEDIA_SET }
            val direction = if (device.displayWidth > device.displayHeight) "landscape" else "portrait"
            capture("$direction-controls")
            val touches = trace.deliveredTouches()
            physicalTap(Rect(device.displayWidth / 2 - 1, device.displayHeight / 2 - 1,
                device.displayWidth / 2 + 1, device.displayHeight / 2 + 1))
            assertTrue("Ordinary touch must reach the owned Activity", trace.deliveredTouches() >= touches + 2)
            hiddenFocusedWindow("ordinary-touch-hidden")
            assertEquals("Ordinary touch must not resize video", before, geometry())
            for (panel in listOf("选择字幕", "播放速度")) {
                click(panel)
                assertTrue(device.wait(Until.hasObject(By.desc("播放设置")), 5_000))
                hiddenFocusedWindow("panel-$panel-hidden")
                assertEquals("A focused settings window must not resize video", before, geometry())
                capture("$direction-${if (panel == "选择字幕") "subtitles" else "speed"}")
                if (panel == "播放速度") {
                    val field = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 5_000)
                        ?: error("Custom rate input missing")
                    val bounds = field.visibleBounds
                    physicalTap(bounds)
                    suspend fun keyboard(shown: Boolean) = awaitStage("ime-$shown") {
                        withContext(Dispatchers.Main) {
                            WindowInspector.getGlobalWindowViews().any { view ->
                                view.isShown && view.hasWindowFocus() &&
                                    ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == shown
                            }
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
                hiddenFocusedWindow("panel-$panel-closed")
                assertEquals(before, geometry())
            }
            // Exercise real inset changes as well as hidden bars. Manual shade
            // gestures remain a separate real-device interaction acceptance.
            activity.scenario.onActivity { host ->
                WindowCompat.getInsetsController(host.window, host.window.decorView).show(WindowInsetsCompat.Type.systemBars())
            }
            awaitStage("system-bars-shown") {
                var shown = false
                activity.scenario.onActivity { host -> shown = ViewCompat.getRootWindowInsets(host.window.decorView)?.isVisible(WindowInsetsCompat.Type.statusBars()) == true }
                shown
            }
            assertEquals("Visible transient system bars must overlay the same Surface", before, geometry())
            activity.scenario.onActivity { host ->
                WindowCompat.getInsetsController(host.window, host.window.decorView).hide(WindowInsetsCompat.Type.systemBars())
            }
            hiddenFocusedWindow("system-bars-hidden")
            assertEquals(before, geometry())
            // A real top-edge gesture must remain available. Do not capture the
            // owner's notification shade; inspect only our native geometry.
            assertTrue(device.swipe(device.displayWidth / 2, 1, device.displayWidth / 2, device.displayHeight / 5, 20))
            awaitStage("real-shade-revealed") {
                var revealed = false
                activity.scenario.onActivity { host -> revealed = !host.hasWindowFocus() ||
                    ViewCompat.getRootWindowInsets(host.window.decorView)?.isVisible(WindowInsetsCompat.Type.statusBars()) == true }
                revealed
            }
            assertEquals("A real system shade gesture must overlay the same native layers", before, geometry())
            if (device.currentPackageName != instrumentation.targetContext.packageName) device.pressBack()
            hiddenFocusedWindow("real-shade-dismissed")
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
