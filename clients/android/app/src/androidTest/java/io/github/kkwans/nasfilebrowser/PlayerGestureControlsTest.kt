package io.github.kkwans.nasfilebrowser

import android.content.pm.ActivityInfo
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import androidx.test.uiautomator.Configurator
import io.github.kkwans.nasfilebrowser.app.ClientModel
import io.github.kkwans.nasfilebrowser.data.*
import io.github.kkwans.nasfilebrowser.player.PlaybackTraceAction
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/** Gesture routing uses a real native session; this is not first-frame or audio acceptance. */
@RunWith(AndroidJUnit4::class)
class PlayerGestureControlsTest {
    private val trace = OwnedUiTraceRule()
    private val activity = ActivityScenarioRule(MainActivity::class.java)
    @get:Rule val rules: RuleChain = RuleChain.outerRule(trace).around(activity)

    @Test fun pictureDragPreviewsWithoutSeekingThenCommitsOnceOrCancels(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val source = NativePlaybackTest.Fixture(instrumentation.context.assets.open("media/fixture.mkv").use { it.readBytes() })
        val database = ClientDatabase.get(instrumentation.targetContext)
        val previousSession = database.profiles().activeSession()
        val store = ProfileStore(database, CredentialVault(instrumentation.targetContext))
        val profile = store.save(ServerProfile(name = "Seek drag fixture", address = source.url))
        lateinit var model: ClientModel
        activity.scenario.onActivity { model = ViewModelProvider(it)[ClientModel::class.java] }
        suspend fun main(action: () -> Unit) = withContext(Dispatchers.Main) { action() }
        fun seeks() = model.player.diagnosticSnapshot().filter { it.action == PlaybackTraceAction.SEEK_REQUEST }
        val configuration = Configurator.getInstance()
        val oldIdle = configuration.getWaitForIdleTimeout()
        var downTime = 0L
        var held = false
        var x = 0f; var y = 0f
        var stage = "prepare"
        fun pointer(action: Int, nextX: Float = x, nextY: Float = y) {
            x = nextX; y = nextY
            if (action == MotionEvent.ACTION_DOWN) { downTime = SystemClock.uptimeMillis(); held = true }
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue("Owned pointer event must be injected", instrumentation.uiAutomation.injectInputEvent(event, true)) }
            finally { event.recycle() }
            OwnedUiTraceRule.trace("seek-drag=$stage action=$action x=$x y=$y screen=${device.displayWidth}x${device.displayHeight}")
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) held = false
        }
        fun previewDescription(node: AccessibilityNodeInfo, refresh: Boolean): String? {
            try {
                if (refresh && !node.refresh()) return null
                if (node.contentDescription?.toString() == "画面拖动进度") return AccessibilityNodeInfoCompat.wrap(node).stateDescription?.toString()
                for (index in 0 until node.childCount) {
                    node.getChild(index)?.let { previewDescription(it, refresh) }?.let { return it }
                }
                return null
            } finally { node.recycle() }
        }
        suspend fun feedback(): String {
            fun read(): String? {
                val cached = instrumentation.uiAutomation.rootInActiveWindow?.let { previewDescription(it, false) }
                val fresh = instrumentation.uiAutomation.rootInActiveWindow?.let { previewDescription(it, true) }
                if (cached == null && fresh != null) OwnedUiTraceRule.trace("seek-preview-cache=stale stage=$stage")
                return fresh
            }
            return try { withTimeout(5_000) {
                var description = read()
                while (description == null) { delay(25); description = read() }
                description
            } } catch (error: TimeoutCancellationException) {
                throw AssertionError("Seek preview missing during $stage; ${trace.snapshot()}; state=${model.player.state.value.phase}, " +
                    "seekable=${model.player.state.value.seekable}, seeks=${seeks().size}", error)
            }
        }
        try {
            main { model.selectProfile(profile); model.connectDraft(profile.name, source.url, BackendKind.NAS, "fixture", "fixture-only", "direct") }
            withTimeout(15_000) { model.state.first { it.connected && !it.busy } }
            main { model.open(model.state.value.files.single()) }
            withTimeout(20_000) { model.player.state.first { it.seekable && it.playing && it.canSavePosition } }
            main { model.player.pause(); model.player.seek(6_000) }
            withTimeout(10_000) { model.player.state.first { it.canSavePosition && it.positionMs in 5_800..6_200 } }
            val picture = device.wait(Until.findObject(By.desc("视频画面")), 5_000)?.visibleBounds ?: error("Picture missing")
            configuration.setWaitForIdleTimeout(0)
            val before = seeks().size
            stage = "release-preview"
            pointer(MotionEvent.ACTION_DOWN, picture.centerX().toFloat(), picture.centerY().toFloat())
            delay(25)
            pointer(MotionEvent.ACTION_MOVE, picture.centerX() + picture.width() * .16f)
            val preview = feedback().substringBefore('，')
            delay(200)
            assertEquals("Drag previews must not send incremental seek commands", before, seeks().size)
            assertEquals(1f, model.player.state.value.rate)
            pointer(MotionEvent.ACTION_UP)
            try { withTimeout(5_000) { while (seeks().size == before) delay(25) } }
            catch (error: TimeoutCancellationException) { throw AssertionError("Release did not seek; ${trace.snapshot()}", error) }
            assertEquals("One release sends exactly one command", before + 1, seeks().size)
            val shownMs = preview.split(':').fold(0L) { total, part -> total * 60 + part.toLong() } * 1000
            assertTrue("Requested target must match the visible preview", seeks().last().value!! in shownMs.toDouble()..(shownMs + 999).toDouble())
            withTimeout(10_000) { model.player.state.first { it.canSavePosition && !it.playing } }
            val after = seeks().size
            stage = "cancel-preview"
            pointer(MotionEvent.ACTION_DOWN, picture.centerX().toFloat(), picture.centerY().toFloat())
            delay(25)
            pointer(MotionEvent.ACTION_MOVE, picture.centerX() - picture.width() * .16f)
            feedback()
            pointer(MotionEvent.ACTION_CANCEL)
            assertTrue(device.wait(Until.gone(By.desc("画面拖动进度")), 5_000))
            assertEquals("Cancel must not seek", after, seeks().size)
            assertFalse(model.player.state.value.playing)
        } catch (error: Throwable) {
            device.executeShellCommand("mkdir -p /sdcard/Download/nfb-client-acceptance")
            device.executeShellCommand("screencap -p /sdcard/Download/nfb-client-acceptance/seek-drag-failure-${SystemClock.uptimeMillis()}.png")
            throw error
        } finally {
            if (held) pointer(MotionEvent.ACTION_CANCEL)
            configuration.setWaitForIdleTimeout(oldIdle)
            main { model.disconnect() }
            store.remove(profile); source.close()
            previousSession?.let { if (database.profiles().account(it.accountKey) != null) database.profiles().saveActiveSession(it) }
        }
    }

    @Test fun doubleTapZonesAndLocksRouteOnlyTheIntendedCommands(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val media = instrumentation.context.assets.open("media/fixture.mkv").use { it.readBytes() }
        val source = NativePlaybackTest.Fixture(media)
        val store = ProfileStore(ClientDatabase.get(instrumentation.targetContext), CredentialVault(instrumentation.targetContext))
        val profile = store.save(ServerProfile(name = "Gesture fixture", address = source.url))
        lateinit var model: ClientModel
        activity.scenario.onActivity { model = ViewModelProvider(it)[ClientModel::class.java] }
        suspend fun main(action: () -> Unit) = withContext(Dispatchers.Main) { action() }
        fun button(label: String) = device.wait(Until.findObject(By.desc(label)), 5_000) ?: error("Missing $label")
        suspend fun doubleTap(fraction: Float, bounds: Rect = button("视频画面").visibleBounds) {
            val x = bounds.left + (bounds.width() * fraction).toInt()
            device.click(x, bounds.centerY()); delay(70); device.click(x, bounds.centerY())
        }
        fun seeks() = model.player.diagnosticSnapshot().filter { it.action == PlaybackTraceAction.SEEK_REQUEST }
        try {
            main { model.selectProfile(profile); model.connectDraft(profile.name, source.url, BackendKind.NAS, "fixture", "fixture-only", "direct") }
            withTimeout(15_000) { model.state.first { it.connected && !it.busy } }
            main { model.open(model.state.value.files.single()) }
            withTimeout(20_000) { model.player.state.first { it.seekable && it.durationMs > 0 } }
            main { model.pausePlayback() }
            val beforeLeft = seeks().size
            doubleTap(.18f)
            withTimeout(5_000) { while (seeks().size == beforeLeft) delay(25) }
            assertEquals(0.0, seeks().last().value!!, .1)
            withTimeout(10_000) { model.player.state.first { it.phase != "正在跳转" } }
            val beforeRight = seeks().size
            doubleTap(.82f)
            withTimeout(5_000) { while (seeks().size == beforeRight) delay(25) }
            assertTrue(seeks().last().value!! >= 9_000)
            withTimeout(10_000) { model.player.state.first { it.phase != "正在跳转" } }
            main { model.player.seek(2_000) }
            withTimeout(10_000) { model.player.state.first { it.phase != "正在跳转" } }
            doubleTap(.5f)
            withTimeout(10_000) { model.player.state.first { it.playing } }
            val preferredRate = model.player.state.value.rate
            val picture = button("视频画面").visibleBounds
            val press = async(Dispatchers.IO) { device.swipe(picture.centerX(), picture.centerY(), picture.centerX(), picture.centerY(), 240) }
            withTimeout(3_000) { model.player.state.first { it.rate == model.playbackPreferences.holdRate.value } }
            assertTrue(press.await())
            withTimeout(3_000) { model.player.state.first { it.rate == preferredRate } }
            main { model.pausePlayback() }
            withTimeout(5_000) { model.player.state.first { !it.playing } }

            var oldOrientation = 0
            activity.scenario.onActivity { oldOrientation = it.requestedOrientation }
            button("锁定屏幕方向").click()
            instrumentation.waitForIdleSync()
            activity.scenario.onActivity { assertEquals(ActivityInfo.SCREEN_ORIENTATION_LOCKED, it.requestedOrientation) }
            button("解除方向锁定").click()
            instrumentation.waitForIdleSync()
            activity.scenario.onActivity { assertEquals(oldOrientation, it.requestedOrientation) }

            val lockedBounds = button("视频画面").visibleBounds
            button("锁定触控").click()
            button("解除触控锁定")
            val count = seeks().size
            // The lock deliberately removes covered controls from accessibility discovery.
            // Inject at the real pre-lock picture coordinates to verify that touches do nothing.
            doubleTap(.82f, lockedBounds); doubleTap(.5f, lockedBounds)
            delay(500)
            assertEquals(count, seeks().size)
            assertFalse(model.player.state.value.playing)
            device.executeShellCommand("mkdir -p /sdcard/Download/nfb-client-acceptance")
            device.executeShellCommand("screencap -p /sdcard/Download/nfb-client-acceptance/player-touch-locked.png")
            device.pressBack()
            assertTrue(device.wait(Until.gone(By.desc("解除触控锁定")), 5_000))
            assertNotNull(model.state.value.selected)
            button("返回文件").click()
            withTimeout(5_000) { model.state.first { it.selected == null } }
            activity.scenario.onActivity { assertEquals(oldOrientation, it.requestedOrientation) }
        } finally {
            main { model.disconnect() }
            store.remove(profile); source.close()
        }
    }
}
