package io.github.kkwans.nasfilebrowser

import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
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
import io.github.kkwans.nasfilebrowser.data.ClientDatabase
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
    private fun button(value: String) {
        var target = text(value)
        while (!target.isClickable) target = target.parent ?: error("Missing clickable owner: $value")
        target.click()
    }
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
        val originalProfile = model.state.value.profile
        val database = ClientDatabase.get(instrumentation.targetContext)
        val previousSession = database.profiles().activeSession()
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
            assertEquals("Address, account and password must fit the initial portrait view", 3, fields.size)
            assertTrue("Field left edges must align", fields.maxOf { it.left } - fields.minOf { it.left } <= 2)
            assertTrue("Field right edges must align", fields.maxOf { it.right } - fields.minOf { it.right } <= 2)
            val action = text("连接服务器").visibleBounds
            assertTrue("Primary action stays at the bottom", action.top > device.displayHeight * .7f)
            capture("grouped-light")

            button("更多连接选项")
            text("收起连接选项")
            text("档案名称（选填）")
            button("收起连接选项")
            assertTrue("Optional fields must collapse without crowding login", device.wait(Until.gone(By.text("档案名称（选填）")), 5000))

            text("服务器地址").click()
            // Shell key events pass through the owner's IME and may become
            // composed text/full-width punctuation. Use the actual EditText's
            // accessibility editing action without changing the phone's IME.
            device.findObjects(By.clazz("android.widget.EditText")).first().text = "https://fixture.example.test/library"
            assertTrue(device.wait(Until.hasObject(By.textContains("https://fixture.example.test/library")), 5_000))
            activity.scenario.onActivity { WindowCompat.getInsetsController(it.window, it.window.decorView).show(WindowInsetsCompat.Type.ime()) }
            withTimeout(5_000) {
                while (true) {
                    var bottom = 0
                    activity.scenario.onActivity {
                        val insets = ViewCompat.getRootWindowInsets(it.window.decorView)
                        if (insets?.isVisible(WindowInsetsCompat.Type.ime()) == true) bottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
                    }
                    if (bottom > 0) {
                        assertTrue("Primary action must stay above the keyboard", text("连接服务器").visibleBounds.bottom <= device.displayHeight - bottom)
                        break
                    }
                    delay(100)
                }
            }
            capture("keyboard")
            // Back can leave the Activity if the owner's IME already dismissed
            // itself after accessibility editing. Hide the IME explicitly.
            activity.scenario.onActivity { WindowCompat.getInsetsController(it.window, it.window.decorView).hide(WindowInsetsCompat.Type.ime()) }
            withTimeout(5000) {
                while (true) {
                    var visible = false
                    activity.scenario.onActivity { visible = ViewCompat.getRootWindowInsets(it.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true }
                    if (!visible) break
                    delay(50)
                }
            }
            text("Tailscale").click()
            text("连接 Tailscale")
            assertTrue(device.hasObject(By.textContains("https://fixture.example.test/library")))
            capture("tailnet")
            text("本地网络").click()
            theme(AppTheme.DARK)
            assertTrue(device.hasObject(By.textContains("https://fixture.example.test/library")))
            assertTrue(device.hasObject(By.text("服务账号")))
            capture("grouped-dark")
        } catch (failure: Throwable) {
            capture("failure")
            throw failure
        } finally {
            withContext(NonCancellable) {
                theme(original)
                withContext(Dispatchers.Main) { model.selectProfile(originalProfile) }
                previousSession?.let { if (database.profiles().account(it.accountKey) != null) database.profiles().saveActiveSession(it) }
            }
        }
    }
}
