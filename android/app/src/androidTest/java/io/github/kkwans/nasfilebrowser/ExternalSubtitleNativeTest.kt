package io.github.kkwans.nasfilebrowser

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
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

/** Real engine ES events and authenticated source reads; subtitle-surface pixels; synchronization/ASS/HDR remain separate acceptance. */
@RunWith(AndroidJUnit4::class)
class ExternalSubtitleNativeTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)
    private suspend fun subtitleWhitePixels(): Int {
        fun find(view: View): SurfaceView? {
            if (view is SurfaceView && runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull() == "surface_subtitles") return view
            if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
            return null
        }
        var surface: SurfaceView? = null
        activity.scenario.onActivity { surface = find(it.window.decorView) }
        val view = surface ?: return 0
        if (!view.holder.surface.isValid) return 0
        val bitmap = Bitmap.createBitmap(384, 216, Bitmap.Config.ARGB_8888)
        val status = CompletableDeferred<Int>()
        var requested = false
        try {
            withContext(Dispatchers.Main) { PixelCopy.request(view, bitmap, { status.complete(it) }, Handler(Looper.getMainLooper())); requested = true }
            if (status.await() != PixelCopy.SUCCESS) return 0
            val pixels = IntArray(384 * 216); bitmap.getPixels(pixels, 0, 384, 0, 0, 384, 216)
            return pixels.count { Color.alpha(it) > 150 && Color.red(it) > 180 && Color.green(it) > 180 && Color.blue(it) > 180 }
        } finally { if (!requested || status.isCompleted) bitmap.recycle() else status.invokeOnCompletion { bitmap.recycle() } }
    }
    @Test fun nasSubtitlePickerLoadsAuthenticatedExternalNativeTrack(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val media = instrumentation.context.assets.open("media/fixture.mkv").use { it.readBytes() }
        val caption = "1\n00:00:00,000 --> 00:00:12,000\nEXTERNAL NATIVE SUBTITLE\n\n".toByteArray()
        val source = NativePlaybackTest.Fixture(media, mapOf("external.srt" to caption))
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
            // A paused input may only parse the external demuxer after resuming.
            withContext(Dispatchers.Main) { model.player.toggle() }
            withTimeout(10_000) { model.player.state.first { it.subtitles.count { track -> track.id >= 0 } > count && it.selectedSubtitle >= 0 && it.selectedSubtitle !in previousIds } }
            assertEquals("external.srt", model.player.state.value.subtitles.single { it.id == model.player.state.value.selectedSubtitle }.title)
            withTimeout(6000) {
                while (subtitleWhitePixels() <= 20) delay(100)
            }
            (device.wait(Until.findObject(By.desc("关闭播放设置")), 3000) ?: error("Subtitle panel close missing")).click()
            assertTrue(device.wait(Until.gone(By.desc("暂停播放")), 5000))
            device.executeShellCommand("mkdir -p /sdcard/Download/nfb-client-acceptance")
            device.executeShellCommand("screencap -p /sdcard/Download/nfb-client-acceptance/external-subtitle.png")
            assertTrue("Subtitle bytes must pass the authenticated broker", source.subtitleRequests.get() > 0)
            assertEquals("No unrelated backend operation", 0, source.unexpected.get())
            withContext(Dispatchers.Main) { model.leavePlayer() }
            assertNull(model.state.value.selected)
        } finally { withContext(Dispatchers.Main) { model.disconnect() }; store.remove(profile); source.close() }
    }
}
