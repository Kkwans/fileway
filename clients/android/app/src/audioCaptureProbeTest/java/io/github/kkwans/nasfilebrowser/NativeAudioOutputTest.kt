package io.github.kkwans.nasfilebrowser

import android.Manifest
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.widget.FrameLayout
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import io.github.kkwans.nasfilebrowser.core.NativeTransport
import io.github.kkwans.nasfilebrowser.data.NasSession
import io.github.kkwans.nasfilebrowser.data.ServerProfile
import io.github.kkwans.nasfilebrowser.player.NativePlayer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.TestRule
import org.junit.runners.model.Statement
import io.github.kkwans.nasfilebrowser.player.PlayerViewport
import java.util.regex.Pattern
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.sin

/** Explicit owned-fixture diagnostic: current Media3 product, own UID audio mix, no account/data writes. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class NativeAudioOutputTest {
    private val activity = ActivityScenarioRule(AudioCaptureProbeActivity::class.java)
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun checkpoint(stage: String) {
        instrumentation.sendStatus(2, Bundle().apply { putString("stream", "AUDIO_PROBE_STAGE=$stage\n") })
    }
    // This rule encloses ActivityScenarioRule: a launch stall must be visible even
    // when JUnit has not entered the test body yet.
    @get:Rule val launchTrace = TestRule { base, description ->
        object : Statement() {
            override fun evaluate() {
                checkpoint("activity-launch-requested")
                try { activity.apply(base, description).evaluate() }
                finally { checkpoint("activity-rule-finished") }
            }
        }
    }
    private suspend fun <T> main(block: () -> T): T = withContext(Dispatchers.Main) { block() }
    private lateinit var meter: CapturedPlaybackMeter
    private var captureClock: AudioTrack? = null
    private var clockWriter: Job? = null

    private suspend fun tone(hz: Int, since: Long, timeout: Long = 6000): CapturedTone = withTimeout(timeout) {
        while (true) {
            check(PlaybackCaptureProbeService.failure == null) { "Capture service: ${PlaybackCaptureProbeService.failure}" }
            check(meter.error == null) { "Capture worker: ${meter.error}" }
            val value = meter.snapshot(since)
            check(value.maximumReadGapMs <= 250) { "Capture reader stalled; cannot attribute silence to the player: $value" }
            if (value.frames >= 9600 && abs(value.frequencyHz - hz) <= 15 && value.rms > .001) return@withTimeout value
            delay(25)
        }
        @Suppress("UNREACHABLE_CODE") error("Unreachable")
    }
    private suspend fun stopCaptureClock() {
        val writer = clockWriter; val track = captureClock
        clockWriter = null; captureClock = null
        writer?.cancel()
        try {
            if (track?.playState == AudioTrack.PLAYSTATE_PLAYING) track.stop()
            writer?.join()
        } finally { track?.release() }
    }
    private suspend fun calibrate(scope: CoroutineScope, keepClock: Boolean) {
        val player = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_ALL).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(48_000).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
            .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(960 * 4 * 4).build()
        val wave = ShortArray(960 * 2) { i -> (sin(2 * Math.PI * 1000 * (i / 2) / 48_000) * 5000).toInt().toShort() }
        val silence = ShortArray(wave.size)
        val calibrationTone = AtomicBoolean(true)
        val started = System.nanoTime()
        captureClock = player
        player.play()
        clockWriter = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val data = if (calibrationTone.get()) wave else silence
                val written = player.write(data, 0, data.size, AudioTrack.WRITE_BLOCKING)
                if (isActive) check(written > 0)
            }
        }
        tone(1000, started)
        // A zero-amplitude track holds the capture mix clock open during engine output rebuilds.
        // It adds no audible signal; the following calibration verifies that zero remains zero.
        calibrationTone.set(false)
        val stopped = System.nanoTime()
        withTimeout(5000) {
            while (meter.snapshot(stopped).let { it.frames < 9600 || it.rms >= .001 || it.maximumSilentMs < 200 }) delay(25)
        }
        if (!keepClock) stopCaptureClock()
    }
    private fun record(stage: String, value: CapturedTone, elapsed: Long): JSONObject = JSONObject()
        .put("stage", stage).put("observedReadyMs", elapsed).put("frames", value.frames)
        .put("frequencyHz", value.frequencyHz).put("rms", value.rms)
        .put("maximumSilentMs", value.maximumSilentMs).put("maximumReadGapMs", value.maximumReadGapMs)

    @Test fun realPlaybackMixSurvivesTracksSubtitlesAndRates(): Unit = runBlocking {
        checkpoint("activity-resumed-test-entered")
        require(InstrumentationRegistry.getArguments().getString("nfbOwnedAudioCapture") == "true")
        require(ownedAudioProbeDevice())
        val captureClockMode = InstrumentationRegistry.getArguments().getString("nfbCaptureClock") ?: "silent"
        require(captureClockMode in setOf("silent", "none"))
        val device = UiDevice.getInstance(instrumentation)
        val context = instrumentation.targetContext
        val audio = context.getSystemService(AudioManager::class.java)
        val oldVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val observations = JSONArray()
        var player: NativePlayer? = null
        var stage = "projection-consent"
        var lastAction = System.nanoTime()
        var failureBeforeCleanup: JSONObject? = null
        try {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, maxOf(1, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) / 2), 0)
            activity.scenario.onActivity { it.requestOwnedPlaybackCapture() }
            checkpoint("projection-consent-requested")
            val confirm = device.wait(Until.findObject(By.pkg("com.android.systemui")
                .text(Pattern.compile("(?i)start now|start recording|share screen|start|立即开始|开始录制|开始录屏|开始|共享屏幕")).enabled(true)), 10_000)
                ?: run {
                    device.dumpWindowHierarchy(java.io.File(context.getExternalFilesDir(null), "owned-audio-consent.xml"))
                    error("Platform projection confirmation missing; preserved owned-audio-consent.xml")
                }
            confirm.click()
            checkpoint("projection-confirmation-clicked")
            withTimeout(10_000) {
                while (PlaybackCaptureProbeService.meter == null) {
                    check(PlaybackCaptureProbeService.failure == null) { "Capture service: ${PlaybackCaptureProbeService.failure}" }
                    activity.scenario.onActivity { check(!it.captureDenied) }
                    delay(50)
                }
            }
            meter = requireNotNull(PlaybackCaptureProbeService.meter)
            checkpoint("capture-service-ready")
            withTimeout(5000) { while (meter.snapshot(0).frames < 9600) { check(meter.error == null); delay(25) } }
            stage = "calibration-tone-and-silence"
            checkpoint(stage)
            calibrate(this, captureClockMode == "silent")
            checkpoint("calibration-passed")
            observations.put(JSONObject().put("stage", stage).put("status", "PASS"))

            val media = instrumentation.context.assets.open("media/subtitle-fixture.mkv").use { it.readBytes() }
            NativePlaybackTest.Fixture(media).use { source ->
                val session = NasSession.login(ServerProfile(name = "Owned output tones", address = source.url), "fixture", "fixture-only")
                var lease: String? = null
                try {
                    val url = session.lease("/fixture.mkv", "/fixture.mkv"); lease = url
                    stage = "native-first-output"
                    lastAction = System.nanoTime()
                    val start = SystemClock.elapsedRealtime()
                    activity.scenario.onActivity { host ->
                        player = NativePlayer(host).also { native ->
                            val surface = PlayerViewport(host)
                            host.viewport.addView(surface, FrameLayout.LayoutParams(-1, host.resources.displayMetrics.widthPixels * 9 / 16, Gravity.CENTER))
                            native.attach(surface)
                        }
                    }
                    val native = requireNotNull(player)
                    val constructionMs = SystemClock.elapsedRealtime() - start
                    check(constructionMs < 20_000) { "Native construction exceeded the startup deadline" }
                    main { native.open(url) }
                    val remainingStartupMs = 20_000 - (SystemClock.elapsedRealtime() - start)
                    check(remainingStartupMs > 0) { "Native open exceeded the startup deadline" }
                    val firstOutput = withTimeout(remainingStartupMs) {
                        native.state.first { it.playing && it.seekable && it.audio.count { track -> track.id >= 0 } == 4 }
                        tone(220, lastAction, 20_000)
                    }
                    observations.put(record(stage, firstOutput, SystemClock.elapsedRealtime() - start).put("constructionMs", constructionMs))
                    var hz = 220
                    suspend fun reposition() {
                        main { native.seek(2000) }
                        withTimeout(10_000) { native.state.first { it.playing && it.positionMs >= 1800 && it.phase != "正在跳转" } }
                        tone(hz, System.nanoTime())
                    }
                    for ((marker, expected) in listOf("AAC" to 440, "FLAC" to 660, "Opus" to 880, "TrueHD" to 220)) {
                        stage = "audio-$marker"
                        reposition()
                        val before = native.state.value.positionMs
                        val generation = native.state.value.mediaGeneration
                        val track = native.state.value.audio.single { it.id >= 0 && it.title.contains(marker) }
                        lastAction = System.nanoTime(); val requested = SystemClock.elapsedRealtime()
                        main { native.audio(track.id) }
                        val value = tone(expected, lastAction)
                        val elapsed = SystemClock.elapsedRealtime() - requested
                        observations.put(record(stage, value, elapsed))
                        assertTrue("Actual switched tone must recover within 3s", elapsed <= 3000)
                        assertTrue("Audio selection must not restart the media", native.state.value.positionMs >= before - 250)
                        assertEquals("Audio switch must keep media generation", generation, native.state.value.mediaGeneration)
                        assertEquals(track.id, native.state.value.selectedAudio)
                        hz = expected
                    }
                    for (marker in listOf("NFB Text", "NFB ASS Attachment", "NFB PGS1", "off")) {
                        stage = "subtitle-$marker"
                        reposition()
                        val id = if (marker == "off") -1 else native.state.value.subtitles.single { it.id >= 0 && it.title.contains(marker) }.id
                        lastAction = System.nanoTime(); val requested = SystemClock.elapsedRealtime()
                        main { native.subtitle(id) }
                        delay(1200)
                        val value = tone(220, lastAction)
                        observations.put(record(stage, value, SystemClock.elapsedRealtime() - requested))
                        assertTrue("Subtitle change interrupted actual audio output: $value", value.maximumSilentMs <= 40)
                    }
                    for (rate in listOf(1.5f, .75f, 1f)) {
                        stage = "rate-$rate"
                        reposition()
                        lastAction = System.nanoTime(); val requested = SystemClock.elapsedRealtime()
                        main { native.rate(rate) }
                        delay(1200)
                        val value = tone(220, lastAction)
                        observations.put(record(stage, value, SystemClock.elapsedRealtime() - requested))
                        assertTrue("Rate change interrupted actual audio output: $value", value.maximumSilentMs <= 40)
                    }
                    assertEquals(0, source.unexpected.get())
                } catch (failure: Throwable) {
                    // Preserve output/player evidence before stop() clears the engine state and audio policy.
                    val state = player?.state?.value
                    val pcm = meter.snapshot(lastAction)
                    failureBeforeCleanup = record(stage, pcm, ((System.nanoTime() - lastAction) / 1_000_000))
                        .put("phase", state?.phase).put("positionMs", state?.positionMs).put("rate", state?.rate)
                        .put("playing", state?.playing).put("mediaGeneration", state?.mediaGeneration)
                        .put("audioPolicies", JSONArray(audio.activePlaybackConfigurations.map {
                            JSONObject().put("usage", it.audioAttributes.usage).put("capturePolicy", it.audioAttributes.allowedCapturePolicy)
                        }))
                        .put("capturedWindows", JSONArray(meter.timeline(lastAction).map {
                            JSONArray(listOf(it.observedAfterMs, it.frames, it.rms, it.readGapMs))
                        }))
                        .put("trace", JSONArray(player?.diagnosticSnapshot().orEmpty().map {
                            JSONObject().put("sequence", it.sequence).put("action", it.action.name).put("elapsedMs", it.elapsedMs).put("value", it.value)
                        }))
                    throw failure
                } finally {
                    withContext(NonCancellable) {
                        main { player?.stop() }
                        try { lease?.let { NativeTransport.call(JSONObject().put("op", "revoke").put("url", it)) } }
                        finally { session.close() }
                    }
                }
            }
        } catch (failure: Throwable) {
            throw AssertionError("Playback output probe failed at $stage; capture=${PlaybackCaptureProbeService.failure}; beforeCleanup=$failureBeforeCleanup", failure)
        } finally {
            withContext(NonCancellable) {
                try {
                    main { player?.release() }
                    context.stopService(Intent(context, PlaybackCaptureProbeService::class.java))
                    withTimeout(5000) { while (PlaybackCaptureProbeService.meter != null) delay(25) }
                } finally {
                    try { stopCaptureClock() }
                    finally { audio.setStreamVolume(AudioManager.STREAM_MUSIC, oldVolume, 0) }
                }
            }
            instrumentation.addResults(Bundle().apply { putString("filewayPlaybackOutputProbe", JSONObject()
                .put("engine", "Media3+FFmpeg+libass").put("stage", stage).put("capture", "own UID Android playback mix")
                .put("captureClock", captureClockMode)
                .put("physicalOutputAndTempo", "UNVERIFIED").put("records", observations)
                .put("failureBeforeCleanup", failureBeforeCleanup ?: JSONObject.NULL).toString()) })
        }
    }
}
