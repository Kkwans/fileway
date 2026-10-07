package io.github.kkwans.nasfilebrowser.player

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import androidx.media3.common.C
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** One media generation owns the native subtitle state. Video remains on its own Surface. */
@androidx.annotation.OptIn(UnstableApi::class)
internal class MediaSubtitleLayer(context: Context) : View(context), MediaSubtitleExtractor.Sink {
    private val worker = Executors.newSingleThreadExecutor()
    private val submissions = Any()
    private val closed = AtomicBoolean()
    private val queued = AtomicBoolean()
    private val revision = AtomicLong()
    private val selection = AtomicLong()
    private val timeMs = AtomicLong()
    private var library: Ass? = null
    private var renderer: io.github.peerless2012.ass.AssRender? = null
    private val tracks = mutableMapOf<String, AssTrack>()
    private val headers = mutableMapOf<String, List<ByteArray>>()
    private val cues = mutableMapOf<String, MutableList<CuesWithTiming>>()
    private var eventBytes = 0L
    private var cueBytes = 0L
    private var lastNative: AssFrame? = null
    private var lastTrack: AssTrack? = null
    private var frame: AssFrame? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    @Volatile private var assId: String? = null
    @Volatile private var textId: String? = null
    @Volatile private var frameWidth = 0
    @Volatile private var frameHeight = 0
    @Volatile var storageWidth = 0
    @Volatile var storageHeight = 0
    @Volatile var delayMs = 0L
    @Volatile var collecting = false
        private set
    var showText: (List<Cue>) -> Unit = {}
    var failed: () -> Unit = {}

    private fun submit(action: () -> Unit) = synchronized(submissions) {
        if (!closed.get()) worker.execute {
            if (!closed.get()) try { action() }
            catch (_: Exception) { post { if (!closed.get()) failed() } }
        }
    }
    private fun ass() = library ?: Ass().also { library = it }
    override fun active() { collecting = true }
    override fun font(name: String, bytes: ByteArray) = submit { ass().addFont(name, bytes) }
    override fun format(format: Format) = submit {
        val id = requireNotNull(format.id)
        val old = headers[id]
        if (old != null && old.size == format.initializationData.size && old.zip(format.initializationData).all { (a, b) -> a.contentEquals(b) }) return@submit
        tracks.remove(id)?.release()
        headers[id] = format.initializationData.map { it.copyOf() }
        tracks[id] = ass().createTrack().also { track ->
            track.readBuffer(requireNotNull(format.initializationData.lastOrNull { it.decodeToString().contains("[Script Info]") }))
        }
    }
    override fun dialogue(trackId: String, startMs: Long, durationMs: Long, packet: ByteArray) = submit {
        eventBytes += packet.size
        check(eventBytes <= 64L * 1024 * 1024 && durationMs >= 0)
        requireNotNull(tracks[trackId]).readChunk(startMs, durationMs, packet)
        requestFrame(timeMs.get())
    }
    private fun bytes(value: CuesWithTiming) = value.cues.sumOf { (it.text?.length?.toLong() ?: 0) * 2 + (it.bitmap?.byteCount?.toLong() ?: 0) }
    override fun textCues(trackId: String, value: CuesWithTiming) = submit {
        val list = cues.getOrPut(trackId) { mutableListOf() }
        if (list.none { it.startTimeUs == value.startTimeUs && it.durationUs == value.durationUs && it.cues == value.cues }) {
            list.add(value); cueBytes += bytes(value)
            while (cueBytes > 32L * 1024 * 1024 || cues.values.sumOf { it.size } > 20_000) {
                val victim = cues.entries.filter { !it.key.startsWith("external:") && it.value.isNotEmpty() }.minByOrNull { it.value.first().startTimeUs } ?: break
                cueBytes -= bytes(victim.value.removeAt(0))
            }
        }
        requestFrame(timeMs.get())
    }
    fun externalAss(id: String, data: ByteArray, ready: () -> Unit) = submit {
        tracks.remove(id)?.release()
        tracks[id] = ass().createTrack().also { it.readBuffer(data) }
        post { if (!closed.get()) ready() }
    }
    fun externalText(id: String, values: List<CuesWithTiming>, ready: () -> Unit) = submit {
        val size = values.sumOf(::bytes)
        check(values.size <= 20_000 && size <= 32L * 1024 * 1024)
        check(cueBytes + size <= 32L * 1024 * 1024 && cues.values.sumOf { it.size } + values.size <= 20_000)
        // Attach only a fully parsed file. A partial/failed read never becomes
        // a selected track, and whole-file seek does not silently lose old cues.
        cues[id] = values.toMutableList(); cueBytes += size
        post { if (!closed.get()) ready() }
    }
    fun select(assTrack: String?, textTrack: String?, positionMs: Long) {
        if (assId == assTrack && textId == textTrack) { requestFrame(positionMs); return }
        assId = assTrack; textId = textTrack
        selection.incrementAndGet(); frame = null; showText(emptyList()); invalidate()
        requestFrame(positionMs)
    }
    fun requestFrame(positionMs: Long) {
        timeMs.set(positionMs); revision.incrementAndGet()
        if (closed.get() || !queued.compareAndSet(false, true)) return
        submit {
            val request = revision.get(); val epoch = selection.get()
            val at = timeMs.get() - delayMs
            try {
                val selectedText = textId
                val available = cues[selectedText].orEmpty()
                val lastIndefinite = available.filter { it.durationUs == C.TIME_UNSET && it.startTimeUs <= at * 1000 }.maxByOrNull { it.startTimeUs }
                val text = if (at < 0) emptyList() else available.filter {
                    it.startTimeUs <= at * 1000 && (if (it.durationUs == C.TIME_UNSET) it === lastIndefinite else at * 1000 < it.endTimeUs)
                }.flatMap { it.cues }
                val track = tracks[assId]
                val output = if (track == null || at < 0 || frameWidth <= 0 || frameHeight <= 0) null else {
                    val render = renderer ?: ass().createRender().also { renderer = it; it.setCacheLimit(1000, 64) }
                    if (lastTrack !== track) { lastNative = null; lastTrack = track }
                    render.setStorageSize(storageWidth.takeIf { it > 0 } ?: frameWidth, storageHeight.takeIf { it > 0 } ?: frameHeight)
                    render.setFrameSize(frameWidth, frameHeight); render.setTrack(track)
                    val next = render.renderFrame(at, AssTexType.BITMAP_RGBA)
                    if (next == null || next.changed != 0) lastNative = next
                    lastNative
                }
                post {
                    if (!closed.get() && selection.get() == epoch) {
                        frame = output
                        if (selectedText != null) showText(text)
                        invalidate()
                    }
                }
            } finally {
                queued.set(false)
                if (!closed.get() && revision.get() != request) requestFrame(timeMs.get())
            }
        }
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        frameWidth = w; frameHeight = h; requestFrame(timeMs.get())
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        frame?.images?.forEach { entry -> entry.bitmap?.let { canvas.drawBitmap(it, entry.x.toFloat(), entry.y.toFloat(), paint) } }
    }
    fun close() = synchronized(submissions) {
        if (!closed.compareAndSet(false, true)) return@synchronized
        selection.incrementAndGet(); frame = null; invalidate()
        worker.execute {
            renderer?.release(); tracks.values.forEach { it.release() }; library?.release()
            tracks.clear(); cues.clear(); headers.clear(); lastNative = null
        }
        worker.shutdown()
    }
    class Clock(private val layer: MediaSubtitleLayer) : NoSampleRenderer() {
        private var offsetUs = 0L
        override fun getName() = "FilewaySubtitleClock"
        override fun onRendererOffsetChanged(offsetUs: Long) { this.offsetUs = offsetUs }
        override fun render(positionUs: Long, elapsedRealtimeUs: Long) = layer.requestFrame((positionUs - offsetUs) / 1000)
        override fun onPositionReset(positionUs: Long, joining: Boolean, sampleStreamIsResetToKeyFrame: Boolean) = layer.requestFrame((positionUs - offsetUs) / 1000)
    }
}
