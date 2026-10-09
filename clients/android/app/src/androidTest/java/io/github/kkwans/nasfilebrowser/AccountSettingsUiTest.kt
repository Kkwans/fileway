package io.github.kkwans.nasfilebrowser

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import io.github.kkwans.nasfilebrowser.app.AccountSettingsController
import io.github.kkwans.nasfilebrowser.ui.AccountSettingsScreen
import io.github.kkwans.nasfilebrowser.ui.LibraryTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccountSettingsUiTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private fun text(label: String): UiObject2 = device.wait(Until.findObject(By.text(label)), 5000) ?: error("Missing account control: $label")
    private fun field(label: String): UiObject2 = device.wait(Until.findObject(By.desc(label)), 5000) ?: error("Missing password field: $label")

    @Test fun passwordSheetRetainsUnknownResultAndUsesAccessibleTouchTargets(): Unit = runBlocking {
        val authority = AccountSettingsAuthority(); authority.commitThenFail = true
        val context = authority.context("ui")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val controller = AccountSettingsController(scope, { it === context })
        var reconnects = 0
        try {
            withContext(Dispatchers.Main) { controller.bind(context); controller.refresh() }
            withTimeout(5000) { controller.state.first { !it.loading && it.profile != null } }
            activity.scenario.onActivity { it.setContent { MaterialTheme { LibraryTheme {
                AccountSettingsScreen(controller, {}, { reconnects++ })
            } } } }
            val content = device.wait(Until.findObject(By.desc("账户设置内容")), 5000) ?: error("Account content missing")
            repeat(8) {
                if (device.hasObject(By.text("修改密码"))) return@repeat
                content.scroll(Direction.DOWN, .7f)
            }
            text("修改密码").click()
            val density = instrumentation.targetContext.resources.displayMetrics.density
            listOf("当前密码", "新密码", "确认新密码").forEach { label ->
                assertTrue("Password target below 48dp: $label", field(label).visibleBounds.height() / density >= 48f)
            }
            field("当前密码").text = "owned-current-password"
            field("新密码").text = "owned-new-password"
            field("确认新密码").text = "owned-new-password"
            text("确认修改密码").click()
            withTimeout(5000) { controller.state.first { it.passwordUnknown && !it.saving } }
            assertEquals(1, authority.writes)
            var reconnect = text("重新连接核对")
            while (!reconnect.isClickable) reconnect = reconnect.parent ?: error("Reconnect control not clickable")
            assertTrue(reconnect.visibleBounds.height() / density >= 48f)
            reconnect.click()
            instrumentation.waitForIdleSync()
            assertEquals(1, reconnects)
            assertEquals("owned-new-password", controller.state.value.password.replacement)
            assertEquals(1, authority.writes)
        } finally { scope.cancel() }
    }
}
