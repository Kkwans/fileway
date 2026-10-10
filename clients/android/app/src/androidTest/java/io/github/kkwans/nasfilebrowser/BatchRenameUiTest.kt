package io.github.kkwans.nasfilebrowser

import android.graphics.Rect
import android.view.accessibility.AccessibilityWindowInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import io.github.kkwans.nasfilebrowser.app.*
import io.github.kkwans.nasfilebrowser.data.*
import io.github.kkwans.nasfilebrowser.ui.BatchRenameSheet
import io.github.kkwans.nasfilebrowser.ui.ClientTheme
import io.github.kkwans.nasfilebrowser.ui.LibraryTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
internal class BatchRenameUiTest : LibraryUiHarness() {
    private fun keyboardVisible(): Boolean = instrumentation.uiAutomation.windows.any { window ->
        if (window.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD) false else {
            val bounds = Rect()
            window.getBoundsInScreen(bounds)
            bounds.height() > 0 && bounds.top < device.displayHeight
        }
    }
    private fun click(label: String) {
        val button = enabledTextAction(label)
        try {
            assertTrue(button.isEnabled)
            assertTrue("$label must retain a 48dp touch target", button.visibleBounds.height() + 1 >= 48 * instrumentation.targetContext.resources.displayMetrics.density)
            button.click()
        } finally { button.recycle() }
    }
    @Test fun nativePreviewRetainsRejectedInputAndUnknownExecutionOnlyAllowsVerification(): Unit = runBlocking {
        val authority = BatchRenameAuthority(); val context = authority.context("one")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        var verified = 0
        val controller = FileOperationsController(scope, { it === context }, { _, _, _ -> error("Must use batch callback") })
        main { controller.bind(context); controller.startBatchRename(authority.files, batchRenameParent(authority.files.first()), context.api.id) }
        activity.scenario.onActivity { host ->
            model = ViewModelProvider(host)[ClientModel::class.java]
            host.setContent { ClientTheme(darkTheme = false) { LibraryTheme { Surface(Modifier.fillMaxSize()) {
                BatchRenameSheet(controller) { parent -> assertEquals("/", parent.path); verified++ }
            } } } }
        }
        try {
            text("批量重命名"); text("添加前缀")
            val input = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 5000) ?: error("Missing prefix input")
            input.text = "native-"
            withTimeout(5000) { controller.state.first { it.batchRename?.options?.text == "native-" } }
            device.waitForIdle()
            // ACTION_SET_TEXT does not require an IME. Back without a visible
            // keyboard dismisses the sheet instead of finishing text entry.
            if (keyboardVisible()) {
                device.pressBack()
                withTimeout(5000) { while (keyboardVisible()) delay(50) }
            }
            assertNotNull("Typing must keep the rename draft open", controller.state.value.batchRename)
            assertEquals("native-", controller.state.value.batchRename!!.options.text)
            authority.reviewStatus = 403
            click("检查变更")
            withTimeout(5000) { controller.state.first { !it.changing && it.batchRename?.error != null } }
            assertEquals("native-", controller.state.value.batchRename!!.options.text)
            assertEquals(0, authority.executions)
            authority.reviewStatus = 200
            click("检查变更")
            withTimeout(5000) { controller.state.first { !it.changing && it.batchRename?.reviewed == true } }
            click("确认重命名 2 项"); text("执行这 2 项重命名？")
            assertEquals(0, authority.executions)
            click("返回预览")
            assertEquals(0, authority.executions)
            authority.executionStatus = 500
            click("确认重命名 2 项"); click("确认执行")
            withTimeout(5000) { controller.state.first { !it.changing && it.batchRename?.unknownExecution == true } }
            assertEquals(1, authority.executions)
            assertFalse(device.hasObject(By.text("检查变更")))
            click("刷新原目录核对并关闭")
            instrumentation.runOnMainSync { assertEquals(1, verified) }
            assertNull(controller.state.value.batchRename)
            assertEquals(1, authority.executions)
        } finally { scope.cancel() }
    }
}
