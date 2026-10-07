package io.github.kkwans.nasfilebrowser

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import androidx.media3.common.Format
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.NoSampleRenderer
import androidx.media3.extractor.text.CuesWithTiming
import io.github.peerless2012.ass.Ass
import io.github.peerless2012.ass.AssFrame
import io.github.peerless2012.ass.AssTexType
import io.github.peerless2012.ass.AssTrack
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Comparison-only overlay. Native work is serialized away from video/audio and UI threads. */
@androidx.annotation.OptIn(UnstableApi::class)
internal class LibassOverlayProbe(context: Context) : View(context), AssPacketExtractor.Sink {
    private val executor = Executors.newSingleThreadExecutor()
    private val submissions = Any()
    private val closed = AtomicBoolean()
    private val queued = AtomicBoolean()
    private val revision = AtomicLong()
    private val timeMs = AtomicLong()
    private val generation = AtomicLong()
    private val tracks = mutableMapOf<String, AssTrack>()
    private val headers = mutableMapOf<String, List<ByteArray>>()
    private val textCues = mutableMapOf<String, MutableList<CuesWithTiming>>()
    private var textCharacters = 0L
    private var ass: Ass? = null
    private var renderer: io.github.peerless2012.ass.AssRender? = null
    private var lastNativeFrame: AssFrame? = null
    private var bytes = 0L
    @Volatile private var selected: String? = null
    @Volatile private var selectedText: String? = null
    var showText: (List<Cue>) -> Unit = {}
    @Volatile private var frameWidth = 0
    @Volatile private var frameHeight = 0
    @Volatile var error: String? = null
        private set
    @Volatile var initializationMs = -1L
        private set
    @Volatile var renderedTimeMs = -1L
        private set
    private var frame: AssFrame? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    init { contentDescription = "libass comparison overlay" }

    private fun submit(block: () -> Unit) {
        synchronized(submissions) {
            if (closed.get()) return
            executor.execute {
                if (!closed.get()) try { block() } catch (failure: Throwable) { error = failure.javaClass.simpleName }
            }
        }
    }
    private fun library(): Ass = ass ?: Ass().also { ass = it }
    override fun font(name: String, bytes: ByteArray) = submit {
        library().addFont(name, bytes)
        requestFrame(timeMs.get())
    }
    override fun format(format: Format) = submit {
        val id = requireNotNull(format.id)
        val previous = headers[id]
        if (previous != null && previous.size == format.initializationData.size &&
            previous.zip(format.initializationData).all { (a, b) -> a.contentEquals(b) }) return@submit
        tracks.remove(id)?.release()
        headers[id] = format.initializationData.map { it.copyOf() }
        tracks[id] = library().createTrack().also { track ->
            val header = format.initializationData.lastOrNull { it.decodeToString().contains("[Script Info]") }
                ?: error("ASS codec private header missing")
            track.readBuffer(header)
        }
    }
    override fun dialogue(trackId: String, startMs: Long, durationMs: Long, packet: ByteArray) = submit {
        bytes += packet.size
        check(bytes <= 64L * 1024 * 1024) { "ASS comparison event budget exceeded" }
        require(durationMs >= 0)
        requireNotNull(tracks[trackId]).readChunk(startMs, durationMs, packet)
        requestFrame(timeMs.get())
    }
    override fun textCues(trackId: String, cues: CuesWithTiming) = submit {
        val entries = textCues.getOrPut(trackId) { mutableListOf() }
        if (entries.none { it.startTimeUs == cues.startTimeUs && it.durationUs == cues.durationUs && it.cues == cues.cues }) {
            textCharacters += cues.cues.sumOf { it.text?.length?.toLong() ?: 0L }
            check(textCharacters <= 4L * 1024 * 1024 && entries.size < 20_000) { "Text cue comparison budget exceeded" }
            entries.add(cues)
        }
        requestFrame(timeMs.get())
    }
    fun select(trackId: String?, textTrackId: String?, positionMs: Long) {
        selected = trackId
        selectedText = textTrackId
        generation.incrementAndGet()
        frame = null
        invalidate()
        requestFrame(positionMs)
    }
    fun requestFrame(positionMs: Long) {
        timeMs.set(positionMs.coerceAtLeast(0))
        revision.incrementAndGet()
        if (closed.get() || !queued.compareAndSet(false, true)) return
        submit {
            val request = revision.get()
            val epoch = generation.get()
            val mediaTime = timeMs.get()
            try {
                val track = tracks[selected]
                val textId = selectedText
                val activeText = textCues[textId].orEmpty().filter {
                    mediaTime * 1000 >= it.startTimeUs && mediaTime * 1000 < it.endTimeUs
                }.flatMap { it.cues }
                val result = if (track == null || frameWidth <= 0 || frameHeight <= 0) null else {
                    val output = renderer ?: run {
                        val start = android.os.SystemClock.elapsedRealtime()
                        library().createRender().also {
                            renderer = it
                            initializationMs = android.os.SystemClock.elapsedRealtime() - start
                            it.setCacheLimit(1000, 64)
                        }
                    }
                    output.setStorageSize(640, 360) // Owned probe fixture geometry, not a product default.
                    output.setFrameSize(frameWidth, frameHeight)
                    output.setTrack(track)
                    val rendered = output.renderFrame(mediaTime, AssTexType.BITMAP_RGBA)
                    if (rendered == null || rendered.changed != 0) lastNativeFrame = rendered
                    lastNativeFrame
                }
                post {
                    if (!closed.get() && generation.get() == epoch) {
                        frame = result; renderedTimeMs = mediaTime
                        if (textId != null) showText(activeText)
                        invalidate()
                    }
                }
            } finally {
                queued.set(false)
                if (revision.get() != request && !closed.get()) requestFrame(timeMs.get())
            }
        }
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        frameWidth = w; frameHeight = h
        requestFrame(timeMs.get())
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        frame?.images?.forEach { image -> image.bitmap?.let { canvas.drawBitmap(it, image.x.toFloat(), image.y.toFloat(), paint) } }
    }
    /** Call off Main after stopping/releasing the player; no queued native use after library release. */
    fun closeAndAwait() {
        synchronized(submissions) {
            if (!closed.compareAndSet(false, true)) return
            generation.incrementAndGet()
            executor.execute {
                renderer?.release(); renderer = null
                tracks.values.forEach { it.release() }; tracks.clear()
                ass?.release(); ass = null
            }
            executor.shutdown()
        }
        check(executor.awaitTermination(30, TimeUnit.SECONDS)) { "libass worker did not finish" }
    }

    class Clock(private val overlay: LibassOverlayProbe) : NoSampleRenderer() {
        private var offsetUs = 0L
        override fun getName() = "FilewayLibassProbeClock"
        override fun onRendererOffsetChanged(offsetUs: Long) { this.offsetUs = offsetUs }
        override fun render(positionUs: Long, elapsedRealtimeUs: Long) = overlay.requestFrame((positionUs - offsetUs) / 1000)
        override fun onPositionReset(positionUs: Long, joining: Boolean, sampleStreamIsResetToKeyFrame: Boolean) =
            overlay.requestFrame((positionUs - offsetUs) / 1000)
    }
}
