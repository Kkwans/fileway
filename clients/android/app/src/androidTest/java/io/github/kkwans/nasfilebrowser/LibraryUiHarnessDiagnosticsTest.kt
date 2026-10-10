package io.github.kkwans.nasfilebrowser

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
internal class LibraryUiHarnessDiagnosticsTest : LibraryUiHarness() {
    @Test fun missingControlWithoutBoundModelRetainsFailureAndObservesTheSameActivity() {
        // Like AppUpdateUiTest's pure setContent case, no ClientModel is bound.
        var originalActivity = 0
        activity.scenario.onActivity {
            originalActivity = System.identityHashCode(it)
            it.setContent { Text("Owned diagnostics content") }
        }
        val failure = runCatching { text("Owned absent control") }.exceptionOrNull()
        assertTrue("A missing control must still fail", failure is IllegalStateException)
        val message = failure!!.message.orEmpty()
        assertTrue(message, message.startsWith("Missing visible action Owned absent control;"))
        assertTrue(message, message.contains("model=uninitialized"))
        assertTrue(message, message.contains("foregroundPackage="))
        assertTrue(message, message.contains("scenario="))
        assertTrue(message, message.contains("testActivities="))
        assertTrue(message, message.contains("sampledAtUptimeMs="))
        assertTrue(message, message.contains("ageMs="))
        assertTrue(message, message.contains("MainActivity@$originalActivity"))
        activity.scenario.onActivity { assertEquals(originalActivity, System.identityHashCode(it)) }
    }

    @Test fun missingDiagnosticReturnsWhileMainQueueIsStillNonIdle() {
        var originalActivity = 0
        activity.scenario.onActivity {
            originalActivity = System.identityHashCode(it)
            it.setContent { Text("Owned non-idle diagnostics content") }
        }
        val handler = Handler(Looper.getMainLooper())
        val stop = AtomicBoolean(false)
        val pumping = AtomicBoolean(false)
        val ready = CountDownLatch(1)
        // Finite even on the old diagnostic path: no worker/thread is escaped,
        // and main keeps servicing messages while never reaching an idle gap.
        val deadline = SystemClock.uptimeMillis() + 3_000
        val pump = object : Runnable {
            override fun run() {
                if (stop.get() || SystemClock.uptimeMillis() >= deadline) { pumping.set(false); return }
                pumping.set(true)
                handler.post(this)
                ready.countDown()
            }
        }
        handler.post(pump)
        try {
            assertTrue("Owned non-idle queue must start", ready.await(2, TimeUnit.SECONDS))
            // Reflect the real missing() path so this test can also exercise its
            // original private implementation in a controlled RED test APK.
            val method = LibraryUiHarness::class.java.getDeclaredMethod("missing", String::class.java).apply { isAccessible = true }
            val failure = try {
                method.invoke(this, "Owned absent control during non-idle queue")
                error("Missing diagnostic unexpectedly succeeded")
            } catch (error: InvocationTargetException) { error.targetException }
            val returnedBeforeIdle = pumping.get() && SystemClock.uptimeMillis() < deadline
            assertTrue("Diagnostic waited for the owned queue to become idle", returnedBeforeIdle)
            assertTrue(failure is IllegalStateException)
            val message = failure.message.orEmpty()
            assertTrue(message, message.startsWith("Missing visible action Owned absent control during non-idle queue;"))
            assertTrue(message, message.contains("model=uninitialized"))
            assertTrue(message, message.contains("MainActivity@$originalActivity"))
        } finally {
            stop.set(true)
            handler.removeCallbacks(pump)
        }
        activity.scenario.onActivity { assertEquals(originalActivity, System.identityHashCode(it)) }
    }
}
