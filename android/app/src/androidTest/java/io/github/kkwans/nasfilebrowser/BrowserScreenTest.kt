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
                val sizes = device.findObjects(By.text("100.0 MB")).map { it.visibleBounds }
                val offsets = listOf("A.mkv", "B.mkv", "C.mkv", longName).map { name ->
                    val title = text(name).visibleBounds
                    val size = sizes.filter { kotlin.math.abs(it.left - title.left) <= 2 && it.top >= title.bottom }.minByOrNull { it.top }
                        ?: error("Visible metadata missing for $name")
                    size.top - title.top
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
            chooseLayout("大图列表")
            assertTrue(device.wait(Until.hasObject(By.desc("大图文件列表")), 5000))
            capture("detail-list")
            chooseLayout("常规列表")
            text("刷新").click()
            withTimeout(5000) { model.state.first { !it.busy && it.files.size == 6 } }
            text("旅行").click()
            withTimeout(5000) { model.state.first { !it.busy && it.path == "/旅行" } }
            text("这个目录还没有文件。")
            device.findObject(By.desc("上一级")).click()
            withTimeout(5000) { model.state.first { !it.busy && it.path == "/" && it.files.size == 6 } }
            text("最近播放").click(); text("继续观看")
            text("文件").click()
            assertTrue(device.wait(Until.hasObject(By.desc("文件列表")), 5000))
        } catch (error: Throwable) { capture("failure"); throw error }
        finally { withContext(Dispatchers.Main) { model.disconnect() }; store.remove(profile); source.close() }
    }
}
