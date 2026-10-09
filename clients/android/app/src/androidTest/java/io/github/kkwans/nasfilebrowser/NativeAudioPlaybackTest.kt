package io.github.kkwans.nasfilebrowser

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.net.Uri
import android.os.SystemClock
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.kkwans.nasfilebrowser.player.NativePlayer
import io.github.kkwans.nasfilebrowser.player.PlaybackTraceAction
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

/** Owned short signals through the real player/audio sink, with no video view.
 * PCM WAV may bypass a codec. AAC separately proves decoder initialization.
 * Clock/decoder evidence does not prove speaker audibility or listening quality.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class NativeAudioPlaybackTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private suspend fun <T> main(action: () -> T): T = withContext(Dispatchers.Main) { action() }
    private fun pcm(seconds: Int): ByteArray = ByteBuffer.allocate(48_000 * seconds * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
        repeat(48_000 * seconds) { sample -> putShort((sin(2 * Math.PI * 440 * sample / 48_000) * 1000).roundToInt().toShort()) }
    }.array()
    private suspend fun await(player: NativePlayer, stage: String, predicate: () -> Boolean) {
        try {
            withTimeout(10_000) {
                while (true) {
                    check(player.state.value.error == null) { "Native audio error at $stage: ${player.state.value.error}" }
                    if (predicate()) break
                    delay(50)
                }
            }
        } catch (error: TimeoutCancellationException) {
            val state = player.state.value
            throw AssertionError("Native audio timeout at $stage: playing=${state.playing}, position=${state.positionMs}, duration=${state.durationMs}, seekable=${state.seekable}, audioDecoder=${state.audioDecoder}, video=${state.width}x${state.height}, phase=${state.phase}", error)
        }
    }
    private fun assertNoVideo(player: NativePlayer) {
        val state = player.state.value
        assertEquals(0, state.width); assertEquals(0, state.height)
        assertFalse(state.firstFrameRendered)
        assertEquals("未知", state.videoDecoder)
        assertTrue("The test must never attach a video surface", player.diagnosticSnapshot().none { it.action == PlaybackTraceAction.ATTACH || it.action == PlaybackTraceAction.VIDEO_OUTPUT })
    }
    @Test fun ownedPcmWavAdvancesPausesSeeksRestoresRateAndReleasesWithoutVideoSurface(): Unit = runBlocking {
        val wave = withContext(Dispatchers.Default) {
            val data = pcm(8)
            ByteBuffer.allocate(44 + data.size).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray(Charsets.US_ASCII)); putInt(36 + data.size); put("WAVEfmt ".toByteArray(Charsets.US_ASCII))
                putInt(16); putShort(1); putShort(1); putInt(48_000); putInt(96_000); putShort(2); putShort(16)
                put("data".toByteArray(Charsets.US_ASCII)); putInt(data.size); put(data)
            }.array()
        }
        val file = File.createTempFile("owned-audio-", ".wav", context.cacheDir)
        var player: NativePlayer? = null
        try {
            withContext(Dispatchers.IO) { file.writeBytes(wave) }
            val native = main { NativePlayer(context) }; player = native
            // Intentionally no attach()/SurfaceView/PlayerViewport.
            main { native.open(Uri.fromFile(file).toString(), autoplay = true) }
            await(native, "PCM playing") { native.state.value.let { it.playing && it.seekable && it.durationMs in 7950..8050 && it.positionMs >= 300 && it.audio.any { track -> track.id >= 0 } } }
            assertNoVideo(native)
            val start = native.state.value.positionMs
            await(native, "PCM clock advances") { native.state.value.positionMs >= start + 350 }
            main { native.pause() }
            await(native, "PCM pause") { !native.state.value.playing && native.state.value.phase == "已暂停" }
            val paused = native.state.value.positionMs
            delay(450)
            assertTrue("Paused audio must not keep advancing", abs(native.state.value.positionMs - paused) <= 150)
            main { native.seek(2500) }
            await(native, "paused PCM seek") { native.state.value.let { !it.playing && it.positionMs in 2400..2600 && it.phase != "正在跳转" } }
            main { native.rate(1.5f); native.toggle() }
            await(native, "PCM resumes at preferred rate") { native.state.value.let { it.playing && it.positionMs >= 3100 && abs(it.rate - 1.5f) < .001f } }
            val temporary = main { native.beginTemporaryRate(2f) }
            assertNotNull("Playing audio must accept temporary rate", temporary)
            assertEquals(2f, native.state.value.rate, .001f)
            main { native.restoreRate(temporary!!) }
            assertEquals(1.5f, native.state.value.rate, .001f)
            main { native.pause() }
            assertEquals(1.5f, native.state.value.rate, .001f)
            main { native.rate(1f); native.toggle() }
            val resumed = native.state.value.positionMs
            await(native, "PCM rate restored to normal") { native.state.value.let { it.playing && it.positionMs >= resumed + 350 && abs(it.rate - 1f) < .001f } }
            assertNoVideo(native)
            main { native.release() }
            val released = native.state.value
            assertFalse(released.playing); assertEquals(0L, released.positionMs); assertEquals(0L, released.durationMs)
            assertTrue(released.audio.isEmpty())
            delay(350)
            assertEquals("Released audio must not restart from a late callback", released, native.state.value)
        } finally { main { player?.release() }; withContext(Dispatchers.IO) { file.delete() } }
    }
    private fun encodeAac(data: ByteArray): ByteArray {
        val codec = try { MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC) }
        catch (error: Exception) { throw AssertionError("AAC encoder unavailable: compressed-decoder acceptance is UNVERIFIED", error) }
        val output = ByteArrayOutputStream()
        try {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 48_000, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 64_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); codec.start()
            val info = MediaCodec.BufferInfo(); var offset = 0; var inputEnded = false; var outputEnded = false
            val deadline = SystemClock.elapsedRealtime() + 10_000
            while (!outputEnded) {
                check(SystemClock.elapsedRealtime() < deadline) { "Owned AAC encoding timed out; decoder acceptance is UNVERIFIED" }
                if (!inputEnded) {
                    val index = codec.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val input = requireNotNull(codec.getInputBuffer(index)); input.clear()
                        val count = minOf(input.remaining(), data.size - offset) and -2
                        check(offset == data.size || count > 0) { "AAC input buffer cannot accept a complete PCM frame" }
                        val timestamp = offset.toLong() / 2 * 1_000_000 / 48_000
                        if (count > 0) { input.put(data, offset, count); codec.queueInputBuffer(index, 0, count, timestamp, 0); offset += count }
                        else { codec.queueInputBuffer(index, 0, 0, timestamp, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputEnded = true }
                    }
                }
                while (true) {
                    val index = codec.dequeueOutputBuffer(info, 10_000)
                    if (index < 0) break
                    try {
                        if (info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            val packet = ByteArray(info.size)
                            requireNotNull(codec.getOutputBuffer(index)).apply { position(info.offset); limit(info.offset + info.size); get(packet) }
                            val length = packet.size + 7
                            check(length < 8192)
                            // MPEG-4 AAC-LC, 48 kHz (index 3), mono, no CRC.
                            output.write(byteArrayOf(0xFF.toByte(), 0xF1.toByte(), ((1 shl 6) or (3 shl 2)).toByte(),
                                ((1 shl 6) or (length shr 11)).toByte(), (length shr 3).toByte(), (((length and 7) shl 5) or 0x1F).toByte(), 0xFC.toByte()))
                            output.write(packet)
                        }
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputEnded = true
                    } finally { codec.releaseOutputBuffer(index, false) }
                    if (outputEnded) break
                }
            }
            check(output.size() > 7) { "AAC encoder produced no owned media; decoder acceptance is UNVERIFIED" }
            return output.toByteArray()
        } catch (error: Exception) {
            throw AssertionError("Owned AAC encoding unavailable or failed: compressed-decoder acceptance is UNVERIFIED", error)
        } finally { runCatching { codec.stop() }; codec.release() }
    }
    @Test fun ownedCompressedAacInitializesRealAudioDecoderAndClockWithoutVideoSurface(): Unit = runBlocking {
        val encoded = withContext(Dispatchers.Default) { encodeAac(pcm(2)) }
        val file = File.createTempFile("owned-audio-", ".aac", context.cacheDir)
        var player: NativePlayer? = null
        try {
            withContext(Dispatchers.IO) { file.writeBytes(encoded) }
            val native = main { NativePlayer(context) }; player = native
            main { native.open(Uri.fromFile(file).toString(), autoplay = true) }
            await(native, "AAC decoder and clock") { native.state.value.let { it.playing && it.positionMs >= 150 && it.audioDecoder.isNotBlank() && it.audioDecoder != "未知" && it.audio.any { track -> track.id >= 0 } } }
            assertNoVideo(native)
            val first = native.state.value.positionMs
            await(native, "AAC clock advances") { native.state.value.playing && native.state.value.positionMs >= first + 200 }
            assertNotEquals("未知", native.state.value.audioDecoder)
            main { native.release() }
            assertFalse(native.state.value.playing)
        } finally { main { player?.release() }; withContext(Dispatchers.IO) { file.delete() } }
    }
}
