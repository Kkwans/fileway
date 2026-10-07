package io.github.kkwans.nasfilebrowser

import android.content.pm.ActivityInfo
import android.graphics.Rect
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import io.github.kkwans.nasfilebrowser.app.ClientModel
import io.github.kkwans.nasfilebrowser.data.*
import io.github.kkwans.nasfilebrowser.player.PlaybackTraceAction
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Gesture routing uses a real native session; this is not first-frame or audio acceptance. */
@RunWith(AndroidJUnit4::class)
class PlayerGestureControlsTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)

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
