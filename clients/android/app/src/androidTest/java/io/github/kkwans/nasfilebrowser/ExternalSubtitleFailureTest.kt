package io.github.kkwans.nasfilebrowser

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.view.ViewGroup
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.kkwans.nasfilebrowser.player.MediaSubtitleLayer
import io.github.kkwans.nasfilebrowser.player.NativePlayer
import io.github.kkwans.nasfilebrowser.player.PlaybackTraceAction
import io.github.kkwans.nasfilebrowser.player.PlayerViewport
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** Real file parsing and worker capacity failure; no profiles, accounts or database fixtures. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class ExternalSubtitleFailureTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private suspend fun <T> main(action: () -> T): T = withContext(Dispatchers.Main) { action() }
    private fun oversizedSrt(): String = buildString {
        fun time(ms: Int) = String.format(Locale.ROOT, "%02d:%02d:%02d,%03d", ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000)
        repeat(20_001) { index ->
            append(index + 1).append('\n').append(time(index * 1000)).append(" --> ").append(time(index * 1000 + 500))
                .append("\nOWNED CAPTION ").append(index).append("\n\n")
        }
    }
    private suspend fun await(native: NativePlayer, stage: String, predicate: suspend () -> Boolean) {
        try {
            withTimeout(20_000) {
                while (true) {
                    check(native.state.value.error == null) { "Owned native playback failed at $stage" }
                    if (predicate()) break
                    delay(50)
                }
            }
        } catch (error: TimeoutCancellationException) {
            val state = native.state.value
            throw AssertionError("External failure stage=$stage, position=${state.positionMs}, playing=${state.playing}, " +
                "subtitleLoading=${state.subtitleLoading}, selected=${state.selectedSubtitle}, operationError=${state.operationError}", error)
        }
    }
    private suspend fun whitePixels(viewport: PlayerViewport): Int = main {
        val view = viewport.text
        check(view.width > 0 && view.height > 0 && view.isAttachedToWindow)
        val bitmap = Bitmap.createBitmap(384, 216, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap); canvas.scale(384f / view.width, 216f / view.height); view.draw(canvas)
            val pixels = IntArray(384 * 216); bitmap.getPixels(pixels, 0, 384, 0, 0, 384, 216)
            pixels.count { Color.alpha(it) > 150 && Color.red(it) > 180 && Color.green(it) > 180 && Color.blue(it) > 180 }
        } finally { bitmap.recycle() }
    }

    @Test fun lateWorkerFailureAfterSubtitleOffIsIgnoredButCurrentFailureReportsAndValidFileRecovers(): Unit = runBlocking {
        val files = mutableListOf<File>()
        fun owned(extension: String) = File.createTempFile("owned-external-failure-", extension, context.cacheDir).also { files.add(it) }
        val unblock = CountDownLatch(1)
        var player: NativePlayer? = null
        var ownedViewport: PlayerViewport? = null
        try {
            val movie = owned(".mkv"); val large = owned(".srt"); val valid = owned(".srt")
            val captions = withContext(Dispatchers.Default) { oversizedSrt().toByteArray(Charsets.UTF_8) }
            assertTrue("Read must pass the 16 MiB source limit and fail the actual worker cue budget", captions.size in 1..16 * 1024 * 1024)
            withContext(Dispatchers.IO) {
                InstrumentationRegistry.getInstrumentation().context.assets.open("media/fixture.mkv").use { input -> movie.outputStream().use { input.copyTo(it) } }
                large.writeBytes(captions)
                valid.writeText("1\n00:00:00,000 --> 00:00:12,000\nOWNED VALID CAPTION\n\n")
            }
            activity.scenario.onActivity { owner ->
                val viewport = PlayerViewport(owner).also { ownedViewport = it }
                (owner.window.decorView as ViewGroup).addView(viewport, ViewGroup.LayoutParams(384, 216))
                player = NativePlayer(owner).also { it.attach(viewport); it.open(Uri.fromFile(movie).toString(), 2500, autoplay = false) }
            }
            val native = requireNotNull(player); val viewport = requireNotNull(ownedViewport)
            await(native, "attached first frame") { native.state.value.let { it.firstFrameRendered && it.seekable && !it.playing && abs(it.positionMs - 2500) <= 250 } }
            main { native.pause(); native.subtitle(-1) }
            await(native, "initial subtitles off") { native.state.value.selectedSubtitle == -1 && native.state.value.pendingSubtitle == null }
            assertEquals(0, whitePixels(viewport))
            val initial = native.state.value
            val layer = main { NativePlayer::class.java.getDeclaredField("layer").apply { isAccessible = true }.get(native) as MediaSubtitleLayer }
            val worker = MediaSubtitleLayer::class.java.getDeclaredField("worker").apply { isAccessible = true }.get(layer) as ExecutorService
            val jobField = NativePlayer::class.java.getDeclaredField("externalJob").apply { isAccessible = true }
            suspend fun load(file: File, name: String): Job = main {
                assertTrue(native.addSubtitle(Uri.fromFile(file).toString(), name))
                requireNotNull(jobField.get(native) as? Job)
            }
            suspend fun drainWorkerAndMain() {
                withContext(Dispatchers.IO) { worker.submit(Runnable { }).get(5, TimeUnit.SECONDS) }
                main { /* All failure/ready posts preceding the worker barrier have been delivered. */ }
            }
            val entered = CountDownLatch(1)
            worker.execute { entered.countDown(); unblock.await() }
            assertTrue("Owned worker blocker must have started", withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
            val old = load(large, "owned-too-many-cues.srt")
            withTimeout(20_000) { old.join() }
            assertTrue("Parser completed and queued the still-blocked capacity check", old.isCompleted && native.state.value.subtitleLoading)
            assertNull(native.state.value.operationError)
            main { native.subtitle(-1) }
            unblock.countDown()
            drainWorkerAndMain()
            val afterLateFailure = native.state.value
            assertNull("A real late worker exception must not report against the explicit off choice", afterLateFailure.operationError)
            assertFalse(afterLateFailure.subtitleLoading); assertEquals(-1, afterLateFailure.selectedSubtitle)
            assertFalse(afterLateFailure.playing); assertEquals(initial.mediaGeneration, afterLateFailure.mediaGeneration)
            assertTrue(abs(afterLateFailure.positionMs - initial.positionMs) <= 250)
            assertEquals(0, whitePixels(viewport))

            val current = load(large, "owned-current-too-many-cues.srt")
            withTimeout(20_000) { current.join() }
            drainWorkerAndMain()
            await(native, "current worker capacity failure") { !native.state.value.subtitleLoading && native.state.value.operationError != null }
            assertEquals(-1, native.state.value.selectedSubtitle)
            assertFalse(native.state.value.playing)

            val good = load(valid, "owned-valid.srt")
            withTimeout(20_000) { good.join() }
            await(native, "valid external file after capacity failure") { native.state.value.let { state ->
                !state.subtitleLoading && state.operationError == null && state.subtitles.any { it.id == state.selectedSubtitle && it.title == "owned-valid.srt" }
            } && whitePixels(viewport) > 20 }
            assertFalse(native.state.value.playing); assertEquals(initial.mediaGeneration, native.state.value.mediaGeneration)
            assertEquals(initial.selectedAudio, native.state.value.selectedAudio)
            assertEquals(1, native.diagnosticSnapshot().count { it.action == PlaybackTraceAction.MEDIA_SET })
        } finally {
            unblock.countDown()
            withContext(NonCancellable + Dispatchers.Main) {
                try { player?.release() } finally { ownedViewport?.let { (it.parent as? ViewGroup)?.removeView(it) } }
            }
            withContext(NonCancellable + Dispatchers.IO) { files.forEach { it.delete() } }
        }
    }
}
