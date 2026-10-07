package io.github.kkwans.nasfilebrowser.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.abs

/** Vertical-only ownership after slop; edges, horizontal motion and multiple pointers are left alone. */
internal fun Modifier.playerVerticalGestures(key: Any?, enabled: Boolean, edge: Float,
    start: (left: Boolean) -> Unit, change: (fraction: Float) -> Unit, finish: () -> Unit): Modifier =
    composed {
    val currentStart by rememberUpdatedState(start)
    val currentChange by rememberUpdatedState(change)
    val currentFinish by rememberUpdatedState(finish)
    pointerInput(key, enabled, edge) {
        if (!enabled) return@pointerInput
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (down.position.x < edge || down.position.x > size.width - edge ||
                down.position.y < edge || down.position.y > size.height - edge) return@awaitEachGesture
            var dragging = false
            try {
                while (true) {
                    val event = awaitPointerEvent()
                    if (event.changes.count { it.pressed } > 1) break
                    val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!pointer.pressed || pointer.isConsumed) break
                    val distance = pointer.position - down.position
                    if (!dragging) {
                        if (abs(distance.x) > viewConfiguration.touchSlop && abs(distance.x) >= abs(distance.y)) break
                        if (abs(distance.y) <= viewConfiguration.touchSlop) continue
                        dragging = true
                        currentStart(down.position.x < size.width / 2f)
                    }
                    pointer.consume()
                    currentChange(-distance.y / size.height.coerceAtLeast(1))
                }
            } finally { if (dragging) currentFinish() }
        }
    }
    }
