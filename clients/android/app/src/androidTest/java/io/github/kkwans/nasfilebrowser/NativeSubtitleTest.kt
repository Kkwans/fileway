package io.github.kkwans.nasfilebrowser

import android.graphics.Canvas
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import io.github.kkwans.nasfilebrowser.app.ClientModel
import io.github.kkwans.nasfilebrowser.app.ResourceRef
import io.github.kkwans.nasfilebrowser.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.media3.common.MediaLibraryInfo
import androidx.media3.ui.SubtitleView
import io.github.kkwans.nasfilebrowser.player.MediaSubtitleLayer

/** Real native subtitle pixels from an owned MKV; metadata alone cannot pass. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class NativeSubtitleTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private var activeModel: ClientModel? = null
    private val diagnostic = InstrumentationRegistry.getArguments().getString("nfbSubtitleDiagnostic") == "true"

    private suspend fun pixels(): IntArray? {
        var frame: IntArray? = null
        activity.scenario.onActivity { owner ->
            val layers = mutableListOf<View>()
            fun find(view: View) {
                if (view is SubtitleView || view is MediaSubtitleLayer) layers.add(view)
                if (view is ViewGroup) for (index in 0 until view.childCount) find(view.getChildAt(index))
            }
            find(owner.window.decorView)
            if (layers.isEmpty() || layers.any { it.width <= 0 || it.height <= 0 }) return@onActivity
            val bitmap = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(bitmap)
                layers.forEach { layer ->
                    canvas.save()
                    canvas.scale(320f / layer.width, 180f / layer.height)
                    layer.draw(canvas)
                    canvas.restore()
                }
                frame = IntArray(320 * 180).also { bitmap.getPixels(it, 0, 320, 0, 0, 320, 180) }
            } finally { bitmap.recycle() }
        }
        return frame
    }

    private fun matches(pixel: Int, rgb: List<Int>): Boolean {
        fun component(actual: Int, expected: Int) = if (expected == 255) actual > 170 else actual < 100
        return Color.alpha(pixel) > 100 && component(Color.red(pixel), rgb[0]) &&
            component(Color.green(pixel), rgb[1]) && component(Color.blue(pixel), rgb[2])
    }
    private fun count(pixels: IntArray, rgb: List<Int>) = pixels.count { matches(it, rgb) }
    private fun composedPixels(): IntArray? {
        var bounds: Rect? = null
        activity.scenario.onActivity { owner ->
            fun find(view: View) {
                if (view is MediaSubtitleLayer) {
                    val visible = Rect()
                    if (view.getGlobalVisibleRect(visible) && !visible.isEmpty) bounds = visible
                }
                if (view is ViewGroup) for (index in 0 until view.childCount) find(view.getChildAt(index))
            }
            find(owner.window.decorView)
        }
        val rect = bounds ?: return null
        val captured = instrumentation.uiAutomation.takeScreenshot() ?: return null
        val screenshot = if (captured.config == Bitmap.Config.HARDWARE) {
            try { captured.copy(Bitmap.Config.ARGB_8888, false) ?: return null }
            finally { captured.recycle() }
        } else captured
        try {
            if (rect.left < 0 || rect.top < 0 || rect.right > screenshot.width || rect.bottom > screenshot.height) return null
            return IntArray(320 * 180) { index ->
                val x = rect.left + ((index % 320 + .5) * rect.width() / 320).toInt()
                val y = rect.top + ((index / 320 + .5) * rect.height() / 180).toInt()
                screenshot.getPixel(x, y)
            }
        } finally { screenshot.recycle() }
    }

    private suspend fun rendered(label: String, nativeCheck: ((IntArray) -> Boolean)? = null,
                                 check: (IntArray) -> Boolean): IntArray = withTimeout(10_000) {
        var attempts = 0
        while (true) {
            val frame = pixels()
            // Inspect actual subtitle layers and independently require the same
            // content in the system compositor, including video blending.
            val nativeMatches = frame != null && (nativeCheck ?: check)(frame)
            if (nativeMatches || diagnostic) {
                val composed = composedPixels()
                if (diagnostic && attempts++ % 5 == 0) {
                    fun roi(values: IntArray?): String {
                        if (values == null) return "missing"
                        val region = (30 until 50).flatMap { y -> (200 until 240).map { x -> values[y * 320 + x] } }
                        return "alpha=${region.map(Color::alpha).average()} rgb=${region.map(Color::red).average()},${region.map(Color::green).average()},${region.map(Color::blue).average()}"
                    }
                    android.util.Log.i("NfbSubtitleDiagnostic", "stage=$label position=${activeModel?.player?.state?.value?.positionMs} nativeMatches=$nativeMatches native=${roi(frame)} composed=${roi(composed)}")
                }
                if (nativeMatches && composed != null && check(composed)) return@withTimeout composed
            }
            delay(100)
        }
        @Suppress("UNREACHABLE_CODE") error(label)
    }
    private fun capture(name: String) {
        val variant = InstrumentationRegistry.getArguments().getString("nfbSubtitleVariant").orEmpty()
        require(variant.matches(Regex("[a-z0-9-]{0,32}")))
        device.executeShellCommand("mkdir -p /sdcard/Download/nfb-client-acceptance")
        device.executeShellCommand("screencap -p /sdcard/Download/nfb-client-acceptance/subtitle-$name-$variant.png")
    }

    private var checking = "native start"
    private suspend fun onMain(action: () -> Unit) = withContext(Dispatchers.Main) { action() }

    /** Every gate owns its fixture/session, so ASS failure cannot hide PGS results. */
    private suspend fun withFixture(label: String, asset: String = "subtitle-fixture.mkv",
                                    verify: suspend (ClientModel) -> Unit) {
        val media = instrumentation.context.assets.open("media/$asset").use { it.readBytes() }
        val source = NativePlaybackTest.Fixture(media)
        val store = ProfileStore(ClientDatabase.get(instrumentation.targetContext), CredentialVault(instrumentation.targetContext))
        val profile = store.save(ServerProfile(name = "Native subtitle fixture", address = source.url))
        lateinit var model: ClientModel
        activity.scenario.onActivity { model = ViewModelProvider(it)[ClientModel::class.java] }
        checking = "$label native start"
        try {
            android.util.Log.i("NfbSubtitleAcceptance", "Gate=$label Media3=${MediaLibraryInfo.VERSION}")
            onMain { model.selectProfile(profile); model.connectDraft(profile.name, source.url, BackendKind.NAS, "fixture", "fixture-only", "direct") }
            withTimeout(10_000) { model.state.first { it.connected && !it.busy } }
            onMain { model.open(ResourceRef("/fixture.mkv", "/fixture.mkv", "Owned subtitle fixture.mkv", false, "video", media.size.toLong())) }
            withTimeout(25_000) { model.player.state.first { it.playing && it.seekable && it.positionMs >= 2800 && it.width == 640 && it.height == 360 } }
            val state = model.player.state.value
            assertEquals(4, state.audio.count { it.id >= 0 })
            assertTrue("Container default TrueHD must be selected", state.audio.single { it.id == state.selectedAudio }.title.contains("NFB TrueHD"))
            assertEquals(8, state.subtitles.count { it.id >= 0 })
            android.util.Log.i("NfbSubtitleAcceptance", "Tracks=${state.subtitles.map { "${it.id}:${it.title}:${it.codec}" }}")
            checking = label
            activeModel = model
            verify(model)
            assertTrue(source.rawRequests.get() > 0)
            assertEquals("No HLS/transcode/other endpoint may be called", 0, source.unexpected.get())
        } catch (error: Throwable) {
            capture("failure-${label}")
            android.util.Log.e("NfbSubtitleAcceptance", "Failure during $checking; state=${model.player.state.value}; trace=${model.player.diagnosticSnapshot()}")
            throw AssertionError("Native subtitle failure during $checking", error)
        } finally { activeModel = null; onMain { model.disconnect() }; store.remove(profile); source.close() }
    }

    private suspend fun select(model: ClientModel, marker: String) {
        val track = model.player.state.value.subtitles.single { it.id >= 0 && it.title.contains(marker) }
        onMain { model.player.subtitle(track.id) }
        withTimeout(5000) { model.player.state.first { it.selectedSubtitle == track.id } }
        onMain { model.player.seek(0) }
        withTimeout(5000) { model.player.state.first { it.selectedSubtitle == track.id && it.positionMs in 0..1500 && it.phase != "正在跳转" } }
    }

    private suspend fun textPixels() = rendered(checking) { frame ->
        frame.count { Color.alpha(it) > 100 && Color.red(it) > 200 && Color.green(it) > 200 && Color.blue(it) > 200 } > 60
    }

    private val fadeRoi = (30 until 50).flatMap { y -> (200 until 240).map { x -> y * 320 + x } }
    private suspend fun fadedPixels() = rendered(checking,
        nativeCheck = { frame -> fadeRoi.count { Color.alpha(frame[it]) > 20 } > 300 }) { frame ->
        fadeRoi.count { index ->
            val pixel = frame[index]
            Color.red(pixel) in 50..170 && Color.green(pixel) in 50..170 && Color.blue(pixel) in 50..170
        } > 300
    }

    /** A missed short red interval must not prevent an independent fade gate. */
    @Test fun actualAssOpaqueRegionFadesInTheComposedViewport(): Unit = runBlocking {
        withFixture("fade", "ass-animation-fixture.mkv") { model ->
            select(model, "NFB ASS Attachment")
            checking = "ASS opaque region before fade"
            rendered(checking) { frame -> fadeRoi.count { index ->
                val pixel = frame[index]
                Color.alpha(pixel) > 200 && Color.red(pixel) > 220 && Color.green(pixel) > 220 && Color.blue(pixel) > 220
            } > 300 }
            assertTrue("Opaque baseline must precede fade-out", model.player.state.value.positionMs < 9000)
            capture("fade-opaque")
            withTimeout(10_000) { model.player.state.first { it.positionMs >= 9900 } }
            checking = "ASS independent visible fade-out"
            fadedPixels()
            capture("fade-grey")
        }
    }

    @Test fun actualTextRendersAtCueStartThroughNativeLease(): Unit = runBlocking {
        withFixture("text") { model ->
            select(model, "NFB Text")
            textPixels()
            capture("text")
        }
    }

    @Test fun subtitleDelayUpdatesPausedPixelsWithoutSeekingOrResuming(): Unit = runBlocking {
        withFixture("delay") { model ->
            select(model, "NFB Text")
            textPixels()
            onMain { model.player.pause() }
            withTimeout(5000) { model.player.state.first { !it.playing } }
            val paused = model.player.state.value.positionMs
            val generation = model.player.state.value.mediaGeneration
            onMain { model.player.subtitleDelay(20_000) }
            rendered("delayed cue is not yet due") { frame ->
                frame.count { Color.alpha(it) > 100 && maxOf(Color.red(it), Color.green(it), Color.blue(it)) > 100 } < 10
            }
            onMain { model.player.subtitleDelay(-1000) }
            textPixels()
            onMain { model.player.subtitleDelay(0) }
            textPixels()
            val after = model.player.state.value
            assertEquals(0L, after.subtitleDelayMs)
            assertEquals(generation, after.mediaGeneration)
            assertFalse("Subtitle timing must preserve the user's pause", after.playing)
            assertTrue("Subtitle timing must not seek video", kotlin.math.abs(after.positionMs - paused) < 250)
        }
    }

    @Test fun textAppearanceChangesPixelsAndSurvivesPreferenceReopen(): Unit = runBlocking {
        withFixture("appearance") { model ->
            val original = model.playbackPreferences.textSubtitleAppearance.value
            try {
                withContext(Dispatchers.Main) { model.playbackPreferences.saveTextSubtitleAppearance(TextSubtitleAppearance()) }
                select(model, "NFB Text")
                textPixels()
                onMain { model.player.pause() }
                fun white(frame: IntArray) = frame.indices.filter { index ->
                    val p = frame[index]
                    Color.alpha(p) > 100 && Color.red(p) > 200 && Color.green(p) > 200 && Color.blue(p) > 200
                }
                val baseline = white(textPixels())
                val baselineY = baseline.map { it / 320 }.average()
                withContext(Dispatchers.Main) { model.playbackPreferences.saveTextSubtitleAppearance(TextSubtitleAppearance(1.75f, .08f)) }
                rendered("larger actual text") { white(it).size > baseline.size * 1.5 }
                val raised = TextSubtitleAppearance(1f, .3f)
                withContext(Dispatchers.Main) { model.playbackPreferences.saveTextSubtitleAppearance(raised) }
                rendered("raised actual text") {
                    val points = white(it)
                    points.size > 60 && points.map { index -> index / 320 }.average() < baselineY - 20
                }
                assertEquals(raised, PlaybackPreferences(instrumentation.targetContext).textSubtitleAppearance.value)
                assertFalse("Appearance changes must preserve pause", model.player.state.value.playing)
                capture("appearance-raised")
            } finally {
                withContext(NonCancellable + Dispatchers.Main) { model.playbackPreferences.saveTextSubtitleAppearance(original) }
            }
        }
    }

    @Test fun actualAssAttachedFontAndDrawingRenderThroughNativeLease(): Unit = runBlocking {
        withFixture("ass") { model ->
            select(model, "NFB ASS Attachment")
            val ass = rendered(checking) { count(it, listOf(255, 0, 0)) > 30 && count(it, listOf(0, 0, 255)) > 150 }
            var runs = 0; var active = false
            for (x in 0 until 140) {
                val column = (0 until 180).count { y -> matches(ass[y * 320 + x], listOf(255, 0, 0)) } > 3
                if (column && !active) runs++
                active = column
            }
            assertEquals("The embedded I font must draw two separate bars rather than a system glyph", 2, runs)
            capture("ass")
        }
    }

    @Test fun actualSixPgsRenderAndClearThroughNativeLease(): Unit = runBlocking {
        withFixture("pgs") { model ->
            val colors = listOf(listOf(255,0,0), listOf(0,255,0), listOf(0,0,255), listOf(255,255,0), listOf(0,255,255), listOf(255,0,255))
            colors.forEachIndexed { index, color ->
                checking = "PGS${index + 1} pixels"
                select(model, "NFB PGS${index + 1}")
                rendered(checking) { frame -> count(frame, color) > 200 && colors.filter { it != color }.all { count(frame, it) < 20 } }
                capture("pgs${index + 1}")
            }
            checking = "subtitle off"
            onMain { model.player.subtitle(-1) }
            rendered(checking) { frame -> frame.count { Color.alpha(it) > 100 && maxOf(Color.red(it), Color.green(it), Color.blue(it)) > 100 } < 10 }
            assertEquals(-1, model.player.state.value.selectedSubtitle)
            capture("off")
        }
    }

    @Test fun actualLongTextRemainsVisibleAfterMidCueSeek(): Unit = runBlocking {
        withFixture("seek") { model ->
            checking = "text before mid-cue seek"
            select(model, "NFB Text")
            textPixels()
            checking = "text mid-cue seek"
            onMain { model.player.seek(3000) }
            withTimeout(5000) { model.player.state.first { it.positionMs in 2500..4500 && it.phase != "正在跳转" } }
            textPixels()
            capture("seek")
        }
    }

    @Test fun actualAssColourMotionAndFadeReachTheComposedViewport(): Unit = runBlocking {
        withFixture("animation", "ass-animation-fixture.mkv") { model ->
            fun yellowCenter(frame: IntArray): Double {
                val xs = frame.indices.filter { matches(frame[it], listOf(255, 255, 0)) }.map { it % 320 }
                assertTrue("Attached moving glyph must have visible pixels", xs.size > 80)
                return xs.average()
            }
            checking = "ASS initial red"
            select(model, "NFB ASS Attachment")
            val early = rendered(checking) { count(it, listOf(255, 0, 0)) > 300 }
            val firstX = yellowCenter(early)
            capture("animation-red")
            checking = "ASS same-bounds blue transform"
            rendered(checking) { count(it, listOf(0, 0, 255)) > 300 && count(it, listOf(255, 0, 0)) < 30 }
            capture("animation-blue")
            withTimeout(6000) { model.player.state.first { it.positionMs >= 4800 } }
            checking = "ASS attached-glyph motion"
            val moved = rendered(checking) { count(it, listOf(255, 255, 0)) > 80 }
            assertTrue("The glyph must move rather than retain an old cached position", yellowCenter(moved) - firstX > 40)
            val occupied = (0 until 320).map { x -> (0 until 180).count { y -> matches(moved[y * 320 + x], listOf(255, 255, 0)) } > 3 }
            assertEquals("The attached I retains both bars during motion", 2, occupied.indices.count { occupied[it] && (it == 0 || !occupied[it - 1]) })
            capture("animation-moved")
            withTimeout(6500) { model.player.state.first { it.positionMs >= 9900 } }
            checking = "ASS visible fade-out"
            fadedPixels()
            capture("animation-faded")
            checking = "ASS end clears all animated regions"
            rendered(checking) { frame -> frame.none { Color.alpha(it) > 100 && maxOf(Color.red(it), Color.green(it), Color.blue(it)) > 100 } }
            capture("animation-cleared")
        }
    }
}
