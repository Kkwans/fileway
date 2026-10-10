package io.github.kkwans.nasfilebrowser.ui

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import java.util.Date
import kotlinx.coroutines.delay

/** The device's own clock and 12/24-hour preference, refreshed on resume. */
@Composable
internal fun rememberPlayerClock(): String {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    fun now() = DateFormat.getTimeFormat(context).format(Date())
    val clock by produceState(now(), context, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                value = now()
                delay(60_000L - System.currentTimeMillis().mod(60_000L))
            }
        }
    }
    return clock
}
