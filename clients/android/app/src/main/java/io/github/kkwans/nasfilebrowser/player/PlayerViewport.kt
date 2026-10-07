package io.github.kkwans.nasfilebrowser.player

import android.content.Context
import android.view.Gravity
import android.view.SurfaceView
import android.widget.FrameLayout
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.SubtitleView

/** Stable native video Surface plus independent text/ASS layers; HUD changes cannot resize them. */
@androidx.annotation.OptIn(UnstableApi::class)
class PlayerViewport(context: Context) : FrameLayout(context) {
    private val content = AspectRatioFrameLayout(context).apply { setAspectRatio(16f / 9f) }
    internal val video = SurfaceView(context)
    internal val text = SubtitleView(context)
    private var subtitles: MediaSubtitleLayer? = null
    init {
        addView(content, LayoutParams(-1, -1, Gravity.CENTER))
        content.addView(video, LayoutParams(-1, -1)); content.addView(text, LayoutParams(-1, -1))
    }
    internal fun subtitles(layer: MediaSubtitleLayer?) {
        subtitles?.let { content.removeView(it) }
        subtitles = layer
        layer?.let {
            (it.parent as? android.view.ViewGroup)?.removeView(it)
            content.addView(it, LayoutParams(-1, -1))
            it.showText = { cues -> text.setCues(cues) }
        }
        text.setCues(emptyList())
    }
    internal fun videoSize(width: Int, height: Int, pixelRatio: Float) {
        if (width > 0 && height > 0) content.setAspectRatio(width * pixelRatio / height)
        subtitles?.let { it.storageWidth = width; it.storageHeight = height }
    }
}
