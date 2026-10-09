package io.github.kkwans.nasfilebrowser.player

import android.graphics.Bitmap
import androidx.media3.common.C
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.text.pgs.PgsParser
import java.io.ByteArrayOutputStream
import java.util.IdentityHashMap
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** SUP framing and display-set state only; Media3 still decodes PGS palettes/RLE. */
@androidx.annotation.OptIn(UnstableApi::class)
internal object ExternalPgs {
    private const val MAX_BITMAP_BYTES = 32L * 1024 * 1024
    internal class PictureData(val width: Int, val height: Int, val segments: List<ByteArray>)
    internal class Crop(val x: Int, val y: Int, val width: Int, val height: Int)
    internal class ObjectData(val picture: PictureData, val reference: ByteArray, val crop: Crop?)
    internal class DisplaySet(val startTimeUs: Long, val header: ByteArray, val palette: ByteArray?, val objects: List<ObjectData>)
    private class Picture(val version: Int, val width: Int, val height: Int, val expected: Int) {
        val segments = mutableListOf<ByteArray>()
        var received = 0
        var complete = false
        var snapshot: PictureData? = null
    }
    private fun u16(data: ByteArray, at: Int) = ((data[at].toInt() and 255) shl 8) or (data[at + 1].toInt() and 255)
    private fun u32(data: ByteArray, at: Int) = (0..3).fold(0L) { n, i -> (n shl 8) or (data[at + i].toLong() and 255) }
    private fun segment(type: Int, data: ByteArray): ByteArray = byteArrayOf(type.toByte(), (data.size shr 8).toByte(), data.size.toByte()) + data

    /** Validate the complete timeline, retaining shared compressed objects. */
    suspend fun parse(data: ByteArray): List<DisplaySet> {
        require(data.size in 13..16 * 1024 * 1024) { "Invalid SUP size" }
        val pictures = mutableMapOf<Int, Picture>()
        val palettes = mutableMapOf<Int, MutableMap<Int, ByteArray>>()
        val output = mutableListOf<DisplaySet>()
        var presentation: ByteArray? = null
        var offset = 0
        var ptsUs = 0L
        var lastPts = -1L
        var wraps = 0L
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
                    val objects = mutableListOf<ObjectData>()
                    var bitmapBytes = 0L
                    var paletteData: ByteArray? = null
                    var at = 11
                    repeat(pcs[10].toInt() and 255) {
                        require(at + 8 <= pcs.size)
                        val ref = pcs.copyOfRange(at, at + 8)
                        val picture = requireNotNull(pictures[u16(ref, 0)])
                        require(picture.complete)
                        val paletteId = pcs[9].toInt() and 255
                        val palette = requireNotNull(palettes[paletteId])
                        if (paletteData == null) paletteData = ByteArrayOutputStream().apply {
                            write(byteArrayOf(paletteId.toByte(), 0)); palette.toSortedMap().values.forEach { write(it) }
                        }.toByteArray()
                        val cropped = ref[3].toInt() and 0x80 != 0
                        ref[3] = 0
                        at += 8
                        val crop = if (cropped) {
                            require(at + 8 <= pcs.size)
                            val x = u16(pcs, at); val y = u16(pcs, at + 2)
                            val w = u16(pcs, at + 4); val h = u16(pcs, at + 6)
                            require(w > 0 && h > 0 && x + w <= picture.width && y + h <= picture.height)
                            at += 8
                            Crop(x, y, w, h)
                        } else null
                        // Bound each visible display set and the transient full
                        // object needed to decode a crop, rather than film length.
                        val fullBytes = picture.width.toLong() * picture.height * 4
                        val visibleBytes = crop?.let { it.width.toLong() * it.height * 4 } ?: fullBytes
                        require(bitmapBytes + fullBytes + (if (crop != null) visibleBytes else 0) <= MAX_BITMAP_BYTES) { "SUP display set exceeds subtitle memory budget" }
                        bitmapBytes += visibleBytes
                        val image = picture.snapshot ?: PictureData(picture.width, picture.height, picture.segments.toList()).also { picture.snapshot = it }
                        objects.add(ObjectData(image, ref, crop))
                    }
                    require(at == pcs.size)
                    if (output.lastOrNull()?.startTimeUs == ptsUs) output.removeAt(output.lastIndex)
                    output.add(DisplaySet(ptsUs, pcs.copyOfRange(0, 11).apply { this[10] = 1 }, paletteData, objects))
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

    /** Count shared object bytes once, including headers and palette snapshots. */
    fun encodedBytes(values: List<DisplaySet>): Long {
        val arrays = IdentityHashMap<ByteArray, Boolean>()
        fun remember(bytes: ByteArray?) { if (bytes != null) arrays[bytes] = true }
        values.forEach { value ->
            remember(value.header); remember(value.palette)
            value.objects.forEach { item -> remember(item.reference); item.picture.segments.forEach(::remember) }
        }
        return arrays.keys.sumOf { it.size.toLong() }
    }

    /** Only the current display set is expanded; Media3 owns palette/RLE decoding. */
    fun decode(value: DisplaySet): CuesWithTiming {
        val decoder = PgsParser()
        val cues = value.objects.map { item ->
            val packet = ByteArrayOutputStream().apply {
                write(segment(0x16, value.header + item.reference)); write(segment(0x14, requireNotNull(value.palette)))
                item.picture.segments.forEach { write(it) }; write(segment(0x80, byteArrayOf()))
            }.toByteArray()
            var decoded: Cue? = null
            decoder.parse(packet, SubtitleParser.OutputOptions.allCues()) { decoded = it.cues.singleOrNull() }
            val cue = requireNotNull(decoded) { "Invalid PGS bitmap" }
            val crop = item.crop
            if (crop == null) cue else {
                val original = requireNotNull(cue.bitmap)
                val image = Bitmap.createBitmap(original, crop.x, crop.y, crop.width, crop.height)
                if (image !== original) original.recycle()
                cue.buildUpon().setBitmap(image).setSize(crop.width.toFloat() / u16(value.header, 0))
                    .setBitmapHeight(crop.height.toFloat() / u16(value.header, 2)).build()
            }
        }
        return CuesWithTiming(cues, value.startTimeUs, C.TIME_UNSET)
    }
}
