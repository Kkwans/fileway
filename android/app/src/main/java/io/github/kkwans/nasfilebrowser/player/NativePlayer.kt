package io.github.kkwans.nasfilebrowser.player

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.util.VLCVideoLayout

data class NativeTrack(val id: Int, val title: String, val codec: String = "", val language: String = "")
data class PlayerState(
    val phase: String = "准备播放", val playing: Boolean = false, val positionMs: Long = 0,
    val durationMs: Long = 0, val buffering: Float = 0f, val seekable: Boolean = false,
    val audio: List<NativeTrack> = emptyList(), val subtitles: List<NativeTrack> = emptyList(),
    val selectedAudio: Int = -1, val selectedSubtitle: Int = -1, val width: Int = 0, val height: Int = 0,
    val error: String? = null, val rate: Float = 1f, val volume: Int = 100,
)

class NativePlayer(context: Context) {
    private val vlc = LibVLC(context.applicationContext, arrayListOf("--audio-time-stretch", "--no-video-title-show"))
    private val player = MediaPlayer(vlc)
    private val mutable = MutableStateFlow(PlayerState())
    val state = mutable.asStateFlow()
    var checkpoint: (() -> Unit)? = null
    private var attached = false
    private var requested = false
    private var released = false
    private var seekTarget: Long? = null
    private var resumeTarget: Long? = null

    init {
        // A portrait page embeds a landscape video viewport. libVLC's default
        // activity-orientation heuristic swaps those bounds and shrinks video.
        player.setUseOrientationFromBounds(true)
        player.setEventListener { event ->
            if (released) return@setEventListener
            val old = mutable.value
            mutable.value = when (event.type) {
                MediaPlayer.Event.Opening -> old.copy(phase = "正在打开视频", error = null)
                MediaPlayer.Event.Buffering -> old.copy(buffering = event.buffering, phase = if (event.buffering < 100) "正在缓冲" else if (old.playing) "正在播放" else "已暂停")
                MediaPlayer.Event.Playing -> old.copy(playing = true, phase = "正在播放", error = null)
                MediaPlayer.Event.Paused -> old.copy(playing = false, phase = "已暂停")
                MediaPlayer.Event.TimeChanged -> {
                    val reached = seekTarget?.let { kotlin.math.abs(event.timeChanged - it) <= 1500 } == true
                    if (reached) seekTarget = null
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
        if (attached) player.detachViews()
        player.attachViews(view, null, true, false)
        attached = true
        if (requested) player.play()
    }
    fun detach() { if (!released && attached) { player.detachViews(); attached = false } }
    fun open(url: String, positionMs: Long = 0, autoplay: Boolean = true) {
        if (released) return
        player.stop()
        seekTarget = null
        resumeTarget = positionMs.takeIf { it > 0 }
        mutable.value = PlayerState(phase = "正在打开视频", volume = player.volume.takeIf { it >= 0 }?.coerceAtMost(100) ?: mutable.value.volume)
        player.rate = 1f
        val media = Media(vlc, Uri.parse(url))
        media.setHWDecoderEnabled(true, false)
        media.addOption(":network-caching=1500")
        player.media = media
        media.release()
        requested = autoplay
        if (attached && requested) player.play()
    }
    fun toggle() {
        if (mutable.value.playing) pause() else { requested = true; if (attached) player.play() }
    }
    fun pause() { requested = false; if (!released) player.pause() }
    fun seek(position: Long) {
        if (mutable.value.seekable) {
            val target = position.coerceIn(0, mutable.value.durationMs.coerceAtLeast(0))
            if (player.setTime(target, false) < 0) { mutable.value = mutable.value.copy(error = "这个视频暂时无法跳转"); return }
            seekTarget = target
            mutable.value = mutable.value.copy(phase = "正在跳转")
        }
    }
    fun rate(value: Float) { player.rate = value; mutable.value = mutable.value.copy(rate = player.rate) }
    fun volume(value: Int) {
        if (player.setVolume(value.coerceIn(0, 100)) < 0) mutable.value = mutable.value.copy(error = "无法调整播放器音量，请重试")
        else mutable.value = mutable.value.copy(volume = player.volume.coerceIn(0, 100))
    }
    fun audio(id: Int) { if (player.setAudioTrack(id)) refreshTracks() }
    fun subtitle(id: Int) { if (player.setSpuTrack(id)) refreshTracks() }
    fun stop() { requested = false; resumeTarget = null; if (!released) player.stop(); mutable.value = PlayerState() }

    private fun refreshTracks() {
        val media = player.media
        val tracks = try { if (media == null) emptyList() else (0 until media.trackCount).mapNotNull { media.getTrack(it) } } finally { media?.release() }
        fun describe(items: Array<MediaPlayer.TrackDescription>?, type: Int) = items.orEmpty().map { item ->
            val track = tracks.firstOrNull { it.type == type && it.id == item.id }
            NativeTrack(item.id, if (item.id == -1) "关闭" else item.name.orEmpty().ifBlank { "轨道 ${item.id}" }, track?.codec.orEmpty(), track?.language.orEmpty())
        }
        val video = tracks.filterIsInstance<IMedia.VideoTrack>().firstOrNull()
        mutable.value = mutable.value.copy(
            audio = describe(player.audioTracks, IMedia.Track.Type.Audio), subtitles = describe(player.spuTracks, IMedia.Track.Type.Text),
            selectedAudio = player.audioTrack, selectedSubtitle = player.spuTrack,
            width = video?.width ?: 0, height = video?.height ?: 0,
            volume = player.volume.takeIf { it >= 0 }?.coerceAtMost(100) ?: mutable.value.volume,
        )
    }
    fun release() {
        if (released) return
        player.setEventListener(null)
        detach(); player.stop(); player.release(); vlc.release(); released = true
    }
}
