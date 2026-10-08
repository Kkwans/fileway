package io.github.kkwans.nasfilebrowser

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.MotionEvent
import android.view.Window
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/** Opt-in fixture diagnostics, including ActivityScenario launch and teardown.
 * Records only lifecycle, window identity and touch geometry, never user content.
 */
internal class OwnedUiTraceRule : TestRule {
    override fun apply(base: Statement, description: Description) = object : Statement() {
        override fun evaluate() {
            if (!enabled()) { base.evaluate(); return }
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val app = instrumentation.targetContext.applicationContext as Application
            val windows = mutableMapOf<Activity, Window.Callback>()
            fun stage(activity: Activity, value: String) {
                trace("$value activity=${activity.javaClass.simpleName}@${System.identityHashCode(activity)} task=${activity.taskId} focus=${activity.hasWindowFocus()} finishing=${activity.isFinishing}")
            }
            val callbacks = object : Application.ActivityLifecycleCallbacks {
                override fun onActivityCreated(activity: Activity, state: Bundle?) {
                    stage(activity, "created")
                    val original = activity.window.callback ?: return
                    windows[activity] = original
                    activity.window.callback = object : Window.Callback by original {
                        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                            val handled = original.dispatchTouchEvent(event)
                            if (event.actionMasked in setOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL)) {
                                stage(activity, "touch=${event.actionMasked} x=${event.x.toInt()} y=${event.y.toInt()} handled=$handled")
                            }
                            return handled
                        }
                    }
                }
                override fun onActivityStarted(activity: Activity) = stage(activity, "started")
                override fun onActivityResumed(activity: Activity) = stage(activity, "resumed")
                override fun onActivityPaused(activity: Activity) = stage(activity, "paused")
                override fun onActivityStopped(activity: Activity) = stage(activity, "stopped")
                override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = stage(activity, "saved")
                override fun onActivityDestroyed(activity: Activity) {
                    stage(activity, "destroyed")
                    windows.remove(activity)?.let { activity.window.callback = it }
                }
            }
            instrumentation.runOnMainSync { app.registerActivityLifecycleCallbacks(callbacks) }
            try { base.evaluate() }
            finally {
                instrumentation.runOnMainSync {
                    app.unregisterActivityLifecycleCallbacks(callbacks)
                    windows.forEach { (activity, original) -> activity.window.callback = original }
                    windows.clear()
                }
            }
        }
    }
    companion object {
        private fun enabled() = InstrumentationRegistry.getArguments().getString("nfbTraceUi") == "true"
        fun trace(value: String) {
            if (enabled()) InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
                putString("stream", "OWNED_UI_TRACE $value\n")
            })
        }
    }
}
