package io.github.kkwans.nasfilebrowser

import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import io.github.kkwans.nasfilebrowser.app.*
import io.github.kkwans.nasfilebrowser.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
internal class ShellUiTest : LibraryUiHarness() {
    @Test fun nativeCommandInputRunAndCopyShowOutputConnectionSemantics(): Unit = runBlocking {
        val fixture = OwnedShellFixture(); val owner = fixture.session("one")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val controller = ShellController(scope) { it === owner }
        activity.scenario.onActivity { host ->
            model = ViewModelProvider(host)[ClientModel::class.java]
            host.setContent { ClientTheme(darkTheme = false) { LibraryTheme { ShellScreen(controller) {} } } }
        }
        try {
            main { controller.bind(owner); controller.open(fixture.directory, owner.api.id) }
            withTimeout(5000) { controller.state.first { !it.loading && it.execute } }
            text("工作目录：/�")
            val input = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 5000) ?: error("Missing command input")
            input.text = "echo owned"
            text("运行").click()
            withTimeout(5000) { controller.state.first { !it.running && !it.loading && it.output.lines.isNotEmpty() } }
            text("owned output"); text("复制输出").click(); text("已复制当前显示的输出")
            assertEquals(1, fixture.starts.size)
            action("关闭命令输出").click(); assertFalse(controller.state.value.open)
        } finally { main { controller.close() }; scope.cancel() }
    }
}
