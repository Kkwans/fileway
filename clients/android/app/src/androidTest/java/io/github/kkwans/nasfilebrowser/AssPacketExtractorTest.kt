package io.github.kkwans.nasfilebrowser

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.extractor.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.kkwans.nasfilebrowser.core.NativeTransport
import io.github.kkwans.nasfilebrowser.data.NasSession
import io.github.kkwans.nasfilebrowser.data.ServerProfile
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest

/** Exercises real Matroska/Range extraction, not ASS rendering or font fidelity. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class AssPacketExtractorTest {
    private data class Dialogue(val id: String, val start: Long, val duration: Long, val text: String)
    @Test fun publicOutputsPreserveOwnedFontAssPacketsAndOtherSubtitleTracks(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().context
        val media = context.assets.open("media/subtitle-fixture.mkv").use { it.readBytes() }
        val fonts = mutableMapOf<String, ByteArray>()
        val headers = mutableMapOf<String, Format>()
        val dialogues = mutableListOf<Dialogue>()
        val formats = mutableMapOf<Int, Format>()
        val samples = mutableMapOf<Int, Int>()
        var map: SeekMap? = null
        val extractor = AssPacketExtractor(object : AssPacketExtractor.Sink {
            override fun font(name: String, bytes: ByteArray) { fonts[name] = bytes }
            override fun format(format: Format) { headers[requireNotNull(format.id)] = format }
            override fun dialogue(trackId: String, startMs: Long, durationMs: Long, packet: ByteArray) {
                dialogues.add(Dialogue(trackId, startMs, durationMs, packet.decodeToString()))
            }
        })
        extractor.init(object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput = object : TrackOutput by DiscardingTrackOutput() {
                override fun format(format: Format) { formats[id] = format }
                override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
                    samples[id] = samples.getOrDefault(id, 0) + 1
                }
            }
            override fun endTracks() = Unit
            override fun seekMap(seekMap: SeekMap) { map = seekMap }
        })
        NativePlaybackTest.Fixture(media).use { source ->
            val session = NasSession.login(ServerProfile(name = "Owned ASS extraction", address = source.url), "fixture", "fixture-only")
            var lease: String? = null
            try {
                val url = session.lease("/fixture.mkv", "/fixture.mkv"); lease = url
                suspend fun readFrom(start: Long) = withContext(Dispatchers.IO) {
                    var position = start
                    var iterations = 0
                    var finished = false
                    while (!finished) {
                        check(++iterations <= 16) { "Unexpected extractor seek loop" }
                        val data = DefaultHttpDataSource.Factory().setConnectTimeoutMs(5000).setReadTimeoutMs(5000).createDataSource()
                        try {
                            val length = data.open(DataSpec.Builder().setUri(Uri.parse(url)).setPosition(position).build())
                            val input = DefaultExtractorInput(data, position, if (length == C.LENGTH_UNSET.toLong()) length else position + length)
                            val requested = PositionHolder()
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                when (extractor.read(input, requested)) {
                                    Extractor.RESULT_END_OF_INPUT -> { finished = true; break }
                                    Extractor.RESULT_SEEK -> { position = requested.position; break }
                                }
                            }
                        } finally { data.close() }
                    }
                }
                withTimeout(20_000) { readFrom(0) }
                val font = requireNotNull(fonts["NfbFixtureBars.ttf"])
                assertEquals("d4142d96504934781f44f4c99a301989324f31adb269c477de4ef5bd4774fe88",
                    MessageDigest.getInstance("SHA-256").digest(font).joinToString("") { "%02x".format(it) })
                assertEquals(1, headers.size)
                assertTrue(headers.values.single().initializationData.any { it.decodeToString().contains("NfbFixtureBars") })
                assertTrue(dialogues.any { it.text.contains("\\pos(80,40)") && it.text.contains("I") })
                assertTrue(dialogues.any { it.text.contains("\\p1") && it.text.contains("m 0 0") })
                assertTrue(dialogues.all { it.start in 500..600 && it.duration in 10_000..10_600 })
                val subtitles = formats.filterValues { it.sampleMimeType == MimeTypes.APPLICATION_MEDIA3_CUES }
                assertEquals("ASS interception must retain all eight selectable subtitle tracks", 8, subtitles.size)
                assertEquals(6, subtitles.values.count { it.codecs == MimeTypes.APPLICATION_PGS })
                assertTrue(subtitles.keys.all { samples.getOrDefault(it, 0) > 0 })
                val before = dialogues.toList()
                val seekMap = requireNotNull(map)
                assertTrue(seekMap.isSeekable)
                val point = seekMap.getSeekPoints(0).first
                extractor.seek(point.position, 0)
                withTimeout(20_000) { readFrom(point.position) }
                assertTrue("Re-reading after seek must emit intact ASS packets", dialogues.size > before.size)
                assertTrue(dialogues.drop(before.size).all { it in before })
                assertEquals(0, source.unexpected.get())
            } finally {
                extractor.release()
                withContext(NonCancellable) {
                    try { lease?.let { NativeTransport.call(JSONObject().put("op", "revoke").put("url", it)) } }
                    finally { session.close() }
                }
            }
        }
    }
}
