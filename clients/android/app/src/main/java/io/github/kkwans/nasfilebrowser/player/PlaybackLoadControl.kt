package io.github.kkwans.nasfilebrowser.player

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl

/** Observe the actual startup/rebuffer gate without changing Media3's loading decisions.
 * All product media URLs are HTTP broker leases, so streaming defaults apply. */
@androidx.annotation.OptIn(UnstableApi::class)
internal class PlaybackLoadControl : DefaultLoadControl() {
    @Volatile var percent: Int = 0
        private set
    fun resetProgress() { percent = 0 }
    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean {
        val ready = super.shouldStartPlayback(parameters)
        var requiredUs = 1000L * if (parameters.rebuffering) DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS else DEFAULT_BUFFER_FOR_PLAYBACK_MS
        if (parameters.targetLiveOffsetUs != C.TIME_UNSET) requiredUs = minOf(requiredUs, parameters.targetLiveOffsetUs / 2)
        val bufferedPlayoutUs = parameters.bufferedDurationUs / parameters.playbackSpeed.toDouble()
        percent = if (ready || requiredUs <= 0) 100 else (100 * bufferedPlayoutUs / requiredUs).toInt().coerceIn(0, 99)
        return ready
    }
}
