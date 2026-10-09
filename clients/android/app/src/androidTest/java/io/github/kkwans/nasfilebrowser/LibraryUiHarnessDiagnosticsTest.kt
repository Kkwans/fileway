package io.github.kkwans.nasfilebrowser

import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

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
        assertTrue(message, message.contains("MainActivity@$originalActivity"))
        activity.scenario.onActivity { assertEquals(originalActivity, System.identityHashCode(it)) }
    }
}
