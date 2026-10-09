package io.github.kkwans.nasfilebrowser

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import io.github.kkwans.nasfilebrowser.app.AdminUsersController
import io.github.kkwans.nasfilebrowser.ui.AdminUsersScreen
import io.github.kkwans.nasfilebrowser.ui.LibraryTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AdminUsersUiTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private fun text(value: String) = device.wait(Until.findObject(By.text(value)), 5000) ?: error("Missing admin action: $value")

    @Test fun listEditorAndDeletionRequireVisibleNativeConfirmation(): Unit = runBlocking {
        val authority = AdminUsersAuthority(); authority.authMethod = "hook"
        val context = authority.context("ui"); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val controller = AdminUsersController(scope, { it === context })
        try {
            withContext(Dispatchers.Main) { controller.bind(context); controller.refresh() }
            withTimeout(5000) { controller.state.first { it.authorized && !it.loading } }
            activity.scenario.onActivity { it.setContent { MaterialTheme { LibraryTheme { AdminUsersScreen(controller, {}, {}) } } } }
            text("member").click()
            text("编辑用户"); text("用户名"); text("用户目录")
            text("删除用户").click(); text("删除这个用户？")
            assertEquals(0, authority.writes)
            text("取消").click(); assertEquals(0, authority.writes)
            text("删除用户").click(); text("确认").click()
            withTimeout(5000) { controller.state.first { it.notice == "用户已删除" && !it.saving } }
            text("用户已删除"); assertFalse(authority.users.containsKey(2))
        } finally { scope.cancel() }
    }
}
