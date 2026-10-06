package io.github.kkwans.nasfilebrowser.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** Device preference; no account, token or playback snapshot is stored here. */
class PlaybackPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("playback_preferences", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(prefs.getFloat("hold_rate", 3f).takeIf { it.isFinite() && it in .1f..5f } ?: 3f)
    val holdRate = mutable.asStateFlow()
    suspend fun saveHoldRate(value: Float) {
        require(value.isFinite() && value in .1f..5f)
        withContext(Dispatchers.IO) { check(prefs.edit().putFloat("hold_rate", value).commit()) { "无法保存长按倍速" } }
        mutable.value = value
    }
}

/** Restore the rate that was active before holding, including a custom rate. */
class HeldPlaybackRate(private val read: () -> Float, private val apply: (Float) -> Unit) {
    private var previous: Float? = null
    fun start(rate: Float) {
        if (previous != null || !rate.isFinite() || rate !in .1f..5f) return
        previous = read()
        apply(rate)
    }
    fun release() { val value = previous ?: return; previous = null; apply(value) }
}

fun parsePlaybackRate(text: String): Float? = text.trim().replace(',', '.').toFloatOrNull()?.takeIf { it.isFinite() && it in .1f..5f }
