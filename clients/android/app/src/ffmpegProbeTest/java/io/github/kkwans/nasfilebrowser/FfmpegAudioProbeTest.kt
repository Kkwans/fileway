package io.github.kkwans.nasfilebrowser

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.view.SurfaceView
import android.view.ViewGroup
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.ffmpeg.FfmpegLibrary
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.kkwans.nasfilebrowser.core.NativeTransport
import io.github.kkwans.nasfilebrowser.data.NasSession
import io.github.kkwans.nasfilebrowser.data.ServerProfile
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.sqrt

/** Optional artifact-backed test source set. PCM here is before Sonic/output routing,
 * so it proves decoded content, not time stretching or audible speaker/Bluetooth output. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class FfmpegAudioProbeTest {
    @get:Rule val activity = ActivityScenarioRule(EngineProbeActivity::class.java)
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private suspend fun <T> main(block: () -> T): T = withContext(Dispatchers.Main) { block() }

    private class PcmMeter : TeeAudioProcessor.AudioBufferSink {
        private var sampleRate = 0
        private var channels = 0
        private var encoding = 0
        private var frames = 0L
        private var crossings = 0L
        private var energy = 0.0
        private var previous = 0.0
        @Synchronized fun reset() { frames = 0; crossings = 0; energy = 0.0; previous = 0.0 }
        @Synchronized override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
            sampleRate = sampleRateHz; channels = channelCount; this.encoding = encoding; reset()
        }
        @Synchronized override fun handleBuffer(buffer: ByteBuffer) {
            if (sampleRate <= 0 || channels <= 0 || encoding != C.ENCODING_PCM_16BIT) return
            val input = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            val frameBytes = channels * 2
            while (input.remaining() >= frameBytes) {
                if (frames >= sampleRate / 2) reset()
                val value = input.short.toDouble() / 32768.0
                input.position(input.position() + frameBytes - 2)
                if (frames > 0 && previous <= 0 && value > 0) crossings++
                energy += value * value; frames++; previous = value
            }
        }
        @Synchronized fun snapshot() = JSONObject().put("sampleRate", sampleRate).put("channels", channels)
            .put("encoding", encoding).put("frames", frames)
            .put("frequencyHz", if (frames > 0) crossings.toDouble() * sampleRate / frames else 0.0)
            .put("rms", if (frames > 0) sqrt(energy / frames) else 0.0)
    }

    @Test fun fourOwnedTonesDecodeAcrossTrackSeekAndPausedRateChanges(): Unit = runBlocking {
        assertTrue("Pinned FFmpeg JNI must actually load", FfmpegLibrary.isAvailable())
        assertTrue("TrueHD decoder must be present", FfmpegLibrary.supportsFormat(MimeTypes.AUDIO_TRUEHD))
        val media = instrumentation.context.assets.open("media/subtitle-fixture.mkv").use { it.readBytes() }
        val sha = MessageDigest.getInstance("SHA-256").digest(media).joinToString("") { "%02x".format(it) }
        assertEquals("d582828ab83e23f5d6f784babe30eb6ffb274c8e108258e0fb54207b4c06da64", sha)
        val meter = PcmMeter()
        lateinit var player: ExoPlayer
        var decoder = ""
        var inputMime = ""
        var audioClockEvents = 0
        val records = JSONArray()
        var stage = "construct"
        activity.scenario.onActivity { host ->
            val factory = object : DefaultRenderersFactory(host) {
                override fun buildAudioSink(context: Context, enableFloatOutput: Boolean,
                    enableAudioOutputPlaybackParams: Boolean): AudioSink = DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(false).setEnableAudioOutputPlaybackParameters(false)
                    .setAudioProcessors(arrayOf(TeeAudioProcessor(meter))).build()
            }.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            player = ExoPlayer.Builder(host, factory).build()
            val surface = SurfaceView(host)
            host.viewport.addView(surface, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            player.setVideoSurfaceView(surface)
            player.addAnalyticsListener(object : AnalyticsListener {
                override fun onAudioDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String,
                    initializedTimestampMs: Long, initializationDurationMs: Long) { decoder = decoderName }
                override fun onAudioInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format,
                    decoderReuseEvaluation: DecoderReuseEvaluation?) { inputMime = format.sampleMimeType.orEmpty() }
                override fun onAudioPositionAdvancing(eventTime: AnalyticsListener.EventTime, playoutStartSystemTimeMs: Long) { audioClockEvents++ }
            })
        }
        suspend fun awaitReady(predicate: () -> Boolean) = withTimeout(20_000) {
            while (!main {
                check(player.playerError == null) { "Media3 error code=${player.playerError?.errorCode}" }
                predicate()
            }) delay(50)
        }
        try {
            NativePlaybackTest.Fixture(media).use { source ->
                val session = NasSession.login(ServerProfile(name = "Owned FFmpeg tones", address = source.url), "fixture", "fixture-only")
                var lease: String? = null
                try {
                    lease = session.lease("/fixture.mkv", "/fixture.mkv")
                    val input = lease
                    main {
                        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
                        player.setMediaItem(MediaItem.fromUri(input)); player.prepare(); player.play()
                    }
                    stage = "track-discovery"
                    awaitReady { player.currentTracks.groups.count { it.type == C.TRACK_TYPE_AUDIO } >= 4 }
                    val formats = listOf(MimeTypes.AUDIO_TRUEHD to 220, MimeTypes.AUDIO_AAC to 440,
                        MimeTypes.AUDIO_FLAC to 660, MimeTypes.AUDIO_OPUS to 880, MimeTypes.AUDIO_TRUEHD to 220)
                    for ((index, entry) in formats.withIndex()) {
                        val (mime, hz) = entry
                        stage = "select-$mime"
                        main {
                            player.pause()
                            val group = player.currentTracks.groups.single { group -> group.type == C.TRACK_TYPE_AUDIO &&
                                (0 until group.length).any { group.getTrackFormat(it).sampleMimeType == mime } }
                            val track = (0 until group.length).first { group.getTrackFormat(it).sampleMimeType == mime }
                            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                                .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                                .addOverride(TrackSelectionOverride(group.mediaTrackGroup, track)).build()
                            player.setPlaybackSpeed(if (index % 2 == 0) 1f else 1.5f)
                            player.seekTo(if (index % 2 == 0) 2000 else 6000)
                        }
                        delay(150)
                        main { assertFalse("Selection/seek/rate must retain paused intent", player.playWhenReady) }
                        meter.reset()
                        val started = SystemClock.elapsedRealtime()
                        val beforeClock = main { audioClockEvents }
                        main { player.play() }
                        stage = "pcm-$mime"
                        var accepted: JSONObject? = null
                        awaitReady {
                            val pcm = meter.snapshot()
                            val ready = player.isPlaying && decoder.startsWith("ffmpeg") && inputMime == mime &&
                                audioClockEvents > beforeClock && pcm.getLong("frames") >= pcm.getInt("sampleRate") / 4 &&
                                pcm.getInt("sampleRate") > 0 && abs(pcm.getDouble("frequencyHz") - hz) <= 12 && pcm.getDouble("rms") > .01
                            if (ready) accepted = pcm.put("mime", inputMime).put("decoder", decoder)
                                .put("reportedRate", player.playbackParameters.speed).put("positionMs", player.currentPosition)
                            ready
                        }
                        val record = requireNotNull(accepted)
                        record.put("decodedToneReadyMs", SystemClock.elapsedRealtime() - started)
                        records.put(record)
                        main { player.pause() }
                    }
                    assertEquals(0, source.unexpected.get())
                } finally {
                    withContext(NonCancellable) {
                        main { player.stop() }
                        try { lease?.let { NativeTransport.call(JSONObject().put("op", "revoke").put("url", it)) } }
                        finally { session.close() }
                    }
                }
            }
        } catch (error: Throwable) {
            throw AssertionError("FFmpeg audio probe failed at $stage; decoder=$decoder; mime=$inputMime; pcm=${meter.snapshot()}", error)
        } finally {
            main { player.release() }
            instrumentation.addResults(Bundle().apply {
                putString("filewayFfmpegAudioProbe", JSONObject().put("ffmpeg", FfmpegLibrary.getVersion())
                    .put("sampleSha256", sha).put("audioPolicy", "FFmpeg preferred; PCM16 before Sonic")
                    .put("physicalOutput", "UNVERIFIED").put("records", records).toString())
            })
        }
    }
}
