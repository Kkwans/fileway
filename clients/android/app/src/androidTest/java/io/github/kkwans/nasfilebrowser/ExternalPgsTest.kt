package io.github.kkwans.nasfilebrowser

import android.view.ViewGroup
import androidx.media3.common.C
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.kkwans.nasfilebrowser.player.ExternalPgs
import io.github.kkwans.nasfilebrowser.player.MediaSubtitleLayer
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class ExternalPgsTest {
    @Test fun longFilmSupKeepsAllCaptionAndClearEventsWithoutExpandingEveryBitmap(): Unit = runBlocking {
        // 240 authored 512x80 captions would occupy 37.5 MiB if all decoded
        // together, although the SUP reuses one small RLE-compressed object.
        val values = ExternalPgs.parse(longSup())
        assertEquals(480, values.size)
        assertTrue("Shared RLE objects must stay compressed", ExternalPgs.encodedBytes(values) < 64 * 1024)
        for (index in listOf(0, 200, 478, 0)) {
            val caption = ExternalPgs.decode(values[index])
            val image = caption.cues.single().bitmap!!
            try {
                assertEquals((2L + index) * 1_000_000, caption.startTimeUs)
                assertEquals(512, image.width); assertEquals(80, image.height)
                assertEquals(0, android.graphics.Color.alpha(image.getPixel(0, 0)))
                assertTrue(android.graphics.Color.red(image.getPixel(300, 0)) > 180)
            } finally { image.recycle() }
            assertTrue("The clear event must replace a prior caption", ExternalPgs.decode(values[index + 1]).cues.isEmpty())
        }
    }
    @Test fun realPgsDecoderPreservesSupTimingCachedObjectsCropAndClear(): Unit = runBlocking {
        val cues = ExternalPgs.parse(ownedSup()).map(ExternalPgs::decode)
        assertEquals(listOf(2_000_000L, 3_000_000L, 4_000_000L), cues.map { it.startTimeUs })
        assertTrue(cues.all { it.durationUs == C.TIME_UNSET })
        val first = cues[0].cues.single()
        assertEquals(64, first.bitmap!!.width)
        assertEquals(12, first.bitmap!!.height)
        assertEquals(64f / 384, first.position, .0001f)
        assertTrue(android.graphics.Color.red(first.bitmap!!.getPixel(0, 0)) > 180)
        val cropped = cues[1].cues.single()
        assertEquals(32, cropped.bitmap!!.width)
        assertEquals(6, cropped.bitmap!!.height)
        assertEquals(96f / 384, cropped.position, .0001f)
        assertTrue("Clear PCS must replace the preceding bitmap", cues[2].cues.isEmpty())
    }
    @Test fun truncatedDisplaySetFailsBeforeASelectedTrackCanBeAttached(): Unit = runBlocking {
        for (bytes in listOf(ownedSup().dropLast(1).toByteArray(), byteArrayOf(80, 71))) {
            try { ExternalPgs.parse(bytes); fail("Corrupt SUP accepted") }
            catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun compressedTimelineSeeksClearsAndAppliesDelayWithoutLosingEarlierCaptions(): Unit = runBlocking {
        val values = ExternalPgs.parse(longSup())
        val events = Channel<Boolean>(Channel.UNLIMITED)
        val ready = CompletableDeferred<Unit>()
        val eventCount = AtomicInteger()
        val lastVisible = AtomicReference<Boolean?>()
        val rendererFailure = AtomicReference<String?>()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        var ownedLayer: MediaSubtitleLayer? = null
        suspend fun stage(label: String, action: suspend () -> Unit) {
            try { withTimeout(5000) { action() } }
            catch (error: Throwable) {
                if (error is CancellationException && error !is TimeoutCancellationException) throw error
                throw AssertionError("Compressed SUP stage=$label, attached=${ownedLayer?.isAttachedToWindow}, " +
                    "events=${eventCount.get()}, lastVisible=${lastVisible.get()}, rendererFailure=${rendererFailure.get()}", error)
            }
        }
        try {
            scenario.onActivity { activity ->
                val root = activity.window.decorView as ViewGroup
                val layer = MediaSubtitleLayer(activity).also { ownedLayer = it }
                layer.showText = { cues ->
                    val visible = cues.isNotEmpty()
                    eventCount.incrementAndGet(); lastVisible.set(visible); events.trySend(visible)
                }
                layer.failed = {
                    rendererFailure.set("Compressed SUP rendering failed")
                    val error = AssertionError(rendererFailure.get())
                    ready.completeExceptionally(error); events.close(error)
                }
                root.addView(layer, ViewGroup.LayoutParams(384, 216))
                assertTrue("Owned subtitle layer must be attached before View.post callbacks", layer.isAttachedToWindow)
                layer.externalPgs("external:owned", values) { ready.complete(Unit) }
            }
            val layer = requireNotNull(ownedLayer)
            suspend fun expect(label: String, visible: Boolean) = stage("$label expectedVisible=$visible") {
                while (events.receive() != visible) { }
            }
            suspend fun at(position: Long, visible: Boolean, delay: Long = 0) {
                while (events.tryReceive().isSuccess) { }
                withContext(Dispatchers.Main) { layer.delayMs = delay; layer.requestFrame(position) }
                expect("frame positionMs=$position delayMs=$delay", visible)
            }
            stage("externalPgs ready callback") { ready.await() }
            withContext(Dispatchers.Main) { layer.select(null, "external:owned", 2500) }
            expect("initial selection positionMs=2500", true)
            at(480_500, true)
            at(480_500, false, delay = 1000)
            at(480_500, true)
            at(481_500, false)
            at(2500, true)
            withContext(Dispatchers.Main) { layer.select(null, null, 2500) }
            expect("explicit subtitle off", false)
        } finally {
            try {
                withContext(NonCancellable + Dispatchers.Main) {
                    ownedLayer?.let { layer -> layer.close(); (layer.parent as? ViewGroup)?.removeView(layer) }
                }
            } finally { try { scenario.close() } finally { events.close() } }
        }
    }
    @Test fun canceledLongSupParseDoesNotFinishAndAttachATrack(): Unit = runBlocking {
        val input = longSup()
        var canceled = false
        val job = launch {
            currentCoroutineContext().cancel()
            try { ExternalPgs.parse(input); fail("Canceled SUP parse completed") }
            catch (_: CancellationException) { canceled = true }
        }
        job.join()
        assertTrue(canceled)
    }
    companion object {
        fun longSup(): ByteArray {
            val output = ByteArrayOutputStream()
            fun segment(at: Int, type: Int, payload: ByteArray) {
                output.write(byteArrayOf(80, 71))
                val pts = at * 90_000
                for (shift in listOf(24, 16, 8, 0)) output.write(pts ushr shift)
                output.write(ByteArray(4)); output.write(type)
                output.write(payload.size ushr 8); output.write(payload.size); output.write(payload)
            }
            val header = byteArrayOf(7, -128, 4, 56, 0, 0, 1, 0, 0, 0, 1)
            val pixels = ByteArrayOutputStream().apply {
                repeat(80) { write(byteArrayOf(0, 65, 0, 0, -63, 0, 1, 0, 0)) }
            }.toByteArray()
            val length = pixels.size + 4
            repeat(240) { index ->
                val at = 2 + index * 2
                segment(at, 0x16, header.copyOf().apply { this[7] = if (index == 0) -128 else 0 } + byteArrayOf(0, 1, 0, 0, 0, 64, 3, -124))
                if (index == 0) {
                    segment(at, 0x14, byteArrayOf(0, 0, 1, -21, -128, -128, -1))
                    segment(at, 0x15, byteArrayOf(0, 1, 0, -64, (length ushr 16).toByte(), (length ushr 8).toByte(), length.toByte(), 2, 0, 0, 80) + pixels)
                }
                segment(at, 0x80, byteArrayOf())
                segment(at + 1, 0x16, header.copyOf().apply { this[10] = 0 })
                segment(at + 1, 0x80, byteArrayOf())
            }
            return output.toByteArray()
        }
        /** Authored white bitmap at 2s, cached/cropped reposition at 3s, clear at 4s. */
        fun ownedSup(): ByteArray {
            val output = ByteArrayOutputStream()
            fun segment(at: Int, type: Int, payload: ByteArray) {
                output.write(byteArrayOf(80, 71))
                val pts = at * 90_000
                for (shift in listOf(24, 16, 8, 0)) output.write(pts ushr shift)
                output.write(ByteArray(4)); output.write(type)
                output.write(payload.size ushr 8); output.write(payload.size); output.write(payload)
            }
            val header = byteArrayOf(1, -128, 0, -40, 0, 0, 1, -128, 0, 0, 1)
            segment(2, 0x16, header + byteArrayOf(0, 1, 0, 0, 0, 64, 0, -116))
            segment(2, 0x14, byteArrayOf(0, 0, 1, -21, -128, -128, -1))
            segment(2, 0x15, byteArrayOf(0, 1, 0, -64, 0, 3, 4, 0, 64, 0, 12) + ByteArray(64 * 12) { 1 })
            segment(2, 0x80, byteArrayOf())
            segment(3, 0x16, header.copyOf().apply { this[7] = 0 } +
                byteArrayOf(0, 1, 0, -128, 0, 96, 0, -116, 0, 0, 0, 0, 0, 32, 0, 6))
            segment(3, 0x80, byteArrayOf())
            segment(4, 0x16, header.copyOf().apply { this[7] = 0; this[10] = 0 })
            segment(4, 0x80, byteArrayOf())
            return output.toByteArray()
        }
    }
}
