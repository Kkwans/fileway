package io.github.kkwans.nasfilebrowser

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import io.github.kkwans.nasfilebrowser.core.NativeTransport
import io.github.kkwans.nasfilebrowser.data.NasSession
import io.github.kkwans.nasfilebrowser.data.ServerProfile
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class MpvSubtitleTest {
    @get:Rule val activity = ActivityScenarioRule(EngineProbeActivity::class.java)
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private suspend fun <T> main(block: () -> T): T = withContext(Dispatchers.Main) { block() }

    private suspend fun movie(asset: String, start: Long = 0, initialSid: Int? = null, verify: suspend (MpvProbe) -> Unit) {
        val media = instrumentation.context.assets.open("media/$asset").use { it.readBytes() }
        lateinit var probe: MpvProbe
        activity.scenario.onActivity { host ->
            probe = MpvProbe(host)
            host.viewport.addView(probe.view, FrameLayout.LayoutParams(-1, host.resources.displayMetrics.widthPixels * 9 / 16, Gravity.CENTER))
            if (start > 0) check(probe.player.setOptionString("start", (start / 1000.0).toString()) >= 0)
            initialSid?.let { probe.player.setPropertyInt("sid", it) }
        }
        try {
            NativePlaybackTest.Fixture(media).use { source ->
                val session = NasSession.login(ServerProfile(name = "Owned mpv subtitle probe", address = source.url), "fixture", "fixture-only")
                var lease: String? = null
                try {
                    val url = session.lease("/fixture.mkv", "/fixture.mkv"); lease = url
                    main { probe.open(url) }
                    withTimeout(25_000) {
                        while (!main { probe.playing && probe.position > start && probe.duration > 0 }) delay(50)
                    }
                    main {
                        assertEquals("truehd", probe.player.getPropertyString("audio-codec-name"))
                        val count = requireNotNull(probe.player.getPropertyInt("track-list/count"))
                        val types = (0 until count).map { probe.player.getPropertyString("track-list/$it/type") }
                        assertEquals(4, types.count { it == "audio" })
                        assertEquals(8, types.count { it == "sub" })
                    }
                    verify(probe)
                    assertEquals(0, source.unexpected.get())
                } finally {
                    withContext(NonCancellable) {
                        main { probe.pause() }
                        try { lease?.let { NativeTransport.call(JSONObject().put("op", "revoke").put("url", it)) } }
                        finally { session.close() }
                    }
                }
            }
        } catch (failure: Throwable) { capture("failure-$asset-$start"); throw failure }
        finally {
            main {
                instrumentation.addResults(Bundle().apply { putString("filewayMpvSubtitleProbe", probe.observations()
                    .put("asset", asset).put("startMs", start).put("hdrAndPhysicalAudio", "UNVERIFIED").toString()) })
                probe.close()
            }
        }
    }
    private fun capture(name: String) {
        UiDevice.getInstance(instrumentation).apply {
            executeShellCommand("mkdir -p /sdcard/Download/nfb-client-acceptance")
            executeShellCommand("screencap -p /sdcard/Download/nfb-client-acceptance/mpv-$name.png")
        }
    }
    private suspend fun pixels(probe: MpvProbe): IntArray? {
        val bounds = main { Rect().takeIf { probe.view.getGlobalVisibleRect(it) && !it.isEmpty } } ?: return null
        val shot = instrumentation.uiAutomation.takeScreenshot() ?: return null
        val frame = shot.copy(Bitmap.Config.ARGB_8888, false)
        return try {
            if (bounds.right > frame.width || bounds.bottom > frame.height) return null
            IntArray(320 * 180) { i -> frame.getPixel(bounds.left + i % 320 * bounds.width() / 320, bounds.top + i / 320 * bounds.height() / 180) }
        } finally { frame.recycle(); shot.recycle() }
    }
    private suspend fun at(probe: MpvProbe, ms: Long, predicate: (IntArray) -> Boolean): IntArray {
        main { probe.pause(); probe.seek(ms) }
        var accepted: IntArray? = null
        withTimeout(20_000) {
            while (accepted == null) {
                if (main { abs(probe.position - ms) < 500 && probe.player.getPropertyBoolean("seeking") != true }) {
                    pixels(probe)?.let { if (predicate(it)) accepted = it }
                }
                if (accepted == null) delay(50)
            }
        }
        return requireNotNull(accepted)
    }
    private suspend fun select(probe: MpvProbe, title: String) = main {
        val count = requireNotNull(probe.player.getPropertyInt("track-list/count"))
        val index = (0 until count).single { probe.player.getPropertyString("track-list/$it/type") == "sub" &&
            probe.player.getPropertyString("track-list/$it/title")?.contains(title) == true }
        probe.player.setPropertyInt("sid", requireNotNull(probe.player.getPropertyInt("track-list/$index/id")))
    }
    private fun red(p: Int) = Color.red(p) > 170 && Color.green(p) < 100 && Color.blue(p) < 100
    private fun blue(p: Int) = Color.blue(p) > 170 && Color.green(p) < 100 && Color.red(p) < 100

    @Test fun fontDrawingSixPgsTextSeekAndDisableReachTheCompositor(): Unit = runBlocking {
        movie("subtitle-fixture.mkv") { probe ->
            select(probe, "NFB ASS Attachment")
            val frame = at(probe, 2000) { it.count(::red) > 30 && it.count(::blue) > 150 }
            var runs = 0; var active = false
            for (x in 0 until 140) {
                val column = (0 until 180).count { y -> red(frame[y * 320 + x]) } > 3
                if (column && !active) runs++
                active = column
            }
            assertEquals(2, runs); capture("font-drawing")
            at(probe, 6000) { it.count(::red) > 30 && it.count(::blue) > 150 }
            at(probe, 0) { it.count(::red) < 10 && it.count(::blue) < 10 }
            val colors = listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW, Color.CYAN, Color.MAGENTA)
            fun count(frame: IntArray, color: Int) = frame.count { p -> abs(Color.red(p) - Color.red(color)) < 85 &&
                abs(Color.green(p) - Color.green(color)) < 85 && abs(Color.blue(p) - Color.blue(color)) < 85 }
            for ((index, color) in colors.withIndex()) {
                select(probe, "NFB PGS${index + 1}")
                at(probe, 2000) { count(it, color) > 200 && colors.filter { other -> other != color }.all { other -> count(it, other) < 20 } }
                capture("pgs-${index + 1}")
            }
            select(probe, "NFB Text")
            at(probe, 6000) { it.count { p -> Color.red(p) > 200 && Color.green(p) > 200 && Color.blue(p) > 200 } > 60 }
            capture("text-mid-cue")
            main { probe.player.setPropertyString("sid", "no") }
            withTimeout(5000) { while (pixels(probe)?.all { maxOf(Color.red(it), Color.green(it), Color.blue(it)) < 100 } != true) delay(50) }
            capture("off")
        }
    }
    @Test fun assSameBoundsColourMotionAndFadeAreRendered(): Unit = runBlocking {
        movie("ass-animation-fixture.mkv") { probe ->
            select(probe, "NFB ASS Attachment")
            fun yellowCenter(frame: IntArray): Double {
                val xs = frame.indices.filter { Color.red(frame[it]) > 170 && Color.green(frame[it]) > 170 && Color.blue(frame[it]) < 100 }
                assertTrue(xs.size > 30)
                return xs.map { (it % 320).toDouble() }.average()
            }
            val early = at(probe, 1000) { it.count(::red) > 200 }
            val later = at(probe, 3500) { it.count(::blue) > 200 && it.count(::red) < 20 }
            assertTrue(yellowCenter(later) - yellowCenter(early) > 25); capture("animation-motion")
            val roi = (30 until 50).flatMap { y -> (200 until 240).map { x -> y * 320 + x } }
            at(probe, 8000) { frame -> roi.count { Color.red(frame[it]) > 220 && Color.green(frame[it]) > 220 && Color.blue(frame[it]) > 220 } > 300 }
            at(probe, 9900) { frame -> roi.count { Color.red(frame[it]) in 50..170 && Color.green(frame[it]) in 50..170 && Color.blue(frame[it]) in 50..170 } > 300 }
            capture("animation-fade")
        }
    }
    @Test fun freshOpenInsideTextCueRestoresWithoutEarlierPlayback(): Unit = runBlocking {
        // Owned fixture contract: text (sid1), ASS, then six PGS streams.
        movie("subtitle-fixture.mkv", start = 6000, initialSid = 1) { probe ->
            main { assertEquals(1, probe.player.getPropertyInt("sid")); probe.pause() }
            withTimeout(10_000) { while (pixels(probe)?.count { Color.red(it) > 200 && Color.green(it) > 200 && Color.blue(it) > 200 }?.let { it > 60 } != true) delay(50) }
            capture("fresh-text-mid-cue")
        }
    }
    @Test fun freshOpenInsideAssCueRestoresFontAndDrawing(): Unit = runBlocking {
        movie("subtitle-fixture.mkv", start = 6000, initialSid = 2) { probe ->
            main { assertEquals(2, probe.player.getPropertyInt("sid")); probe.pause() }
            withTimeout(10_000) {
                while (pixels(probe)?.let { it.count(::red) > 30 && it.count(::blue) > 150 } != true) delay(50)
            }
            capture("fresh-ass-mid-cue")
        }
    }
}
