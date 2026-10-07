package io.github.kkwans.nasfilebrowser

import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import io.github.kkwans.nasfilebrowser.app.ClientModel
import io.github.kkwans.nasfilebrowser.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

/** Owned-emulator queue controls. Does not assert decoded output or actual sound. */
@RunWith(AndroidJUnit4::class)
class MediaQueueUiTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device get() = UiDevice.getInstance(instrumentation)
    private suspend fun main(action: () -> Unit) = withContext(Dispatchers.Main) { action() }
    private fun model(): ClientModel {
        lateinit var result: ClientModel
        activity.scenario.onActivity { result = ViewModelProvider(it)[ClientModel::class.java] }
        return result
    }
    private fun store() = ProfileStore(ClientDatabase.get(instrumentation.targetContext), CredentialVault(instrumentation.targetContext))
    private fun capture(name: String) {
        device.executeShellCommand("mkdir -p /sdcard/Download/nfb-client-acceptance")
        device.executeShellCommand("screencap -p /sdcard/Download/nfb-client-acceptance/$name.png")
        // Use this instrumentation's UiAutomation connection; a concurrent shell
        // uiautomator dump would steal the connection and invalidate the test.
        device.dumpWindowHierarchy(File(instrumentation.targetContext.getExternalFilesDir(null), "$name.xml"))
    }

    /** Validates paused queue binding and late metadata isolation, not decoded output. */
    @Test fun videoQueueControlsKeepSnapshotAndRejectSupersededOpen(): Unit = runBlocking {
        val media = instrumentation.context.assets.open("media/fixture.mkv").use { it.readBytes() }
        val source = NativePlaybackTest.Fixture(media, videos = listOf("one.mkv", "two.mkv"))
        val model = model(); val store = store(); val profile = store.save(ServerProfile(name = "Video queue fixture", address = source.url))
        try {
            main { model.selectProfile(profile); model.connectDraft(profile.name, source.url, BackendKind.NAS, "fixture", "fixture-only", "direct") }
            withTimeout(10_000) { model.state.first { it.connected && !it.busy } }
            main { model.foreground(false); model.open(model.state.value.files.first()) }
            withTimeout(10_000) { model.state.first { it.selected?.name == "one.mkv" && !it.busy } }
            val snapshot = model.state.value.mediaQueue!!.snapshotId
            assertFalse(model.player.state.value.playing)
            assertEquals(0, model.state.value.mediaQueue!!.index)
            assertTrue(device.wait(Until.hasObject(By.desc("上一个视频")), 5000))
            capture("video-queue-first")
            assertNotNull(device.wait(Until.findObject(By.desc("上一个视频").enabled(false)), 5000))
            source.stallNextRead.set(true)
            device.findObject(By.desc("下一个视频")).click()
            assertTrue(withContext(Dispatchers.IO) { source.resumeRead.await(5, TimeUnit.SECONDS) })
            (device.wait(Until.findObject(By.desc("上一个视频").enabled(true)), 5000) ?: error("Previous queue action has not rendered")).click()
            source.releaseRead.countDown()
            withTimeout(10_000) { model.state.first { it.selected?.name == "one.mkv" && !it.busy } }
            delay(300)
            assertEquals(snapshot, model.state.value.mediaQueue!!.snapshotId)
            assertEquals("one.mkv", model.state.value.selected?.name)
            device.findObject(By.desc("播放列表")).click()
            assertTrue(device.wait(Until.hasObject(By.textContains("two.mkv")), 5000))
            device.findObject(By.textContains("two.mkv")).click()
            withTimeout(10_000) { model.state.first { it.selected?.name == "two.mkv" && !it.busy } }
            assertNotNull(device.wait(Until.findObject(By.desc("下一个视频").enabled(false)), 5000))
            assertFalse(model.player.state.value.playing)
        } finally {
            source.releaseRead.countDown()
            main { model.leavePlayer(); model.foreground(true); model.disconnect() }; store.remove(profile); source.close()
        }
    }
}
