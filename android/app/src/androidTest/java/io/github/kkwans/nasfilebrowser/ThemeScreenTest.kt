package io.github.kkwans.nasfilebrowser

import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import io.github.kkwans.nasfilebrowser.app.ClientModel
import io.github.kkwans.nasfilebrowser.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThemeScreenTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private fun themeAction(): UiObject2 {
        repeat(6) {
            device.findObject(By.text("主题"))?.let { return it }
            val content = device.wait(Until.findObject(By.desc("设置内容")), 5000) ?: error("Settings missing")
            content.scroll(Direction.DOWN, .5f)
        }
        error("Theme action missing")
    }
    private suspend fun choose(model: ClientModel, mode: AppTheme, label: String) {
        themeAction().click()
        check(device.wait(Until.hasObject(By.text("选择主题")), 5000))
        capture("theme-sheet-${mode.name.lowercase()}")
        (device.wait(Until.findObject(By.text(label)), 5000) ?: error("Theme choice missing")).click()
        withTimeout(5000) { model.appearance.state.first { it.theme == mode && !it.saving } }
    }
    private suspend fun color(dark: Boolean) {
        var lastPixels = emptyList<Int>()
        val canvas = if (dark) Color.rgb(32, 32, 35) else Color.rgb(245, 245, 247)
        val navigation = if (dark) Color.rgb(20, 20, 22) else Color.WHITE
        val matched = withTimeoutOrNull(5000) {
            while (true) {
                val screenshot = instrumentation.uiAutomation.takeScreenshot() ?: error("Screenshot unavailable")
                val bitmap = screenshot.copy(Bitmap.Config.ARGB_8888, false)
                // A closing theme sheet can still own the system bars after the page changes.
                // Wait for the actual status/navigation backgrounds, not only window icon flags.
                val values = try {
                    listOf(bitmap.getPixel(10, bitmap.height / 2),
                        bitmap.getPixel(10, 10), bitmap.getPixel(10, bitmap.height - 10))
                } finally { bitmap.recycle(); screenshot.recycle() }
                lastPixels = values
                if (values == listOf(canvas, canvas, navigation)) break
                delay(100)
            }
            true
        }
        if (matched != true) {
            capture("theme-bars-failure-${if (dark) "dark" else "light"}")
            activity.scenario.onActivity {
                val rect = android.graphics.Rect()
                it.findViewById<android.view.View>(android.R.id.content).getGlobalVisibleRect(rect)
                println("Theme window: content=$rect, flags=${it.window.attributes.flags}")
            }
        }
        assertTrue("System bar/page pixels dark=$dark: ${lastPixels.map { Integer.toHexString(it) }}", matched == true)
        activity.scenario.onActivity {
            val rect = android.graphics.Rect()
            it.findViewById<android.view.View>(android.R.id.content).getGlobalVisibleRect(rect)
            assertEquals("Content must cover the status-bar region", 0, rect.top)
            assertEquals("Content must cover the navigation-bar region", device.displayHeight, rect.bottom)
            val bars = WindowCompat.getInsetsController(it.window, it.window.decorView)
            assertEquals(!dark, bars.isAppearanceLightStatusBars)
            assertEquals(!dark, bars.isAppearanceLightNavigationBars)
        }
    }
    private fun capture(name: String) {
        instrumentation.waitForIdleSync()
        android.os.SystemClock.sleep(250)
        val variant = InstrumentationRegistry.getArguments().getString("nfbVisualVariant").orEmpty()
        require(variant.matches(Regex("[a-z0-9-]{0,32}")))
        val suffix = if (variant.isEmpty()) "" else "-$variant"
        device.executeShellCommand("mkdir -p /sdcard/Download/nfb-client-acceptance")
        device.executeShellCommand("screencap -p /sdcard/Download/nfb-client-acceptance/$name$suffix.png")
    }
    @Test fun actualThemeChoicesRenderPersistAndSurviveActivityRecreation(): Unit = runBlocking {
        val source = ClientSearchTest.Fixture()
        val store = ProfileStore(ClientDatabase.get(instrumentation.targetContext), CredentialVault(instrumentation.targetContext))
        val profile = store.save(ServerProfile(name = "我的媒体库", address = source.url))
        lateinit var model: ClientModel
        activity.scenario.onActivity { model = ViewModelProvider(it)[ClientModel::class.java] }
        InstrumentationRegistry.getArguments().getString("nfbFontScale")?.toFloat()?.let { expected ->
            assertEquals(expected, instrumentation.targetContext.resources.configuration.fontScale, .01f)
        }
        val original = withTimeout(5000) { model.appearance.state.first { it.loaded } }.theme
        try {
            withContext(Dispatchers.Main) { model.selectProfile(profile); model.connectDraft(profile.name, source.url, BackendKind.NAS, "one", "fixture-only", "direct") }
            withTimeout(10_000) { model.state.first { it.connected && !it.busy } }
            (device.wait(Until.findObject(By.desc("设置导航")), 5000) ?: error("Settings nav missing")).click()
            choose(model, AppTheme.DARK, "暗色"); color(true); capture("theme-dark")
            assertEquals(AppTheme.DARK, AppearanceStore(ClientDatabase.get(instrumentation.targetContext)).theme.first())
            activity.scenario.recreate()
            color(true)
            choose(model, AppTheme.LIGHT, "明色"); color(false); capture("theme-light")
            choose(model, AppTheme.SYSTEM, "跟随系统")
            val systemDark = (instrumentation.targetContext.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
            color(systemDark)
        } finally {
            withContext(Dispatchers.Main) { model.appearance.save(original) }
            withTimeout(5000) { model.appearance.state.first { it.theme == original && !it.saving } }
            withContext(Dispatchers.Main) { model.disconnect() }; store.remove(profile); source.close()
        }
    }
}
