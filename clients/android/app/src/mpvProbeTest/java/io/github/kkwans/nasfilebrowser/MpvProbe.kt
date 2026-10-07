package io.github.kkwans.nasfilebrowser

import android.view.SurfaceHolder
import android.view.SurfaceView
import dev.jdtech.mpv.MPVLib
import org.json.JSONObject

/** Fixture-only adapter; no product engine selection or preferences. Access only on Main. */
internal class MpvProbe(host: EngineProbeActivity) : EngineComparisonHarness.Probe, SurfaceHolder.Callback {
    override val view = SurfaceView(host)
    val player = requireNotNull(MPVLib.create(host)) { "mpv_create failed" }
    private var pending: String? = null
    private var attached = false
    private var closed = false

    init {
        try {
            for ((key, value) in mapOf(
                "config" to "no", "terminal" to "no", "idle" to "yes", "keep-open" to "yes",
                "osd-level" to "0", "osd-bar" to "no",
                "force-window" to "no", "vo" to "gpu", "gpu-context" to "android", "opengl-es" to "yes",
                "hwdec" to "mediacodec,mediacodec-copy", "ao" to "audiotrack,opensles",
                "audio-set-media-role" to "yes", "demuxer-max-bytes" to "67108864",
                "demuxer-max-back-bytes" to "67108864", "save-position-on-quit" to "no",
            )) check(player.setOptionString(key, value) >= 0) { "Unsupported mpv option: $key" }
            player.init()
            view.holder.addCallback(this)
        } catch (failure: Throwable) { player.destroy(); throw failure }
    }
    override val version get() = player.getPropertyString("mpv-version") ?: "UNKNOWN"
    override val position get() = ((player.getPropertyDouble("time-pos") ?: 0.0) * 1000).toLong()
    override val duration get() = ((player.getPropertyDouble("duration") ?: 0.0) * 1000).toLong()
    override val playing get() = player.getPropertyBoolean("pause") == false &&
        player.getPropertyBoolean("paused-for-cache") == false && player.getPropertyBoolean("idle-active") == false &&
        player.getPropertyBoolean("eof-reached") != true
    override val rate get() = (player.getPropertyDouble("speed") ?: 0.0).toFloat()
    // This wrapper exposes end-file event IDs but not error payloads. Baseline timeouts still fail.
    override val error get() = closed
    override fun open(url: String) { pending = url; loadPending() }
    private fun loadPending() {
        if (!attached || closed) return
        pending?.let { url ->
            pending = null
            player.setPropertyBoolean("pause", false)
            player.command(arrayOf("loadfile", url, "replace"))
        }
    }
    override fun seek(position: Long) = player.command(arrayOf("seek", (position / 1000.0).toString(), "absolute+exact"))
    override fun pause() = player.setPropertyBoolean("pause", true)
    override fun play() = player.setPropertyBoolean("pause", false)
    override fun speed(value: Float) = player.setPropertyDouble("speed", value.toDouble())
    override fun observations(): JSONObject = JSONObject().apply {
        if (closed) { put("released", true); return@apply }
        for (key in listOf("mpv-version", "ffmpeg-version", "hwdec-current", "video-codec", "audio-codec-name", "current-vo", "current-ao")) {
            put(key, player.getPropertyString(key) ?: JSONObject.NULL)
        }
        put("audioClockEvents", JSONObject.NULL)
        put("errorPayloadCapability", "UNAVAILABLE_IN_WRAPPER")
    }
    override fun surfaceCreated(holder: SurfaceHolder) {
        if (closed) return
        player.attachSurface(holder.surface)
        attached = true
        player.setPropertyString("force-window", "yes")
        player.setPropertyString("vo", "gpu")
        loadPending()
    }
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (!closed) player.setPropertyString("android-surface-size", "${width}x$height")
    }
    override fun surfaceDestroyed(holder: SurfaceHolder) {
        // The comparison owns a stable surface. Close fully before dropping its global reference;
        // it does not borrow upstream's unresolved asynchronous detach/rebind assumption.
        close()
    }
    override fun close() {
        if (closed) return
        closed = true; pending = null; attached = false
        view.holder.removeCallback(this)
        player.destroy()
    }
}
