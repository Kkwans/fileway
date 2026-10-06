package io.github.kkwans.nasfilebrowser

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.kkwans.nasfilebrowser.app.AppearanceController
import io.github.kkwans.nasfilebrowser.data.AppTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class AppearanceControllerTest {
    @Test fun failedReadCanRetryAndCannotWriteBeforeLoaded(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        var failRead = true; var writes = 0
        try {
            val controller = withContext(Dispatchers.Main) { AppearanceController(scope,
                { if (failRead) throw IOException("test-owned read failure"); AppTheme.DARK }, { writes++ }) }
            withTimeout(5000) { controller.state.first { it.error != null } }
            withContext(Dispatchers.Main) { controller.save(AppTheme.LIGHT) }
            assertEquals(0, writes); assertFalse(controller.state.value.loaded)
            withContext(Dispatchers.Main) { failRead = false; controller.reload() }
            val loaded = withTimeout(5000) { controller.state.first { it.loaded } }
            assertEquals(AppTheme.DARK, loaded.theme); assertNull(loaded.error)
        } finally { scope.cancel() }
    }
    @Test fun failedCommitPreservesEffectiveThemeAndAllowsRetry(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        var failWrite = true
        try {
            val controller = withContext(Dispatchers.Main) { AppearanceController(scope, { AppTheme.LIGHT },
                { if (failWrite) throw IOException("test-owned write failure") }) }
            withTimeout(5000) { controller.state.first { it.loaded } }
            withContext(Dispatchers.Main) { controller.save(AppTheme.DARK) }
            val failed = withTimeout(5000) { controller.state.first { it.error != null } }
            assertEquals(AppTheme.LIGHT, failed.theme); assertFalse(failed.saving)
            withContext(Dispatchers.Main) { failWrite = false; controller.save(AppTheme.DARK) }
            val saved = withTimeout(5000) { controller.state.first { it.theme == AppTheme.DARK && !it.saving } }
            assertNull(saved.error)
        } finally { scope.cancel() }
    }
    @Test fun pendingCommitDoesNotApplyChoiceOrStartAnotherWrite(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val finish = CompletableDeferred<Unit>(); val started = CompletableDeferred<Unit>(); var writes = 0
        try {
            val controller = withContext(Dispatchers.Main) { AppearanceController(scope, { AppTheme.SYSTEM }, { writes++; started.complete(Unit); finish.await() }) }
            withTimeout(5000) { controller.state.first { it.loaded } }
            withContext(Dispatchers.Main) { controller.save(AppTheme.DARK); controller.save(AppTheme.LIGHT) }
            withTimeout(5000) { started.await() }
            assertEquals(AppTheme.SYSTEM, controller.state.value.theme)
            assertTrue(controller.state.value.saving); assertEquals(1, writes)
            finish.complete(Unit)
            withTimeout(5000) { controller.state.first { it.theme == AppTheme.DARK && !it.saving } }
        } finally { finish.complete(Unit); scope.cancel() }
    }
}
