package io.github.kkwans.nasfilebrowser

import androidx.media3.common.C
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.kkwans.nasfilebrowser.player.ExternalPgs
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class ExternalPgsTest {
    @Test fun realPgsDecoderPreservesSupTimingCachedObjectsCropAndClear(): Unit = runBlocking {
        val cues = ExternalPgs.parse(ownedSup())
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
    companion object {
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
