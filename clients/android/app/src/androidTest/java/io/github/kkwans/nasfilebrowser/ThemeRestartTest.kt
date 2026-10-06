package io.github.kkwans.nasfilebrowser

import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.kkwans.nasfilebrowser.app.ClientModel
import io.github.kkwans.nasfilebrowser.data.AppTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Run separately with a host-side force-stop between the two instrumentation invocations. */
@RunWith(AndroidJUnit4::class)
class ThemeRestartPrepareTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)
    @Test fun saveDarkForOwnedProcessRestart(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val saved = File(context.noBackupFilesDir, "theme-restart-test-original.txt")
        check(!saved.exists()) { "Restore the previous restart test before starting another" }
        lateinit var model: ClientModel
        activity.scenario.onActivity { model = ViewModelProvider(it)[ClientModel::class.java] }
        val original = withTimeout(5000) { model.appearance.state.first { it.loaded } }.theme
        saved.writeText(original.name)
        withContext(Dispatchers.Main) { model.appearance.save(AppTheme.DARK) }
        withTimeout(5000) { model.appearance.state.first { it.theme == AppTheme.DARK && !it.saving } }
    }
}

@RunWith(AndroidJUnit4::class)
class ThemeRestartVerifyTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)
    @Test fun freshProcessRestoresDarkBeforeLoginAndRestoresOriginalPreference(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val saved = File(instrumentation.targetContext.noBackupFilesDir, "theme-restart-test-original.txt")
        check(saved.isFile) { "Restart preparation is missing" }
        val original = AppTheme.valueOf(saved.readText())
        lateinit var model: ClientModel
        activity.scenario.onActivity { model = ViewModelProvider(it)[ClientModel::class.java] }
        try {
            assertEquals(AppTheme.DARK, withTimeout(5000) { model.appearance.state.first { it.loaded } }.theme)
            assertFalse(model.state.value.connected)
            withTimeout(5000) {
                while (true) {
                    val screenshot = instrumentation.uiAutomation.takeScreenshot() ?: error("Screenshot unavailable")
                    val bitmap = screenshot.copy(Bitmap.Config.ARGB_8888, false)
                    val pixel = try { bitmap.getPixel(10, bitmap.height / 2) } finally { bitmap.recycle(); screenshot.recycle() }
                    if (pixel == Color.rgb(16, 20, 27)) break
                    delay(100)
                }
            }
            activity.scenario.onActivity {
                val bars = WindowCompat.getInsetsController(it.window, it.window.decorView)
                assertFalse(bars.isAppearanceLightStatusBars); assertFalse(bars.isAppearanceLightNavigationBars)
            }
        } finally {
            withContext(Dispatchers.Main) { model.appearance.save(original) }
            withTimeout(5000) { model.appearance.state.first { it.theme == original && !it.saving } }
            check(saved.delete()) { "Cannot remove test-owned preference recovery record" }
        }
    }
}
