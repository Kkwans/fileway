package io.github.kkwans.nasfilebrowser

import android.app.Instrumentation
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import org.junit.Assert.assertTrue

/** The same checked real-pointer path for fullscreen and panel controls. */
internal object OwnedTouchInput {
    fun tap(
        instrumentation: Instrumentation,
        bounds: Rect,
        toolType: Int = MotionEvent.TOOL_TYPE_FINGER,
        tracePrefix: String = "owned",
        rejected: (String) -> Unit = {},
        afterDown: () -> Unit = {},
    ) {
        val downTime = SystemClock.uptimeMillis()
        fun inject(action: Int): Boolean {
            val properties = MotionEvent.PointerProperties().apply { id = 0; this.toolType = toolType }
            val coordinates = MotionEvent.PointerCoords().apply {
                x = bounds.centerX().toFloat(); y = bounds.centerY().toFloat()
                pressure = 1f; size = 1f
            }
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, 1,
                arrayOf(properties), arrayOf(coordinates), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            OwnedUiTraceRule.trace("$tracePrefix-event action=$action tool=${event.getToolType(0)} " +
                "source=${event.source} device=${event.deviceId} pressure=${event.getPressure(0)} flags=${event.flags}")
            return try { instrumentation.uiAutomation.injectInputEvent(event, true) }
            finally { event.recycle() }
        }
        val down = inject(MotionEvent.ACTION_DOWN)
        if (!down) rejected("DOWN")
        assertTrue("Owned tap DOWN must be injected at $bounds", down)
        afterDown()
        var released = false
        try {
            SystemClock.sleep(100)
            released = inject(MotionEvent.ACTION_UP)
            if (!released) rejected("UP")
            assertTrue("Owned tap UP must be injected at $bounds", released)
        } finally { if (!released) inject(MotionEvent.ACTION_CANCEL) }
    }
}
