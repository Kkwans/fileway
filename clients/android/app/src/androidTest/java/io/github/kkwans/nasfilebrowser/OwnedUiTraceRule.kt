package io.github.kkwans.nasfilebrowser

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.Window
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.util.concurrent.atomic.AtomicReference

/** Main-callback snapshots, including ActivityScenario launch and teardown.
 * Logging is opt-in. Cache reads never await main/idle or query accessibility.
 * Records only lifecycle, window identity and touch geometry, never user content.
 */
internal class OwnedUiTraceRule : TestRule {
    private data class Snapshot(val uptime: Long, val value: String)
    private data class Case(val token: Any, val id: String, val snapshot: Snapshot? = null)
    private data class WindowHook(val original: Window.Callback, val installed: Window.Callback)
    private val current = AtomicReference<Case?>()

    fun snapshot(): String {
        val observed = current.get() ?: return "case=unverified; testActivities=unverified"
        val snapshot = observed.snapshot ?: return "case=${observed.id}; testActivities=unverified"
        return "case=${observed.id}; sampledAtUptimeMs=${snapshot.uptime}; ageMs=${SystemClock.uptimeMillis() - snapshot.uptime}; testActivities=[${snapshot.value}]"
    }

    override fun apply(base: Statement, description: Description) = object : Statement() {
        override fun evaluate() {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val app = instrumentation.targetContext.applicationContext as Application
            val token = Any()
            val id = "${description.className}.${description.methodName}@${System.identityHashCode(token)}"
            current.set(Case(token, id))
            val windows = mutableMapOf<Activity, WindowHook>() // Accessed only by main callbacks/posted cleanup.
            fun stage(activity: Activity, value: String) {
                if (current.get()?.token !== token) return
                val decor = activity.window.decorView
                val snapshot = Snapshot(SystemClock.uptimeMillis(),
                    "$value activity=${activity.javaClass.simpleName}@${System.identityHashCode(activity)} task=${activity.taskId} " +
                        "focus=${activity.hasWindowFocus()} finishing=${activity.isFinishing} destroyed=${activity.isDestroyed} " +
                        "attached=${decor.isAttachedToWindow} shown=${decor.isShown} visibility=${decor.windowVisibility} size=${decor.width}x${decor.height}")
                while (true) {
                    val before = current.get() ?: return
                    if (before.token !== token) return
                    if (current.compareAndSet(before, before.copy(snapshot = snapshot))) break
                }
                trace("case=$id sampledAtUptimeMs=${snapshot.uptime} ${snapshot.value}")
            }
            fun restore(activity: Activity, hook: WindowHook) {
                if (activity.window.callback === hook.installed) activity.window.callback = hook.original
            }
            val callbacks = object : Application.ActivityLifecycleCallbacks {
                override fun onActivityCreated(activity: Activity, state: Bundle?) {
                    if (current.get()?.token !== token) return
                    stage(activity, "created")
                    val original = activity.window.callback ?: return
                    val installed = object : Window.Callback by original {
                        override fun onWindowFocusChanged(hasFocus: Boolean) {
                            original.onWindowFocusChanged(hasFocus)
                            stage(activity, "window-focus=$hasFocus")
                        }
                        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                            val handled = original.dispatchTouchEvent(event)
                            if (event.actionMasked in setOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL)) {
                                stage(activity, "touch=${event.actionMasked} x=${event.x.toInt()} y=${event.y.toInt()} handled=$handled")
                            }
                            return handled
                        }
                    }
                    windows[activity] = WindowHook(original, installed)
                    activity.window.callback = installed
                }
                override fun onActivityStarted(activity: Activity) = stage(activity, "started")
                override fun onActivityResumed(activity: Activity) = stage(activity, "resumed")
                override fun onActivityPaused(activity: Activity) = stage(activity, "paused")
                override fun onActivityStopped(activity: Activity) = stage(activity, "stopped")
                override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = stage(activity, "saved")
                override fun onActivityDestroyed(activity: Activity) {
                    stage(activity, "destroyed")
                    windows.remove(activity)?.let { restore(activity, it) }
                }
            }
            // Application registers/removes callbacks under its own list lock;
            // it has no main-thread requirement. Do not wait for main here.
            app.registerActivityLifecycleCallbacks(callbacks)
            try { base.evaluate() }
            finally {
                while (true) {
                    val before = current.get() ?: break
                    if (before.token !== token || current.compareAndSet(before, null)) break
                }
                app.unregisterActivityLifecycleCallbacks(callbacks)
                Handler(Looper.getMainLooper()).post {
                    windows.forEach { (activity, hook) -> restore(activity, hook) }
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
