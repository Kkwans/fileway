package io.github.kkwans.nasfilebrowser.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.abs
import kotlin.math.sign

private enum class PlayerDragAxis { HORIZONTAL, VERTICAL }

/** Lock one axis after slop. Controls, system edges, extra pointers and long
 * presses retain priority; only a normal single-pointer release commits seek.
 */
internal fun Modifier.playerGestures(key: Any?, enabled: Boolean, edge: Float,
    start: (left: Boolean) -> Unit, change: (fraction: Float) -> Unit, finish: () -> Unit,
    seekStart: () -> Boolean, seekChange: (fraction: Float) -> Unit, seekFinish: (released: Boolean) -> Unit): Modifier =
    composed {
    val currentStart by rememberUpdatedState(start)
    val currentChange by rememberUpdatedState(change)
    val currentFinish by rememberUpdatedState(finish)
    val currentSeekStart by rememberUpdatedState(seekStart)
    val currentSeekChange by rememberUpdatedState(seekChange)
    val currentSeekFinish by rememberUpdatedState(seekFinish)
    pointerInput(key, enabled, edge) {
        if (!enabled) return@pointerInput
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (down.position.x < edge || down.position.x > size.width - edge ||
                down.position.y < edge || down.position.y > size.height - edge) return@awaitEachGesture
            var axis: PlayerDragAxis? = null
            var released = false
            try {
                while (true) {
                    val event = awaitPointerEvent()
                    if (event.changes.count { it.pressed } > 1) break
                    val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (pointer.isConsumed) break
                    if (!pointer.pressed) { released = true; break }
                    val distance = pointer.position - down.position
                    if (axis == null) {
                        if (maxOf(abs(distance.x), abs(distance.y)) <= viewConfiguration.touchSlop) continue
                        if (abs(distance.x) >= abs(distance.y)) {
                            if (!currentSeekStart()) break
                            axis = PlayerDragAxis.HORIZONTAL
                        } else {
                            axis = PlayerDragAxis.VERTICAL
                            currentStart(down.position.x < size.width / 2f)
                        }
                    }
                    pointer.consume()
                    if (axis == PlayerDragAxis.HORIZONTAL) {
                        val movement = distance.x.sign * (abs(distance.x) - viewConfiguration.touchSlop).coerceAtLeast(0f)
                        currentSeekChange(movement / (size.width - 2 * edge).coerceAtLeast(1f))
                    } else currentChange(-distance.y / size.height.coerceAtLeast(1))
                }
            } finally {
                when (axis) {
                    PlayerDragAxis.HORIZONTAL -> currentSeekFinish(released)
                    PlayerDragAxis.VERTICAL -> currentFinish()
                    null -> Unit
                }
            }
        }
    }
    }
