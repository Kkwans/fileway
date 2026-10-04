package io.github.kkwans.nasfilebrowser

import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import io.github.kkwans.nasfilebrowser.app.ClientModel
import io.github.kkwans.nasfilebrowser.data.AppTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Installed form/layout/keyboard gate. All input is owned synthetic metadata. */
@RunWith(AndroidJUnit4::class)
class ConnectionScreenTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private fun text(value: String) = device.wait(Until.findObject(By.text(value)), 5_000) ?: error("Missing form control: $value")
    private fun capture(name: String) {
        instrumentation.waitForIdleSync()
        device.executeShellCommand("mkdir -p /sdcard/Download/nfb-client-acceptance")
        device.executeShellCommand("screencap -p /sdcard/Download/nfb-client-acceptance/connection-$name.png")
    }

    @Test fun groupedFormKeepsActionsVisibleAndInputsAcrossModeThemeAndKeyboard(): Unit = runBlocking {
        lateinit var model: ClientModel
        activity.scenario.onActivity { model = ViewModelProvider(it)[ClientModel::class.java] }
        withTimeout(5_000) { model.appearance.state.first { it.loaded } }
        val original = model.appearance.state.value.theme
        suspend fun theme(mode: AppTheme) {
            withContext(Dispatchers.Main) { model.appearance.save(mode) }
            withTimeout(5_000) { model.appearance.state.first { it.theme == mode && !it.saving } }
            instrumentation.waitForIdleSync()
        }
        try {
            withContext(Dispatchers.Main) { model.selectProfile(null) }
            theme(AppTheme.LIGHT)
            text("连接你的文件库")
            assertTrue("Both server and account sections should be visible", device.hasObject(By.text("服务账号")))
            val fields = device.findObjects(By.clazz("android.widget.EditText")).map { it.visibleBounds }.filter { !it.isEmpty }
            assertEquals("Four fields must fit the initial ordinary-scale portrait view", 4, fields.size)
            assertTrue("Field left edges must align", fields.maxOf { it.left } - fields.minOf { it.left } <= 2)
            assertTrue("Field right edges must align", fields.maxOf { it.right } - fields.minOf { it.right } <= 2)
            val action = text("连接服务器").visibleBounds
            assertTrue("Primary action stays at the bottom", action.top > device.displayHeight * .7f)
            capture("grouped-light")

            text("服务器地址").click()
            device.executeShellCommand("input text https://fixture.example.test/library")
            assertTrue(device.wait(Until.hasObject(By.textContains("https://fixture.example.test/library")), 5_000))
            withTimeout(5_000) {
                while (true) {
                    var bottom = 0
                    activity.scenario.onActivity { bottom = ViewCompat.getRootWindowInsets(it.window.decorView)?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0 }
                    if (bottom > 0) {
                        assertTrue("Primary action must stay above the keyboard", text("连接服务器").visibleBounds.bottom <= device.displayHeight - bottom)
                        break
                    }
                    delay(100)
                }
            }
            capture("keyboard")
            device.pressBack()
            text("Tailscale").click()
            text("连接 Tailscale")
            assertTrue(device.hasObject(By.textContains("https://fixture.example.test/library")))
            capture("tailnet")
            text("本地网络").click()
            theme(AppTheme.DARK)
            assertTrue(device.hasObject(By.textContains("https://fixture.example.test/library")))
            assertTrue(device.hasObject(By.text("服务账号")))
            capture("grouped-dark")
        } finally {
            theme(original)
            device.pressBack()
        }
    }
}
