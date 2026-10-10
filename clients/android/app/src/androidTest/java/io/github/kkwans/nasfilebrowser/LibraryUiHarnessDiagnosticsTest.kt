package io.github.kkwans.nasfilebrowser

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiObject2
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
internal class LibraryUiHarnessDiagnosticsTest : LibraryUiHarness() {
    private fun awaitOwnedWindowFocus() {
        val focused = CountDownLatch(1)
        val listener = android.view.ViewTreeObserver.OnWindowFocusChangeListener { if (it) focused.countDown() }
        lateinit var decor: android.view.View
        activity.scenario.onActivity {
            decor = it.window.decorView
            decor.viewTreeObserver.addOnWindowFocusChangeListener(listener)
            if (it.hasWindowFocus()) focused.countDown()
        }
        try { assertTrue("Owned input fixture must have window focus", focused.await(5, TimeUnit.SECONDS)) }
        finally { activity.scenario.onActivity {
            if (decor.viewTreeObserver.isAlive) decor.viewTreeObserver.removeOnWindowFocusChangeListener(listener)
        } }
    }
    private fun assertInitiallyDisabled(label: String) {
        var node = text(label)
        try {
            repeat(32) {
                if (!node.isEnabled) return
                val previous = node
                node = previous.parent ?: error("Owned disabled ancestor missing")
                previous.recycle()
            }
            error("Owned disabled ancestor not observed")
        } finally { node.recycle() }
    }

    private fun assertReturnedOwnerGeneration(owner: UiObject2, expected: String) {
        var node: UiObject2? = owner
        val chain = arrayListOf<String>()
        try {
            for (depth in 0 until 32) {
                val current = node ?: break
                val description = current.contentDescription
                val ownedMarker = description?.takeIf { it.startsWith("Owned replacement generation ") }
                chain.add("$depth:${current.className}(ownedMarker=${ownedMarker ?: "absent"})")
                if (ownedMarker != null) {
                    assertEquals("Returned action's actual ancestor chain: $chain", expected, ownedMarker)
                    return
                }
                node = current.parent
                if (current !== owner) current.recycle()
            }
            fail("No owned generation on returned action or its actual ancestors: $chain")
        } finally { node?.takeIf { it !== owner }?.recycle() }
    }

    @Test fun enabledActionWaitsForControlledEnableWithoutClickingAndRestoresIdleTimeout() {
        val label = "Owned action becomes enabled"
        val enabled = mutableStateOf(false)
        val clicks = AtomicInteger()
        val clicked = CountDownLatch(1)
        activity.scenario.onActivity { host ->
            host.setContent { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Button({ clicks.incrementAndGet(); clicked.countDown() }, enabled = enabled.value) { Text(label) }
            } }
        }
        awaitOwnedWindowFocus()
        assertInitiallyDisabled(label)
        val configuration = Configurator.getInstance()
        val previousIdle = configuration.getWaitForIdleTimeout()
        val scheduled = AtomicBoolean(false)
        val active = AtomicBoolean(true)
        val handler = Handler(Looper.getMainLooper())
        val enable = Runnable { if (active.get()) enabled.value = true }
        try {
            val owner = enabledTextAction(label) {
                assertEquals(0L, configuration.getWaitForIdleTimeout())
                if (scheduled.compareAndSet(false, true)) check(handler.post(enable))
            }
            try {
                assertTrue("A pending query must precede the controlled enable", scheduled.get())
                assertEquals(previousIdle, configuration.getWaitForIdleTimeout())
                assertEquals("A query must never perform a click", 0, clicks.get())
                assertTrue(owner.isEnabled); assertTrue(owner.isClickable)
                assertTrue(owner.visibleBounds.width() > 0 && owner.visibleBounds.height() > 0)
                owner.click()
                assertTrue("The one real click must reach its callback", clicked.await(5, TimeUnit.SECONDS))
                assertEquals(1, clicks.get())
            } finally { owner.recycle() }
        } finally {
            active.set(false); handler.removeCallbacks(enable)
            try { assertEquals(previousIdle, configuration.getWaitForIdleTimeout()) }
            finally { configuration.setWaitForIdleTimeout(previousIdle) }
        }
    }

    @Test fun continuouslyDisabledActionFailsWithoutChoosingEnabledWrapperAndRestoresIdleTimeout() {
        val label = "Owned action stays disabled"
        val clicks = AtomicInteger()
        val wrapperClicks = AtomicInteger()
        activity.scenario.onActivity { host ->
            host.setContent { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Box(Modifier.clickable { wrapperClicks.incrementAndGet() }) {
                    Button({ clicks.incrementAndGet() }, enabled = false) { Text(label) }
                }
            } }
        }
        awaitOwnedWindowFocus()
        assertInitiallyDisabled(label)
        val configuration = Configurator.getInstance()
        val previousIdle = configuration.getWaitForIdleTimeout()
        val polls = AtomicInteger()
        try {
            val started = SystemClock.uptimeMillis()
            val failure = runCatching {
                enabledTextAction(label) { polls.incrementAndGet(); assertEquals(0L, configuration.getWaitForIdleTimeout()) }
                    .also { it.recycle() }
            }.exceptionOrNull()
            assertTrue("A continuously disabled action must fail", failure is IllegalStateException)
            assertTrue(failure!!.message.orEmpty(), failure.message.orEmpty().contains("within 5000ms"))
            assertTrue("A disabled action must consume its polling budget", SystemClock.uptimeMillis() - started >= 5_000)
            assertTrue("A real disabled query must be observed", polls.get() >= 1)
            assertEquals(previousIdle, configuration.getWaitForIdleTimeout())
            assertEquals(0, clicks.get()); assertEquals(0, wrapperClicks.get())
            // Also cover an exception inside the query; restoration is not limited
            // to successful results or the ordinary exhausted-budget path.
            val observerFailure = runCatching { enabledTextAction(label) { throw IllegalArgumentException("Owned query failure") } }.exceptionOrNull()
            assertTrue(observerFailure is IllegalArgumentException)
            assertEquals(previousIdle, configuration.getWaitForIdleTimeout())
            assertEquals(0, clicks.get()); assertEquals(0, wrapperClicks.get())
        } finally { configuration.setWaitForIdleTimeout(previousIdle) }
    }

    @Test fun enabledActionRequeriesAfterComposeReplacesItsDisabledNode() {
        val label = "Owned action after replacement"
        val generation = mutableStateOf(0)
        val oldClicks = AtomicInteger(); val newClicks = AtomicInteger()
        val newClicked = CountDownLatch(1)
        val replacementDrawn = CountDownLatch(1)
        activity.scenario.onActivity { host ->
            host.setContent { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val version = generation.value
                key(version) {
                    // Give this keyed action an explicit non-clickable marked
                    // ancestor. Material's clickable owner need not itself own
                    // the modifier's contentDescription in the platform tree.
                    Box(Modifier.semantics { contentDescription = "Owned replacement generation $version" }) {
                        Button({
                            if (version == 0) oldClicks.incrementAndGet()
                            else { newClicks.incrementAndGet(); newClicked.countDown() }
                        }, enabled = version == 1, modifier = Modifier.drawWithContent {
                            drawContent()
                            if (version == 1) replacementDrawn.countDown()
                        }) { Text(label) }
                    }
                }
            } }
        }
        awaitOwnedWindowFocus()
        assertInitiallyDisabled(label)
        val configuration = Configurator.getInstance()
        val previousIdle = configuration.getWaitForIdleTimeout()
        val scheduled = AtomicBoolean(false)
        val active = AtomicBoolean(true)
        val handler = Handler(Looper.getMainLooper())
        val replace = Runnable { if (active.get()) generation.value = 1 }
        try {
            val owner = enabledTextAction(label) {
                if (scheduled.compareAndSet(false, true)) check(handler.post(replace))
            }
            try {
                assertTrue(scheduled.get())
                assertEquals(previousIdle, configuration.getWaitForIdleTimeout())
                assertReturnedOwnerGeneration(owner, "Owned replacement generation 1")
                assertEquals(0, oldClicks.get()); assertEquals(0, newClicks.get())
                // Accessibility can expose an enabled keyed replacement before
                // its new input/layout frame is drawn. Inject once users could
                // actually see that replacement, without retrying a gesture.
                assertTrue("Replacement must draw before the one physical click", replacementDrawn.await(5, TimeUnit.SECONDS))
                owner.click()
                assertTrue("The one real click must reach the replacement callback", newClicked.await(5, TimeUnit.SECONDS))
                assertEquals(0, oldClicks.get()); assertEquals(1, newClicks.get())
            } finally { owner.recycle() }
        } finally {
            active.set(false); handler.removeCallbacks(replace)
            try { assertEquals(previousIdle, configuration.getWaitForIdleTimeout()) }
            finally { configuration.setWaitForIdleTimeout(previousIdle) }
        }
    }

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
