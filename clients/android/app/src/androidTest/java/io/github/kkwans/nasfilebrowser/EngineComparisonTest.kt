package io.github.kkwans.nasfilebrowser

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.kkwans.nasfilebrowser.core.NativeTransport
import io.github.kkwans.nasfilebrowser.data.NasSession
import io.github.kkwans.nasfilebrowser.data.ServerProfile
import io.github.kkwans.nasfilebrowser.player.NativePlayer
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.util.VLCVideoLayout
import java.security.MessageDigest
import kotlin.math.abs

/** Base-path comparison, not full engine acceptance. No FFmpeg/libass/mpv claim. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class EngineComparisonTest {
    @get:Rule val activity = ActivityScenarioRule(EngineProbeActivity::class.java)
    @Test fun nativeBasePathsUseTheSameLeaseAndPixelChecks(): Unit = runBlocking {
        EngineComparisonHarness(activity).run()
    }
}

/** Test-only injection keeps optional native candidates on the exact same fixture and assertions. */
@androidx.annotation.OptIn(UnstableApi::class)
internal class EngineComparisonHarness(
    private val activity: ActivityScenarioRule<EngineProbeActivity>,
    private val additional: Map<String, (EngineProbeActivity) -> Probe> = emptyMap(),
) {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    /** All adapter access is on Main, including getters. No product engine registry. */
    interface Probe {
        val view: View
        val version: String
        val position: Long
        val duration: Long
        val playing: Boolean
        val rate: Float
        val error: Boolean
        fun open(url: String)
        fun seek(position: Long)
        fun pause()
        fun play()
        fun speed(value: Float)
        fun observations(): JSONObject
        fun close()
    }

    private class VlcProbe(host: EngineProbeActivity) : Probe {
        private val player = NativePlayer(host)
        override val view = VLCVideoLayout(host)
        override val version get() = "${LibVLC.version()}/${LibVLC.changeset()}"
        override val position get() = player.state.value.positionMs
        override val duration get() = player.state.value.durationMs
        override val playing get() = player.state.value.playing
        override val rate get() = player.state.value.rate
        override val error get() = player.state.value.error != null
        override fun open(url: String) { player.attach(view); player.open(url) }
        override fun seek(position: Long) = player.seek(position)
        override fun pause() = player.pause()
        override fun play() { if (!playing) player.toggle() }
        override fun speed(value: Float) = player.rate(value)
        override fun observations() = JSONObject().put("decoder", "UNKNOWN").put("audioClockEvents", JSONObject.NULL)
            .put("trace", JSONArray(player.diagnosticSnapshot().map {
                JSONObject().put("action", it.action.name).put("elapsedMs", it.elapsedMs).put("value", it.value ?: JSONObject.NULL)
            }))
        override fun close() = player.release()
    }

    private class Media3Probe(host: EngineProbeActivity) : Probe {
        override val view = SurfaceView(host)
        private var videoDecoder = "UNKNOWN"
        private var audioDecoder = "UNKNOWN"
        private var audioClockEvents = 0
        private var frameEvents = 0
        private val player = ExoPlayer.Builder(host).build().apply {
            addAnalyticsListener(object : AnalyticsListener {
                override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String,
                    initializedTimestampMs: Long, initializationDurationMs: Long) { videoDecoder = decoderName }
                override fun onAudioDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String,
                    initializedTimestampMs: Long, initializationDurationMs: Long) { audioDecoder = decoderName }
                override fun onAudioPositionAdvancing(eventTime: AnalyticsListener.EventTime, playoutStartSystemTimeMs: Long) { audioClockEvents++ }
                override fun onRenderedFirstFrame(eventTime: AnalyticsListener.EventTime, output: Any, renderTimeMs: Long) { frameEvents++ }
            })
        }
        override val version = "Media3/1.11.1-platform-decoders"
        override val position get() = player.currentPosition
        override val duration get() = player.duration.coerceAtLeast(0)
        override val playing get() = player.isPlaying
        override val rate get() = player.playbackParameters.speed
        override val error get() = player.playerError != null
        override fun open(url: String) { player.setVideoSurfaceView(view); player.setMediaItem(MediaItem.fromUri(url)); player.prepare(); player.play() }
        override fun seek(position: Long) = player.seekTo(position)
        override fun pause() = player.pause()
        override fun play() = player.play()
        override fun speed(value: Float) = player.setPlaybackSpeed(value)
        override fun observations() = JSONObject().put("decoder", videoDecoder).put("audioDecoder", audioDecoder)
            .put("audioClockEvents", audioClockEvents).put("frameEvents", frameEvents)
        override fun close() { player.clearVideoSurfaceView(view); player.release() }
    }

    private suspend fun <T> main(block: () -> T): T = withContext(Dispatchers.Main) { block() }
    private fun surface(view: View): SurfaceView? {
        if (view is SurfaceView && (view.id == View.NO_ID ||
                runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull() == "surface_video")) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) surface(view.getChildAt(index))?.let { return it }
        return null
    }

    private suspend fun hasPixels(probe: Probe): Boolean {
        val view = main { surface(probe.view)?.takeIf { it.width > 0 && it.height > 0 && it.holder.surface.isValid } } ?: return false
        val image = Bitmap.createBitmap(144, 81, Bitmap.Config.ARGB_8888)
        val copied = CompletableDeferred<Int>()
        var requested = false
        try {
            main { PixelCopy.request(view, image, { copied.complete(it) }, Handler(Looper.getMainLooper())); requested = true }
            if (copied.await() != PixelCopy.SUCCESS) return false
            val pixels = IntArray(144 * 81)
            image.getPixels(pixels, 0, 144, 0, 0, 144, 81)
            return pixels.count { maxOf(Color.red(it), Color.green(it), Color.blue(it)) -
                minOf(Color.red(it), Color.green(it), Color.blue(it)) > 30 } > pixels.size / 5
        } finally { if (!requested || copied.isCompleted) image.recycle() else copied.invokeOnCompletion { image.recycle() } }
    }

    private suspend fun awaitState(probe: Probe, timeout: Long = 20_000, predicate: () -> Boolean) {
        withTimeout(timeout) {
            while (!main { check(!probe.error) { "Engine reported an error" }; predicate() }) delay(50)
        }
    }

    suspend fun run() {
        val arguments = InstrumentationRegistry.getArguments()
        val factories = linkedMapOf<String, (EngineProbeActivity) -> Probe>("vlc" to ::VlcProbe, "media3" to ::Media3Probe)
        require(additional.keys.none { it in factories })
        factories.putAll(additional)
        // Use a single argument value: UTP can alter comma-separated runner values.
        val selection = arguments.getString("nfbProbeEngines") ?: "all"
        val engines = if (selection == "all") factories.keys.toList() else {
            require(selection in factories) { "nfbProbeEngines must be all or one of ${factories.keys}" }
            listOf(selection)
        }
        val rounds = (arguments.getString("nfbProbeRounds") ?: "2").toInt().also { require(it in 1..10) }
        val media = instrumentation.context.assets.open("media/fixture.mkv").use { it.readBytes() }
        val sha = MessageDigest.getInstance("SHA-256").digest(media).joinToString("") { "%02x".format(it) }
        lateinit var host: EngineProbeActivity
        activity.scenario.onActivity { host = it }
        withTimeout(5000) { while (!main { host.viewport.width > 0 }) delay(50) }
        val failures = mutableListOf<String>()
        val measurements = mutableListOf<JSONObject>()
        NativePlaybackTest.Fixture(media).use { source ->
            val session = NasSession.login(ServerProfile(name = "Owned engine probe", address = source.url), "fixture", "fixture-only")
            var lease: String? = null
            try {
                // Empty cache key selects Broker.Lease, not LeaseCached. Both engines
                // use this exact capability; no persisted credentials/profile/node.
                val input = session.lease("/fixture.mkv", "/fixture.mkv")
                lease = input
                repeat(rounds) { round ->
                    val order = if (round % 2 == 0) engines else engines.reversed()
                    for ((orderIndex, engine) in order.withIndex()) {
                        val result = JSONObject().put("engine", engine).put("round", round).put("order", orderIndex)
                            .put("sampleSha256", sha).put("api", Build.VERSION.SDK_INT).put("abi", Build.SUPPORTED_ABIS.first())
                            .put("persistentTransportCache", false).put("hardwareAudioHdrAcceptance", "UNVERIFIED")
                        var probe: Probe? = null
                        var stage = "construct"
                        val started = SystemClock.elapsedRealtime()
                        try {
                            probe = main {
                                requireNotNull(factories[engine])(host).also {
                                    host.viewport.addView(it.view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                        host.viewport.width * 9 / 16, Gravity.CENTER))
                                }
                            }
                            val current = requireNotNull(probe)
                            result.put("version", main { current.version }).put("constructionMs", SystemClock.elapsedRealtime() - started)
                            stage = "first-pixels"
                            main { current.open(input) }
                            withTimeout(20_000) {
                                awaitState(current) { current.playing && current.position > 0 && current.duration > 0 }
                                while (!hasPixels(current)) delay(50)
                            }
                            result.put("firstPixelMs", SystemClock.elapsedRealtime() - started)
                            stage = "seek"
                            val seekStarted = SystemClock.elapsedRealtime()
                            main { current.seek(6000) }
                            awaitState(current) { abs(current.position - 6000) <= 1000 && current.playing }
                            withTimeout(20_000) { while (!hasPixels(current)) delay(50) }
                            result.put("seekClockAndPixelsMs", SystemClock.elapsedRealtime() - seekStarted)
                            stage = "pause-and-rate"
                            main { current.pause() }
                            awaitState(current, 5000) { !current.playing }
                            main { current.speed(1.5f) }
                            delay(300)
                            main { assertFalse(current.playing); assertEquals(1.5f, current.rate, .01f) }
                            val beforeResume = main { current.position }
                            main { current.play() }
                            awaitState(current, 5000) { current.playing && current.position > beforeResume + 200 }
                            result.put("status", "PASS_BASELINE_ONLY")
                        } catch (error: Throwable) {
                            if (error is CancellationException && error !is TimeoutCancellationException) throw error
                            failures.add("$engine/$round/$stage/${error.javaClass.simpleName}")
                            result.put("status", "FAIL").put("stage", stage).put("exception", error.javaClass.simpleName)
                        } finally {
                            main {
                                probe?.let { result.put("observations", it.observations()); it.close(); host.viewport.removeView(it.view) }
                            }
                            // Numeric/enumerated observations only: never report capability URLs.
                            // Android test parsers reserve 0 for a completed test;
                            // candidate measurements are IN_PROGRESS (2), not passes.
                            measurements.add(result)
                            instrumentation.sendStatus(2, Bundle().apply { putString("filewayEngineProbe", result.toString()) })
                        }
                    }
                }
                assertEquals("Only the authenticated raw/media fixture contract is allowed", 0, source.unexpected.get())
            } finally {
                withContext(NonCancellable) {
                    try { lease?.let { NativeTransport.call(JSONObject().put("op", "revoke").put("url", it)) } }
                    finally { session.close() }
                }
            }
        }
        instrumentation.addResults(Bundle().apply {
            putString("filewayEngineProbeSummary", JSONObject().put("selection", selection).put("rounds", rounds)
                .put("attempts", measurements.size).put("records", JSONArray(measurements)).toString())
        })
        assertTrue("Baseline failures: ${failures.joinToString()}", failures.isEmpty())
    }
}
