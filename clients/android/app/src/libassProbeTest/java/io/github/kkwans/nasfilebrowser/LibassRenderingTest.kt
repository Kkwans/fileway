package io.github.kkwans.nasfilebrowser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.view.Gravity
import android.view.SurfaceView
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.MediaItem
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.ui.SubtitleView
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
@androidx.annotation.OptIn(UnstableApi::class)
class LibassRenderingTest {
    @get:Rule val activity = ActivityScenarioRule(EngineProbeActivity::class.java)
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private suspend fun <T> main(block: () -> T): T = withContext(Dispatchers.Main) { block() }

    private suspend fun movie(asset: String, verify: suspend (ExoPlayer, LibassOverlayProbe, SubtitleView) -> Unit) {
        val media = instrumentation.context.assets.open("media/$asset").use { it.readBytes() }
        lateinit var player: ExoPlayer
        lateinit var overlay: LibassOverlayProbe
        lateinit var text: SubtitleView
        var assSelected = false
        var standardTextSelected = false
        var audioDecoder = ""
        activity.scenario.onActivity { host ->
            overlay = LibassOverlayProbe(host)
            text = SubtitleView(host)
            overlay.showText = { text.setCues(it) }
            val video = SurfaceView(host)
            val frame = FrameLayout(host)
            host.viewport.addView(frame, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                host.resources.displayMetrics.widthPixels * 9 / 16, Gravity.CENTER))
            for (view in listOf(video, text, overlay)) frame.addView(view, FrameLayout.LayoutParams(-1, -1))
            val renderers = object : DefaultRenderersFactory(host) {
                override fun buildMiscellaneousRenderers(context: Context, eventHandler: Handler, extensionRendererMode: Int,
                    out: ArrayList<Renderer>) {
                    super.buildMiscellaneousRenderers(context, eventHandler, extensionRendererMode, out)
                    out.add(LibassOverlayProbe.Clock(overlay))
                }
            }.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            player = ExoPlayer.Builder(host, renderers).setMediaSourceFactory(DefaultMediaSourceFactory(host,
                ExtractorsFactory { arrayOf(AssPacketExtractor(overlay)) })).build()
            player.setVideoSurfaceView(video)
            player.addAnalyticsListener(object : AnalyticsListener {
                override fun onAudioDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String,
                    initializedTimestampMs: Long, initializationDurationMs: Long) { audioDecoder = decoderName }
            })
            player.addListener(object : Player.Listener {
                override fun onTracksChanged(tracks: Tracks) {
                    val selected = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
                        .flatMap { group -> (0 until group.length).filter { group.isTrackSelected(it) }.map { group.getTrackFormat(it) } }.firstOrNull()
                    assSelected = selected?.codecs == MimeTypes.TEXT_SSA
                    val retainedText = selected?.codecs == MimeTypes.APPLICATION_SUBRIP
                    standardTextSelected = selected != null && !assSelected && !retainedText
                    overlay.select(if (assSelected) selected?.id else null, if (retainedText) selected?.id else null, player.currentPosition)
                    if (!standardTextSelected) text.setCues(emptyList())
                }
                override fun onCues(cueGroup: CueGroup) { text.setCues(if (standardTextSelected) cueGroup.cues else emptyList()) }
            })
        }
        try {
            NativePlaybackTest.Fixture(media).use { source ->
                val session = NasSession.login(ServerProfile(name = "Owned libass rendering", address = source.url), "fixture", "fixture-only")
                var lease: String? = null
                try {
                    val url = session.lease("/fixture.mkv", "/fixture.mkv"); lease = url
                    main {
                        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                            .setPreferredAudioMimeTypes(MimeTypes.AUDIO_TRUEHD).build()
                        player.setMediaItem(MediaItem.fromUri(url)); player.prepare(); player.play()
                    }
                    withTimeout(20_000) {
                        while (!main { check(player.playerError == null); player.isPlaying && player.currentPosition > 0 &&
                            audioDecoder.startsWith("ffmpeg") && audioDecoder.endsWith("-truehd") }) delay(50)
                    }
                    verify(player, overlay, text)
                    assertEquals(0, source.unexpected.get())
                } finally {
                    withContext(NonCancellable) {
                        main { player.stop() }
                        try { lease?.let { NativeTransport.call(JSONObject().put("op", "revoke").put("url", it)) } }
                        finally { session.close() }
                    }
                }
            }
        } catch (failure: Throwable) {
            capture("failure-$asset")
            throw failure
        } finally {
            main { player.release() }
            withContext(NonCancellable + Dispatchers.IO) { overlay.closeAndAwait() }
            instrumentation.addResults(Bundle().apply {
                putString("filewayLibassProbe", JSONObject().put("asset", asset)
                    .put("rendererInitMs", overlay.initializationMs).put("workerError", overlay.error ?: JSONObject.NULL)
                    .put("audioDecoder", audioDecoder)
                    .put("hdrAndPhysicalAudio", "UNVERIFIED").toString())
            })
        }
    }
    private fun capture(name: String) {
        val device = UiDevice.getInstance(instrumentation)
        device.executeShellCommand("mkdir -p /sdcard/Download/nfb-client-acceptance")
        device.executeShellCommand("screencap -p /sdcard/Download/nfb-client-acceptance/libass-$name.png")
    }
    private suspend fun pixels(view: LibassOverlayProbe): IntArray? {
        val bounds = main { Rect().takeIf { view.getGlobalVisibleRect(it) && !it.isEmpty } } ?: return null
        val shot = instrumentation.uiAutomation.takeScreenshot() ?: return null
        val bitmap = shot.copy(Bitmap.Config.ARGB_8888, false)
        return try {
            if (bounds.right > bitmap.width || bounds.bottom > bitmap.height) return null
            IntArray(320 * 180) { index -> bitmap.getPixel(bounds.left + (index % 320 * bounds.width() / 320),
                bounds.top + (index / 320 * bounds.height() / 180)) }
        } finally { bitmap.recycle(); shot.recycle() }
    }
    private suspend fun at(player: ExoPlayer, overlay: LibassOverlayProbe, ms: Long, predicate: (IntArray) -> Boolean): IntArray {
        main { player.pause(); player.seekTo(ms) }
        var accepted: IntArray? = null
        withTimeout(20_000) {
            while (true) {
                check(overlay.error == null) { "libass worker failed: ${overlay.error}" }
                val correctTime = main { check(player.playerError == null); abs(player.currentPosition - ms) < 500 && abs(overlay.renderedTimeMs - ms) < 500 }
                if (correctTime) pixels(overlay)?.let { if (predicate(it)) accepted = it }
                if (accepted != null) break
                delay(50)
            }
        }
        return requireNotNull(accepted)
    }
    private suspend fun selectAss(player: ExoPlayer) = main {
        val group = player.currentTracks.groups.single { group -> group.type == C.TRACK_TYPE_TEXT &&
            (0 until group.length).any { group.getTrackFormat(it).codecs == MimeTypes.TEXT_SSA } }
        val index = (0 until group.length).first { group.getTrackFormat(it).codecs == MimeTypes.TEXT_SSA }
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .clearOverridesOfType(C.TRACK_TYPE_TEXT).addOverride(TrackSelectionOverride(group.mediaTrackGroup, index)).build()
    }
    private fun red(pixel: Int) = Color.red(pixel) > 170 && Color.green(pixel) < 100 && Color.blue(pixel) < 100
    private fun blue(pixel: Int) = Color.blue(pixel) > 170 && Color.red(pixel) < 100 && Color.green(pixel) < 100

    @Test fun attachedFontDrawingAndMidCueSeekReachActualOverlay(): Unit = runBlocking {
        movie("subtitle-fixture.mkv") { player, overlay, _ ->
            selectAss(player)
            val frame = at(player, overlay, 2000) { it.count(::red) > 30 && it.count(::blue) > 150 }
            var runs = 0; var active = false
            for (x in 0 until 140) {
                val column = (0 until 180).count { y -> red(frame[y * 320 + x]) } > 3
                if (column && !active) runs++
                active = column
            }
            assertEquals("Embedded font must produce two bars", 2, runs)
            capture("font-and-drawing")
            at(player, overlay, 6000) { it.count(::red) > 30 && it.count(::blue) > 150 }
            at(player, overlay, 0) { it.count(::red) < 10 && it.count(::blue) < 10 }
            at(player, overlay, 2000) { it.count(::red) > 30 && it.count(::blue) > 150 }
            capture("seek-restored")
            main { player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build() }
            withTimeout(5000) { while (pixels(overlay)?.let { it.count(::red) < 10 && it.count(::blue) < 10 } != true) delay(50) }
        }
    }
    @Test fun sameBoundsColourAndFadeAreNotDropped(): Unit = runBlocking {
        movie("ass-animation-fixture.mkv") { player, overlay, _ ->
            selectAss(player)
            fun yellowCenter(frame: IntArray): Double {
                val positions = frame.indices.filter { Color.red(frame[it]) > 170 && Color.green(frame[it]) > 170 && Color.blue(frame[it]) < 100 }
                assertTrue("Moving attached-font glyph must be visible", positions.size > 30)
                return positions.map { (it % 320).toDouble() }.average()
            }
            val early = at(player, overlay, 1000) { it.count(::red) > 200 }
            capture("animation-red")
            val later = at(player, overlay, 3500) { it.count(::blue) > 200 && it.count(::red) < 20 }
            assertTrue("ASS motion must update the actual glyph position", yellowCenter(later) - yellowCenter(early) > 25)
            capture("animation-blue-motion")
            val roi = (30 until 50).flatMap { y -> (200 until 240).map { x -> y * 320 + x } }
            at(player, overlay, 8000) { frame -> roi.count { Color.red(frame[it]) > 220 && Color.green(frame[it]) > 220 && Color.blue(frame[it]) > 220 } > 300 }
            at(player, overlay, 9900) { frame -> roi.count { Color.red(frame[it]) in 50..170 && Color.green(frame[it]) in 50..170 && Color.blue(frame[it]) in 50..170 } > 300 }
            capture("animation-fade")
        }
    }
    @Test fun pgsAndTextSwitchWithoutAnAssOverlayRemaining(): Unit = runBlocking {
        movie("subtitle-fixture.mkv") { player, overlay, _ ->
            selectAss(player)
            at(player, overlay, 2000) { it.count(::red) > 30 && it.count(::blue) > 150 }
            val colors = listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW, Color.CYAN, Color.MAGENTA)
            fun count(frame: IntArray, color: Int) = frame.count { pixel ->
                abs(Color.red(pixel) - Color.red(color)) < 85 && abs(Color.green(pixel) - Color.green(color)) < 85 &&
                    abs(Color.blue(pixel) - Color.blue(color)) < 85 }
            for ((index, color) in colors.withIndex()) {
                main {
                    val group = player.currentTracks.groups.filter { group -> group.type == C.TRACK_TYPE_TEXT &&
                        (0 until group.length).any { group.getTrackFormat(it).codecs == MimeTypes.APPLICATION_PGS } }[index]
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_TEXT)
                        .addOverride(TrackSelectionOverride(group.mediaTrackGroup, 0)).build()
                }
                at(player, overlay, 2000) { frame -> count(frame, color) > 200 && colors.filter { it != color }.all { count(frame, it) < 20 } }
                capture("pgs-${index + 1}")
            }
            main {
                val group = player.currentTracks.groups.single { group -> group.type == C.TRACK_TYPE_TEXT &&
                    (0 until group.length).any { group.getTrackFormat(it).codecs == MimeTypes.APPLICATION_SUBRIP } }
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_TEXT)
                    .addOverride(TrackSelectionOverride(group.mediaTrackGroup, 0)).build()
            }
            at(player, overlay, 6000) { frame -> frame.count { Color.red(it) > 200 && Color.green(it) > 200 && Color.blue(it) > 200 } > 60 }
            capture("text-mid-cue")
            main { player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build() }
            withTimeout(5000) { while (pixels(overlay)?.all { maxOf(Color.red(it), Color.green(it), Color.blue(it)) < 100 } != true) delay(50) }
            capture("off")
        }
    }
}
