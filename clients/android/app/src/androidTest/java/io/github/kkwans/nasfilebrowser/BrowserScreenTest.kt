package io.github.kkwans.nasfilebrowser

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

/** Installed browser -> actual model/JNI/HTTP directory, not a static mock screen. */
@RunWith(AndroidJUnit4::class)
class BrowserScreenTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private fun text(value: String) = device.wait(Until.findObject(By.text(value)), 5000)
        ?: throw AssertionError("Missing visible action: $value")
    private fun capture(name: String) {
        instrumentation.waitForIdleSync()
        android.os.SystemClock.sleep(250)
        device.executeShellCommand("mkdir -p /sdcard/Download/nfb-client-acceptance")
        device.executeShellCommand("screencap -p /sdcard/Download/nfb-client-acceptance/browser-$name.png")
    }
    @Test fun actualListGridDetailsAndDirectoryActions(): Unit = runBlocking {
        val longName = "秋日旅行.导演剪辑版.2026.2160p.特别长的完整文件名称.中文字幕.mkv"
        val source = ClientSearchTest.Fixture(listOf("旅行", "文档", "A.mkv", "B.mkv", "C.mkv", longName))
        val store = ProfileStore(ClientDatabase.get(instrumentation.targetContext), CredentialVault(instrumentation.targetContext))
        val profile = store.save(ServerProfile(name = "我的媒体库", address = source.url))
        lateinit var model: ClientModel
        activity.scenario.onActivity { model = ViewModelProvider(it)[ClientModel::class.java] }
        try {
            withContext(Dispatchers.Main) { model.selectProfile(profile); model.connectDraft(profile.name, source.url, BackendKind.NAS, "one", "fixture-only", "direct") }
            withTimeout(10_000) { model.state.first { it.connected && !it.busy && it.files.size == 6 } }
            var currentLayout = "封面网格"
            fun chooseLayout(next: String) { text(currentLayout).click(); text(next).click(); currentLayout = next }
            assertTrue(device.wait(Until.hasObject(By.desc("文件网格")), 5000))
            assertFalse("Server identity belongs in settings", device.hasObject(By.text(model.state.value.serverLabel)))
            assertFalse(device.hasObject(By.text("切换服务器")))
            val search = device.wait(Until.findObject(By.desc("搜索文件")), 5000) ?: error("Header search icon missing")
            assertTrue("Search belongs in the first toolbar on the right", search.visibleBounds.top < device.displayHeight * .12f && search.visibleBounds.centerX() > device.displayWidth * .8f)
            val folders = listOf("旅行", "文档").map { text(it).visibleBounds }
            assertTrue("Folders must share cover-grid columns instead of spanning full-width rows", folders[0].left != folders[1].left)
            assertTrue("Folder titles must align in the same grid row", kotlin.math.abs(folders[0].top - folders[1].top) <= 2)
            capture("cover-default")
            chooseLayout("常规列表")
            val list = device.wait(Until.findObject(By.desc("文件列表")), 5000) ?: error("File list missing")
            if (instrumentation.targetContext.resources.configuration.fontScale <= 1.05f)
                assertTrue("Files must occupy at least 60% of the compact portrait screen", list.visibleBounds.height() >= device.displayHeight * .6f)
            capture("list")
            chooseLayout("封面网格")
            val grid = device.wait(Until.findObject(By.desc("文件网格")), 5000) ?: error("File grid missing")
            if (instrumentation.targetContext.resources.configuration.fontScale <= 1.05f) {
                instrumentation.waitForIdleSync()
                val offsets = listOf("A.mkv", "B.mkv", "C.mkv", longName).map { name ->
                    var offset: Int? = null
                    // Inspect the final scroll result too, and wait for the
                    // accessibility tree rather than only the app's main queue.
                    for (attempt in 0..6) {
                        device.waitForIdle()
                        val title = device.findObject(By.text(name))?.visibleBounds
                        val size = if (title == null || title.height() == 0) null else device.findObjects(By.text("100.0 MB")).map { it.visibleBounds }
                            .filter { it.height() > 0 && kotlin.math.abs(it.left - title.left) <= 2 && it.top >= title.bottom }.minByOrNull { it.top }
                        if (title != null && size != null) { offset = size.top - title.top; break }
                        if (attempt < 6) grid.scroll(Direction.DOWN, .35f)
                    }
                    offset ?: error("Visible metadata missing for $name")
                }
                assertTrue("Grid metadata must align despite one- and two-line filenames", offsets.max() - offsets.min() <= 2)
            }
            if (!device.hasObject(By.text(longName))) grid.scroll(Direction.DOWN, .8f)
            text(longName).longClick()
            text("文件详情")
            if (!device.hasObject(By.text("/$longName"))) device.findObject(By.desc("文件详情内容")).scroll(Direction.DOWN, .8f)
            text("/$longName")
            capture("details")
            text("关闭").click()
            capture("grid")
            chooseLayout("紧凑网格")
            assertTrue(device.wait(Until.hasObject(By.desc("紧凑文件网格")), 5000))
            capture("compact-grid")
            chooseLayout("无界网格")
            assertTrue(device.wait(Until.hasObject(By.desc("无界文件网格")), 5000))
            capture("unbounded-grid")
            chooseLayout("大图列表")
            assertTrue(device.wait(Until.hasObject(By.desc("大图文件列表")), 5000))
            capture("detail-list")
            chooseLayout("常规列表")
            (device.wait(Until.findObject(By.desc("刷新")), 5000) ?: error("Refresh action missing")).click()
            withTimeout(5000) { model.state.first { !it.busy && it.files.size == 6 } }
            text("旅行").click()
            withTimeout(5000) { model.state.first { !it.busy && it.path == "/旅行" } }
            text("这个目录还没有文件。")
            device.findObject(By.desc("上一级")).click()
            withTimeout(5000) { model.state.first { !it.busy && it.path == "/" && it.files.size == 6 } }
            text("最近").click(); text("继续观看")
            text("文件").click()
            assertTrue(device.wait(Until.hasObject(By.desc("文件列表")), 5000))
        } catch (error: Throwable) { capture("failure"); throw error }
        finally { withContext(Dispatchers.Main) { model.disconnect() }; store.remove(profile); source.close() }
    }
}
