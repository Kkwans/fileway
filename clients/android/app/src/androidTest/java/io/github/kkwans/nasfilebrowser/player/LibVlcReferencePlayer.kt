package io.github.kkwans.nasfilebrowser.player

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import io.github.kkwans.nasfilebrowser.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.util.VLCVideoLayout

class LibVlcReferencePlayer(context: Context) {
    private val trace = PlaybackTrace(SystemClock::elapsedRealtime, enabled = BuildConfig.DEBUG)
    private val vlc = LibVLC(context.applicationContext, arrayListOf("--audio-time-stretch", "--no-video-title-show").apply {
        if (BuildConfig.DEBUG && BuildConfig.NATIVE_VERBOSE) add("--verbose=2")
    }).also { trace.record(PlaybackTraceAction.ENGINE_READY) }
    private val player = MediaPlayer(vlc).also { trace.record(PlaybackTraceAction.PLAYER_READY) }
    private val mutable = MutableStateFlow(PlayerState())
    val state = mutable.asStateFlow()
    var checkpoint: (() -> Unit)? = null
    private var attached = false
    private val session = PlaybackSession()
    private var released = false
    private var seekTarget: Long? = null
    private var resumeTarget: Long? = null
    private val externalSubtitleLabels = mutableMapOf<Int, String>()
    private var pendingSubtitle: Pair<String, Set<Int>>? = null
    private var hasPlaybackClock = false
    private var bufferBucket = -1
    private var temporaryRate: Float? = null
    val canAddExternalSubtitle get() = !released && hasPlaybackClock
    fun diagnosticSnapshot(): List<PlaybackTraceEntry> = trace.snapshot()

    init {
        // A portrait page embeds a landscape video viewport. libVLC's default
        // activity-orientation heuristic swaps those bounds and shrinks video.
        player.setUseOrientationFromBounds(true)
    }

    private fun bindEvents(epoch: Long) {
        player.setEventListener { event ->
            if (released || !session.accepts(epoch)) return@setEventListener
            if (event.type == MediaPlayer.Event.Playing && !session.wantsPlay) {
                player.pause()
                mutable.value = mutable.value.copy(playing = false, phase = if (mutable.value.phase == "播放完毕") "播放完毕" else "已暂停")
                return@setEventListener
            }
            if (event.type == MediaPlayer.Event.EndReached || event.type == MediaPlayer.Event.EncounteredError) session.pause()
            val targetRate = temporaryRate ?: session.preferredRate
            if (event.type == MediaPlayer.Event.Playing && kotlin.math.abs(player.rate - targetRate) > .001f) {
                applyRate(targetRate)
            }
            when (event.type) {
                MediaPlayer.Event.Opening -> trace.record(PlaybackTraceAction.OPENING)
                MediaPlayer.Event.Buffering -> {
                    val bucket = event.buffering.toInt() / 25
                    if (bucket != bufferBucket) { bufferBucket = bucket; trace.record(PlaybackTraceAction.BUFFERING, event.buffering.toDouble()) }
                }
                MediaPlayer.Event.Playing -> trace.record(PlaybackTraceAction.PLAYING)
                MediaPlayer.Event.Paused -> trace.record(PlaybackTraceAction.PAUSED)
                MediaPlayer.Event.Vout -> trace.record(PlaybackTraceAction.VIDEO_OUTPUT)
                MediaPlayer.Event.EndReached -> trace.record(PlaybackTraceAction.ENDED)
                MediaPlayer.Event.EncounteredError -> trace.record(PlaybackTraceAction.ERROR)
            }
            val old = mutable.value
            mutable.value = when (event.type) {
                MediaPlayer.Event.Opening -> old.copy(phase = "正在打开视频", error = null)
                MediaPlayer.Event.Buffering -> old.copy(buffering = event.buffering, phase = if (old.phase == "播放完毕" || old.error != null) old.phase else if (event.buffering < 100) "正在缓冲" else if (old.playing) "正在播放" else "已暂停")
                MediaPlayer.Event.Playing -> old.copy(playing = true, phase = "正在播放", error = null)
                MediaPlayer.Event.Paused -> old.copy(playing = false, phase = "已暂停")
                MediaPlayer.Event.TimeChanged -> {
                    if (event.timeChanged > 0 && !hasPlaybackClock) trace.record(PlaybackTraceAction.FIRST_CLOCK, event.timeChanged.toDouble())
                    if (event.timeChanged > 0) hasPlaybackClock = true
                    val reached = seekTarget?.let { kotlin.math.abs(event.timeChanged - it) <= 1500 } == true
                    if (reached) { trace.record(PlaybackTraceAction.SEEK_REACHED, event.timeChanged.toDouble()); seekTarget = null }
                    old.copy(positionMs = event.timeChanged.coerceAtLeast(0), phase = if (reached) { if (old.playing) "正在播放" else "已暂停" } else old.phase)
                }
                MediaPlayer.Event.LengthChanged -> old.copy(durationMs = event.lengthChanged.coerceAtLeast(0))
                MediaPlayer.Event.SeekableChanged -> old.copy(seekable = event.seekable)
                MediaPlayer.Event.EndReached -> old.copy(playing = false, positionMs = old.durationMs.takeIf { it > 0 } ?: old.positionMs, phase = "播放完毕")
                MediaPlayer.Event.EncounteredError -> old.copy(playing = false, phase = "无法播放", error = "视频读取或解码失败，可重试或选择其他音轨。")
                else -> old
            }
            if (event.type in listOf(MediaPlayer.Event.ESAdded, MediaPlayer.Event.ESDeleted, MediaPlayer.Event.ESSelected, MediaPlayer.Event.Playing, MediaPlayer.Event.Vout)) refreshTracks()
            val resume = resumeTarget
            // Seekability describes the input, not a ready decoder/output.
            // Resume only after the first advancing playback clock event.
            if (resume != null && event.type == MediaPlayer.Event.TimeChanged && event.timeChanged > 0 && mutable.value.playing && mutable.value.seekable && mutable.value.durationMs > 0) {
                resumeTarget = null
                seek(resume)
            }
            if (event.type == MediaPlayer.Event.Paused || event.type == MediaPlayer.Event.EndReached) checkpoint?.invoke()
        }
    }

    fun attach(view: VLCVideoLayout) {
        if (released) return
        trace.record(PlaybackTraceAction.ATTACH)
        if (attached) player.detachViews()
        player.attachViews(view, null, true, false)
        attached = true
        if (session.wantsPlay) { trace.record(PlaybackTraceAction.PLAY_REQUEST); player.play() }
    }
    fun detach() { if (!released && attached) { trace.record(PlaybackTraceAction.DETACH); player.detachViews(); attached = false } }
    fun open(url: String, positionMs: Long = 0, autoplay: Boolean = true) {
        if (released) return
        val epoch = session.open(autoplay)
        temporaryRate = null
        // VLCObject removes queued Java event callbacks when replacing its listener.
        // Unbind before stopping the old input, then bind a generation-specific listener.
        player.setEventListener(null)
        trace.beginOpen(positionMs)
        trace.record(PlaybackTraceAction.STOP_REQUEST)
        player.stop()
        trace.record(PlaybackTraceAction.STOP_RETURNED)
        bufferBucket = -1
        externalSubtitleLabels.clear(); pendingSubtitle = null; hasPlaybackClock = false
        seekTarget = null
        resumeTarget = positionMs.takeIf { it > 0 }
        mutable.value = PlayerState(phase = if (autoplay) "正在打开视频" else "已暂停", volume = player.volume.takeIf { it >= 0 }?.coerceAtMost(100) ?: mutable.value.volume,
            rate = session.preferredRate, mediaGeneration = epoch)
        bindEvents(epoch)
        val media = Media(vlc, Uri.parse(url))
        media.setHWDecoderEnabled(true, false)
        media.addOption(":network-caching=1500")
        player.media = media
        trace.record(PlaybackTraceAction.MEDIA_SET)
        media.release()
        if (attached && session.wantsPlay) { trace.record(PlaybackTraceAction.PLAY_REQUEST); player.play() }
    }
    fun toggle() {
        if (released || !session.active) return
        if (mutable.value.playing) pause() else { session.play(); if (attached) { trace.record(PlaybackTraceAction.PLAY_REQUEST); player.play() } }
    }
    fun pause() {
        session.pause()
        if (!released) {
            trace.record(PlaybackTraceAction.PAUSE_REQUEST); player.pause()
            if (temporaryRate != null) { temporaryRate = null; if (session.active && hasPlaybackClock) applyRate(session.preferredRate) }
        }
    }
    fun seek(position: Long) {
        if (!released && session.active && mutable.value.seekable) {
            val target = position.coerceIn(0, mutable.value.durationMs.coerceAtLeast(0))
            trace.record(PlaybackTraceAction.SEEK_REQUEST, target.toDouble())
            if (player.setTime(target, false) < 0) { mutable.value = mutable.value.copy(error = "这个视频暂时无法跳转"); return }
            trace.record(PlaybackTraceAction.SEEK_ACCEPTED, target.toDouble())
            seekTarget = target
            mutable.value = mutable.value.copy(phase = "正在跳转")
        }
    }
    fun rate(value: Float) {
        if (released || !session.selectRate(value)) return
        temporaryRate = null
        if (session.active && hasPlaybackClock) applyRate(value)
        else mutable.value = mutable.value.copy(rate = value)
    }
    fun beginTemporaryRate(value: Float): Long? {
        if (released || !session.active || !mutable.value.playing || !value.isFinite() || value !in .1f..5f) return null
        temporaryRate = value
        applyRate(value)
        return session.generation
    }
    fun restoreRate(epoch: Long) { if (!released && session.accepts(epoch)) { temporaryRate = null; applyRate(session.preferredRate) } }
    private fun applyRate(value: Float) {
        trace.record(PlaybackTraceAction.RATE_REQUEST, value.toDouble())
        player.rate = value
        mutable.value = mutable.value.copy(rate = player.rate)
        trace.record(PlaybackTraceAction.RATE_REPORTED, mutable.value.rate.toDouble())
    }
    fun volume(value: Int) {
        if (released) return
        if (player.setVolume(value.coerceIn(0, 100)) < 0) mutable.value = mutable.value.copy(error = "无法调整播放器音量，请重试")
        else mutable.value = mutable.value.copy(volume = player.volume.coerceIn(0, 100))
    }
    fun audio(id: Int) {
        if (released || !session.active) return
        trace.record(PlaybackTraceAction.AUDIO_REQUEST, id.toDouble())
        val accepted = player.setAudioTrack(id)
        trace.record(PlaybackTraceAction.AUDIO_ACCEPTED, if (accepted) 1.0 else 0.0)
        if (accepted) { mutable.value = mutable.value.copy(operationError = null); refreshTracks() }
        else mutable.value = mutable.value.copy(operationError = "这个音轨暂时无法启用，请选择其他音轨")
    }
    fun addSubtitle(url: String, name: String): Boolean {
        if (!canAddExternalSubtitle) return false
        trace.record(PlaybackTraceAction.EXTERNAL_SUBTITLE_REQUEST)
        val previous = pendingSubtitle
        pendingSubtitle = name to player.spuTracks.orEmpty().filter { it.id >= 0 }.map { it.id }.toSet()
        val accepted = player.addSlave(IMedia.Slave.Type.Subtitle, Uri.parse(url), true)
        if (!accepted) pendingSubtitle = previous else refreshTracks()
        return accepted
    }
    fun subtitle(id: Int) {
        if (released || !session.active) return
        trace.record(PlaybackTraceAction.SUBTITLE_REQUEST, id.toDouble())
        val accepted = player.setSpuTrack(id)
        trace.record(PlaybackTraceAction.SUBTITLE_ACCEPTED, if (accepted) 1.0 else 0.0)
        if (accepted) { mutable.value = mutable.value.copy(operationError = null); refreshTracks() }
        else mutable.value = mutable.value.copy(operationError = "字幕未能切换，请重试")
    }
    fun clearOperationError(expected: String) { if (mutable.value.operationError == expected) mutable.value = mutable.value.copy(operationError = null) }
    fun stop() {
        session.stop()
        temporaryRate = null
        externalSubtitleLabels.clear(); pendingSubtitle = null; hasPlaybackClock = false; resumeTarget = null; seekTarget = null
        if (!released) { player.setEventListener(null); trace.record(PlaybackTraceAction.STOP_REQUEST); player.stop(); trace.record(PlaybackTraceAction.STOP_RETURNED) }
        mutable.value = PlayerState(rate = session.preferredRate, mediaGeneration = session.generation)
    }

    private fun refreshTracks() {
        val media = player.media
        val tracks = try { if (media == null) emptyList() else (0 until media.trackCount).mapNotNull { media.getTrack(it) } } finally { media?.release() }
        val subtitleDescriptions = player.spuTracks
        val pending = pendingSubtitle
        if (pending != null) {
            val added = subtitleDescriptions.orEmpty().filter { it.id >= 0 && it.id !in pending.second }
            // A single-file addition is named only when exactly one new engine ID is observed.
            if (added.size == 1) { externalSubtitleLabels[added.single().id] = pending.first; pendingSubtitle = null }
            else if (added.size > 1) pendingSubtitle = null
        }
        externalSubtitleLabels.keys.retainAll(subtitleDescriptions.orEmpty().map { it.id }.toSet())
        fun describe(items: Array<MediaPlayer.TrackDescription>?, type: Int) = items.orEmpty().map { item ->
            val track = tracks.firstOrNull { it.type == type && it.id == item.id }
            NativeTrack(item.id, if (item.id == -1) "关闭" else (if (type == IMedia.Track.Type.Text) externalSubtitleLabels[item.id] else null) ?: item.name.orEmpty().ifBlank { "轨道 ${item.id}" }, track?.codec.orEmpty(), track?.language.orEmpty())
        }
        val video = tracks.filterIsInstance<IMedia.VideoTrack>().firstOrNull()
        val previous = mutable.value
        mutable.value = mutable.value.copy(
            audio = describe(player.audioTracks, IMedia.Track.Type.Audio), subtitles = describe(subtitleDescriptions, IMedia.Track.Type.Text),
            selectedAudio = player.audioTrack, selectedSubtitle = player.spuTrack,
            width = video?.width ?: 0, height = video?.height ?: 0,
            volume = player.volume.takeIf { it >= 0 }?.coerceAtMost(100) ?: mutable.value.volume,
        )
        if (previous.selectedAudio != mutable.value.selectedAudio) trace.record(PlaybackTraceAction.AUDIO_SELECTED, mutable.value.selectedAudio.toDouble())
        if (previous.selectedSubtitle != mutable.value.selectedSubtitle) trace.record(PlaybackTraceAction.SUBTITLE_SELECTED, mutable.value.selectedSubtitle.toDouble())
    }
    fun release() {
        if (released) return
        session.stop()
        trace.record(PlaybackTraceAction.RELEASE)
        player.setEventListener(null)
        detach(); player.stop(); player.release(); vlc.release(); released = true
    }
}
