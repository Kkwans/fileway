package io.github.kkwans.nasfilebrowser

import android.graphics.Canvas
import android.graphics.Bitmap
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import androidx.media3.ui.SubtitleView
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import io.github.kkwans.nasfilebrowser.app.ClientModel
import io.github.kkwans.nasfilebrowser.app.ResourceRef
import io.github.kkwans.nasfilebrowser.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Authenticated source reads and actual subtitle view pixels; ASS/HDR have separate gates. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class ExternalSubtitleNativeTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)
    private suspend fun subtitleWhitePixels(): Int {
        var white = 0
        activity.scenario.onActivity { owner ->
            fun find(view: View): SubtitleView? {
                if (view is SubtitleView) return view
                if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
                return null
            }
            val view = find(owner.window.decorView) ?: return@onActivity
            if (view.width <= 0 || view.height <= 0) return@onActivity
            val bitmap = Bitmap.createBitmap(384, 216, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(bitmap)
                canvas.scale(384f / view.width, 216f / view.height)
                view.draw(canvas)
                val pixels = IntArray(384 * 216)
                bitmap.getPixels(pixels, 0, 384, 0, 0, 384, 216)
                white = pixels.count { Color.alpha(it) > 150 && Color.red(it) > 180 && Color.green(it) > 180 && Color.blue(it) > 180 }
            } finally { bitmap.recycle() }
        }
        return white
    }
    @Test fun supUsesAuthenticatedLeaseAndAdjustsPausedPixelsWithoutReopening(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val media = instrumentation.context.assets.open("media/fixture.mkv").use { it.readBytes() }
        val caption = ExternalPgsTest.ownedSup()
        val source = NativePlaybackTest.Fixture(media, mapOf("external.sup" to caption))
        val store = ProfileStore(ClientDatabase.get(instrumentation.targetContext), CredentialVault(instrumentation.targetContext))
        val profile = store.save(ServerProfile(name = "SUP subtitle fixture", address = source.url))
        lateinit var model: ClientModel
        activity.scenario.onActivity { model = ViewModelProvider(it)[ClientModel::class.java] }
        suspend fun main(action: suspend () -> Unit) = withContext(Dispatchers.Main) { action() }
        suspend fun white(expected: Boolean) = withTimeout(6000) { while ((subtitleWhitePixels() > 20) != expected) delay(50) }
        try {
            main { model.selectProfile(profile); model.connectDraft(profile.name, source.url, BackendKind.NAS, "fixture", "fixture-only", "direct") }
            withTimeout(10_000) { model.state.first { it.connected && !it.busy } }
            main { model.open(ResourceRef("/fixture.mkv", "/fixture.mkv", "Owned media.mkv", false, "video", media.size.toLong())) }
            withTimeout(20_000) { model.player.state.first { it.firstFrameRendered && it.seekable } }
            main { model.pausePlayback(); model.player.subtitle(-1); model.player.seek(2500) }
            withTimeout(6000) { model.player.state.first { !it.playing && kotlin.math.abs(it.positionMs - 2500) < 250 } }
            val generation = model.player.state.value.mediaGeneration
            main { model.addExternalSubtitle(ResourceRef("/external.sup", "/external.sup", "external.sup", false, "", caption.size.toLong())) }
            withTimeout(10_000) { model.player.state.first { !it.subtitleLoading && it.subtitles.any { track -> track.id == it.selectedSubtitle && track.title == "external.sup" } } }
            white(true)
            main { model.player.subtitleDelay(1000) }; white(false)
            main { model.player.subtitleDelay(0) }; white(true)
            main { model.player.seek(4500) }
            withTimeout(6000) { model.player.state.first { kotlin.math.abs(it.positionMs - 4500) < 250 } }
            white(false)
            assertFalse(model.player.state.value.playing)
            assertEquals(generation, model.player.state.value.mediaGeneration)
            assertTrue(source.subtitleRequests.get() > 0)
            assertEquals(0, source.unexpected.get())
        } finally { main { model.disconnect() }; store.remove(profile); source.close() }
    }

    @Test fun nasSubtitlePickerLoadsAuthenticatedExternalNativeTrack(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val media = instrumentation.context.assets.open("media/fixture.mkv").use { it.readBytes() }
        val caption = "1\n00:00:00,000 --> 00:00:12,000\nEXTERNAL NATIVE SUBTITLE\n\n".toByteArray()
        val source = NativePlaybackTest.Fixture(media, mapOf("external.srt" to caption, "delayed.srt" to caption))
        val store = ProfileStore(ClientDatabase.get(instrumentation.targetContext), CredentialVault(instrumentation.targetContext))
        val profile = store.save(ServerProfile(name = "External subtitle acceptance", address = source.url))
        lateinit var model: ClientModel
        activity.scenario.onActivity { model = ViewModelProvider(it)[ClientModel::class.java] }
        try {
            withContext(Dispatchers.Main) { model.selectProfile(profile); model.connectDraft(profile.name, source.url, BackendKind.NAS, "fixture", "fixture-only", "direct") }
            withTimeout(10_000) { model.state.first { it.connected && !it.busy } }
            withContext(Dispatchers.Main) { model.open(ResourceRef("/fixture.mkv", "/fixture.mkv", "Owned media.mkv", false, "video", media.size.toLong())) }
            try { withTimeout(20_000) { model.player.state.first { it.playing && it.durationMs > 0 && it.positionMs > 0 } } }
            catch (timeout: TimeoutCancellationException) {
                val state = model.player.state.value
                throw AssertionError("Native startup timeout: phase=${state.phase}, playing=${state.playing}, position=${state.positionMs}, duration=${state.durationMs}, buffer=${state.buffering}, error=${state.error}, clientBusy=${model.state.value.busy}, clientError=${model.state.value.error}, rawReads=${source.rawRequests.get()}", timeout)
            }
            withContext(Dispatchers.Main) { model.pausePlayback(); model.player.subtitle(-1) }
            val previousIds = model.player.state.value.subtitles.filter { it.id >= 0 }.map { it.id }.toSet()
            val count = previousIds.size
            (device.wait(Until.findObject(By.desc("选择字幕")), 5000) ?: error("Subtitle action missing")).click()
            (device.wait(Until.findObject(By.desc("选择外挂字幕")), 5000) ?: error("External subtitle action missing")).click()
            (device.wait(Until.findObject(By.text("external.srt")), 5000) ?: error("NAS subtitle not listed")).click()
            // External parsing must work while paused without reopening audio/video.
            withTimeout(10_000) { model.player.state.first { it.subtitles.count { track -> track.id >= 0 } > count && it.selectedSubtitle >= 0 && it.selectedSubtitle !in previousIds } }
            assertEquals("external.srt", model.player.state.value.subtitles.single { it.id == model.player.state.value.selectedSubtitle }.title)
            withTimeout(6000) {
                while (subtitleWhitePixels() <= 20) delay(100)
            }
            (device.wait(Until.findObject(By.desc("关闭播放设置")), 3000) ?: error("Subtitle panel close missing")).click()
            assertFalse("Adding an external subtitle must preserve pause", model.player.state.value.playing)
            device.executeShellCommand("mkdir -p /sdcard/Download/nfb-client-acceptance")
            device.executeShellCommand("screencap -p /sdcard/Download/nfb-client-acceptance/external-subtitle.png")
            assertTrue("Subtitle bytes must pass the authenticated broker", source.subtitleRequests.get() > 0)
            assertEquals("No unrelated backend operation", 0, source.unexpected.get())
            withContext(Dispatchers.Main) { model.player.subtitle(-1) }
            withTimeout(5000) { model.player.state.first { it.selectedSubtitle == -1 } }
            withTimeout(5000) { while (subtitleWhitePixels() > 0) delay(50) }
            assertFalse(model.player.state.value.playing)
            // Hold a different uncached response until the user has explicitly
            // disabled subtitles. A late successful read must not undo that choice.
            source.stallNextSubtitle.set(true)
            withContext(Dispatchers.Main) {
                model.addExternalSubtitle(ResourceRef("/delayed.srt", "/delayed.srt", "delayed.srt", false, "", caption.size.toLong()))
            }
            assertTrue("Delayed subtitle request reached the authenticated source", withContext(Dispatchers.IO) {
                source.subtitleRead.await(5, java.util.concurrent.TimeUnit.SECONDS)
            })
            withContext(Dispatchers.Main) { model.player.subtitle(-1) }
            assertFalse(model.player.state.value.subtitleLoading)
            source.releaseSubtitle.countDown()
            assertTrue(withContext(Dispatchers.IO) { source.subtitleReturned.await(5, java.util.concurrent.TimeUnit.SECONDS) })
            repeat(10) {
                delay(100)
                assertEquals("A canceled read must not reselect the external subtitle", -1, model.player.state.value.selectedSubtitle)
                assertFalse(model.player.state.value.playing)
                assertEquals(0, subtitleWhitePixels())
            }
            withContext(Dispatchers.Main) { model.leavePlayer() }
            assertNull(model.state.value.selected)
        } finally { withContext(Dispatchers.Main) { model.disconnect() }; store.remove(profile); source.close() }
    }
}
