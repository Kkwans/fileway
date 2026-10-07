package io.github.kkwans.nasfilebrowser

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.DataReader
import androidx.media3.extractor.*
import androidx.media3.extractor.mkv.EbmlProcessor
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleTranscodingExtractorOutput
import java.io.ByteArrayOutputStream
import java.io.EOFException

/** Public-API interception before Media3's subtitle transcoding; no private field access.
 * This comparison adapter reports original ASS packets; it does not render them. */
@androidx.annotation.OptIn(UnstableApi::class)
internal class AssPacketExtractor(private val sink: Sink) : Extractor {
    interface Sink {
        fun font(name: String, bytes: ByteArray)
        fun format(format: Format)
        fun dialogue(trackId: String, startMs: Long, durationMs: Long, packet: ByteArray)
    }
    private val streams = mutableListOf<Capture>()
    private var transcoder: SubtitleTranscodingExtractorOutput? = null
    private var fontBytes = 0L
    private val delegate = object : MatroskaExtractor(DefaultSubtitleParserFactory(), FLAG_EMIT_RAW_SUBTITLE_DATA) {
        private var name: String? = null
        private var mime: String? = null
        private var bytes: ByteArray? = null
        override fun getElementType(id: Int): Int = when (id) {
            0x1941A469, 0x61A7 -> EbmlProcessor.ELEMENT_TYPE_MASTER
            0x466E, 0x4660 -> EbmlProcessor.ELEMENT_TYPE_STRING
            0x465C -> EbmlProcessor.ELEMENT_TYPE_BINARY
            else -> super.getElementType(id)
        }
        override fun isLevel1Element(id: Int) = id == 0x1941A469 || super.isLevel1Element(id)
        override fun startMasterElement(id: Int, contentPosition: Long, contentSize: Long) {
            if (id == 0x61A7) { name = null; mime = null; bytes = null }
            else super.startMasterElement(id, contentPosition, contentSize)
        }
        override fun stringElement(id: Int, value: String) {
            when (id) { 0x466E -> name = value; 0x4660 -> mime = value; else -> super.stringElement(id, value) }
        }
        override fun binaryElement(id: Int, contentSize: Int, input: ExtractorInput) {
            if (id != 0x465C) { super.binaryElement(id, contentSize, input); return }
            if (contentSize !in 0..MAX_FONT_BYTES || fontBytes + contentSize > MAX_TOTAL_FONTS ||
                (mime != null && mime !in FONT_MIMES)) { input.skipFully(contentSize); return }
            bytes = ByteArray(contentSize).also { input.readFully(it, 0, it.size) }
        }
        override fun endMasterElement(id: Int) {
            if (id != 0x61A7) { super.endMasterElement(id); return }
            val data = bytes; val title = name
            if (data != null && title != null && title.length <= 256 && mime in FONT_MIMES) {
                fontBytes += data.size
                sink.font(title, data)
            }
            name = null; mime = null; bytes = null
        }
    }

    override fun sniff(input: ExtractorInput) = delegate.sniff(input)
    override fun init(output: ExtractorOutput) {
        val converted = SubtitleTranscodingExtractorOutput(output, DefaultSubtitleParserFactory())
        transcoder = converted
        delegate.init(object : ExtractorOutput by converted {
            override fun track(id: Int, type: Int): TrackOutput {
                val track = converted.track(id, type)
                return if (type == C.TRACK_TYPE_TEXT) Capture(track).also(streams::add) else track
            }
        })
    }
    override fun read(input: ExtractorInput, seekPosition: PositionHolder) = delegate.read(input, seekPosition)
    override fun seek(position: Long, timeUs: Long) {
        streams.forEach { it.clear() }
        transcoder?.resetSubtitleParsers()
        delegate.seek(position, timeUs)
    }
    override fun release() { streams.clear(); delegate.release() }

    private inner class Capture(private val output: TrackOutput) : TrackOutput by output {
        private var trackId: String? = null
        private val pending = ByteArrayOutputStream()
        fun clear() = pending.reset()
        override fun format(format: Format) {
            trackId = format.id.takeIf { format.sampleMimeType == MimeTypes.TEXT_SSA }
            if (trackId != null) sink.format(format)
            output.format(format)
        }
        private fun capture(data: ByteArray, offset: Int, length: Int, part: Int) {
            if (trackId == null || part != TrackOutput.SAMPLE_DATA_PART_MAIN) return
            check(pending.size().toLong() + length <= MAX_PACKET_BYTES) { "ASS sample exceeds the comparison memory budget" }
            pending.write(data, offset, length)
        }
        // Override both overloads: Kotlin delegation otherwise bypasses the observer.
        override fun sampleData(data: ParsableByteArray, length: Int) = sampleData(data, length, TrackOutput.SAMPLE_DATA_PART_MAIN)
        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            capture(data.data, data.position, length, sampleDataPart)
            output.sampleData(data, length, sampleDataPart)
        }
        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean): Int =
            sampleData(input, length, allowEndOfInput, TrackOutput.SAMPLE_DATA_PART_MAIN)
        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
            if (trackId == null) return output.sampleData(input, length, allowEndOfInput, sampleDataPart)
            check(length in 0..MAX_PACKET_BYTES)
            val bytes = ByteArray(length)
            val count = input.read(bytes, 0, length)
            if (count < 0) { if (allowEndOfInput) return -1 else throw EOFException() }
            capture(bytes, 0, count, sampleDataPart)
            output.sampleData(ParsableByteArray(bytes, count), count, sampleDataPart)
            return count
        }
        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
            trackId?.let { id ->
                val data = pending.toByteArray()
                val end = data.size - offset
                val start = end - size
                check(start >= 0 && end in start..data.size) { "Invalid ASS sample boundaries" }
                if (timeUs != C.TIME_UNSET) {
                    val first = (start until end).firstOrNull { data[it] == ','.code.toByte() } ?: error("Missing SSA start prefix")
                    val second = (first + 1 until end).firstOrNull { data[it] == ','.code.toByte() } ?: error("Missing SSA end prefix")
                    val match = SSA_TIME.matchEntire(data.decodeToString(first + 1, second).trim()) ?: error("Invalid SSA duration")
                    val (hours, minutes, seconds, centiseconds) = match.destructured
                    val duration = ((hours.toLong() * 60 + minutes.toLong()) * 60 + seconds.toLong()) * 1000 + centiseconds.toLong() * 10
                    sink.dialogue(id, timeUs / 1000, duration, data.copyOfRange(second + 1, end))
                }
                pending.reset()
                pending.write(data, end, offset)
            }
            output.sampleMetadata(timeUs, flags, size, offset, cryptoData)
        }
    }
    companion object {
        private const val MAX_FONT_BYTES = 32 * 1024 * 1024
        private const val MAX_TOTAL_FONTS = 64L * 1024 * 1024
        private const val MAX_PACKET_BYTES = 4 * 1024 * 1024
        private val FONT_MIMES = setOf("font/ttf", "font/otf", "font/sfnt", "application/x-truetype-font", "application/vnd.ms-opentype", "application/x-font-ttf")
        private val SSA_TIME = Regex("(\\d+):(\\d{2}):(\\d{2})[:.](\\d{2})")
    }
}
