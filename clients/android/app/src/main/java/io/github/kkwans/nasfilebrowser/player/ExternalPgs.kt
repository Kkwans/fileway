package io.github.kkwans.nasfilebrowser.player

import android.graphics.Bitmap
import androidx.media3.common.C
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.text.pgs.PgsParser
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** SUP framing and display-set state only; Media3 still decodes PGS palettes/RLE. */
@androidx.annotation.OptIn(UnstableApi::class)
internal object ExternalPgs {
    private class Picture(val version: Int, val width: Int, val height: Int, val expected: Int) {
        val segments = mutableListOf<ByteArray>()
        var received = 0
        var complete = false
    }
    private fun u16(data: ByteArray, at: Int) = ((data[at].toInt() and 255) shl 8) or (data[at + 1].toInt() and 255)
    private fun u32(data: ByteArray, at: Int) = (0..3).fold(0L) { n, i -> (n shl 8) or (data[at + i].toLong() and 255) }
    private fun segment(type: Int, data: ByteArray): ByteArray = byteArrayOf(type.toByte(), (data.size shr 8).toByte(), data.size.toByte()) + data

    suspend fun parse(data: ByteArray): List<CuesWithTiming> {
        require(data.size in 13..16 * 1024 * 1024) { "Invalid SUP size" }
        val pictures = mutableMapOf<Int, Picture>()
        val palettes = mutableMapOf<Int, MutableMap<Int, ByteArray>>()
        val output = mutableListOf<CuesWithTiming>()
        val decoder = PgsParser()
        var presentation: ByteArray? = null
        var offset = 0
        var ptsUs = 0L
        var lastPts = -1L
        var wraps = 0L
        var bitmapBytes = 0L
        while (offset < data.size) {
            currentCoroutineContext().ensureActive()
            require(data.size - offset >= 13 && data[offset] == 'P'.code.toByte() && data[offset + 1] == 'G'.code.toByte()) { "Invalid SUP header" }
            val type = data[offset + 10].toInt() and 255
            val size = u16(data, offset + 11)
            val end = offset + 13 + size
            require(end <= data.size) { "Truncated SUP segment" }
            val payload = data.copyOfRange(offset + 13, end)
            when (type) {
                0x16 -> {
                    require(presentation == null && size >= 11 && u16(payload, 0) in 1..8192 && u16(payload, 2) in 1..8192)
                    val pts = u32(data, offset + 2)
                    if (lastPts > pts && lastPts - pts > 0x80000000L) wraps += 0x100000000L
                    val nextUs = (pts + wraps) * 1_000_000 / 90_000
                    require(lastPts < 0 || nextUs >= ptsUs) { "SUP presentation time moves backwards" }
                    lastPts = pts; ptsUs = nextUs
                    if (payload[7].toInt() and 0x80 != 0) { pictures.clear(); palettes.clear() }
                    require((payload[10].toInt() and 255) <= 2) { "Unsupported PGS object count" }
                    presentation = payload
                }
                0x14 -> {
                    require(size >= 2 && (size - 2) % 5 == 0)
                    val palette = palettes.getOrPut(payload[0].toInt() and 255) { mutableMapOf() }
                    require(palettes.size <= 8)
                    for (i in 2 until size step 5) palette[payload[i].toInt() and 255] = payload.copyOfRange(i, i + 5)
                }
                0x15 -> {
                    require(size >= 4)
                    val id = u16(payload, 0); val flags = payload[3].toInt() and 255
                    val base = flags and 0x80 != 0
                    if (base) {
                        require(size >= 11)
                        val length = ((payload[4].toInt() and 255) shl 16) or u16(payload, 5)
                        val width = u16(payload, 7); val height = u16(payload, 9)
                        require(length in 5..4 * 1024 * 1024 && width > 0 && height > 0 && width.toLong() * height <= 4_000_000)
                        pictures[id] = Picture(payload[2].toInt() and 255, width, height, length - 4)
                    }
                    val picture = requireNotNull(pictures[id])
                    require(!picture.complete && picture.version == (payload[2].toInt() and 255))
                    picture.received += size - if (base) 11 else 4
                    require(picture.received <= picture.expected)
                    picture.segments.add(segment(type, payload))
                    if (flags and 0x40 != 0) { require(picture.received == picture.expected); picture.complete = true }
                    require(pictures.size <= 64)
                }
                0x80 -> {
                    require(size == 0)
                    val pcs = requireNotNull(presentation)
                    val cues = mutableListOf<Cue>()
                    var at = 11
                    repeat(pcs[10].toInt() and 255) {
                        require(at + 8 <= pcs.size)
                        val ref = pcs.copyOfRange(at, at + 8)
                        val picture = requireNotNull(pictures[u16(ref, 0)])
                        require(picture.complete)
                        val paletteId = pcs[9].toInt() and 255
                        val palette = requireNotNull(palettes[paletteId])
                        val paletteData = ByteArrayOutputStream().apply {
                            write(byteArrayOf(paletteId.toByte(), 0)); palette.toSortedMap().values.forEach { write(it) }
                        }.toByteArray()
                        val header = pcs.copyOfRange(0, 11).apply { this[10] = 1 }
                        val cropped = ref[3].toInt() and 0x80 != 0
                        ref[3] = 0
                        val packet = ByteArrayOutputStream().apply {
                            write(segment(0x16, header + ref)); write(segment(0x14, paletteData))
                            picture.segments.forEach { write(it) }; write(segment(0x80, byteArrayOf()))
                        }.toByteArray()
                        var cue: Cue? = null
                        decoder.parse(packet, SubtitleParser.OutputOptions.allCues()) { cue = it.cues.singleOrNull() }
                        var value = requireNotNull(cue) { "Invalid PGS bitmap" }
                        at += 8
                        if (cropped) {
                            require(at + 8 <= pcs.size)
                            val x = u16(pcs, at); val y = u16(pcs, at + 2)
                            val w = u16(pcs, at + 4); val h = u16(pcs, at + 6)
                            require(w > 0 && h > 0 && x + w <= picture.width && y + h <= picture.height)
                            val original = requireNotNull(value.bitmap)
                            value = value.buildUpon().setBitmap(Bitmap.createBitmap(original, x, y, w, h))
                                .setSize(w.toFloat() / u16(pcs, 0)).setBitmapHeight(h.toFloat() / u16(pcs, 2)).build()
                            at += 8
                        }
                        bitmapBytes += requireNotNull(value.bitmap).byteCount
                        require(bitmapBytes <= 32L * 1024 * 1024) { "SUP exceeds subtitle memory budget" }
                        cues.add(value)
                    }
                    require(at == pcs.size)
                    if (output.lastOrNull()?.startTimeUs == ptsUs) output.removeAt(output.lastIndex)
                    output.add(CuesWithTiming(cues, ptsUs, C.TIME_UNSET))
                    require(output.size <= 20_000)
                    presentation = null
                }
                0x17 -> Unit // Window geometry is already represented by PCS object positions.
                else -> error("Unsupported SUP segment")
            }
            offset = end
        }
        require(presentation == null && output.isNotEmpty()) { "Incomplete SUP display set" }
        return output
    }
}
