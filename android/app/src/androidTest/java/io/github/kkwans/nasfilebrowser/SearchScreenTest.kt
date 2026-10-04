package io.github.kkwans.nasfilebrowser

import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import io.github.kkwans.nasfilebrowser.app.ClientModel
import io.github.kkwans.nasfilebrowser.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/** Installed application UI -> bound ViewModel -> actual JNI/Go -> owned HTTP fixture. */
@RunWith(AndroidJUnit4::class)
class SearchScreenTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private fun objectWithText(text: String): UiObject2 = device.wait(Until.findObject(By.text(text)), 5000)
        ?: throw AssertionError("Visible action missing: $text")
    private fun resultWithName(name: String): UiObject2 = device.wait(Until.findObject(By.clazz("android.widget.TextView").text(name)), 5000)
        ?: throw AssertionError("Visible result missing: $name")
    private fun capture(name: String) {
        instrumentation.waitForIdleSync()
        // Capture the settled dialog/system-bar frame, not its exit animation.
        android.os.SystemClock.sleep(250)
        val variant = InstrumentationRegistry.getArguments().getString("nfbVisualVariant").orEmpty()
        require(variant.matches(Regex("[a-z0-9-]{0,32}")))
        val suffix = if (variant.isEmpty()) "" else "-$variant"
        device.executeShellCommand("mkdir -p /sdcard/Download/nfb-client-acceptance")
        device.executeShellCommand("screencap -p /sdcard/Download/nfb-client-acceptance/$name$suffix.png")
        device.dumpWindowHierarchy(instrumentation.targetContext.filesDir.resolve("search-tree.xml"))
    }

    private suspend fun fixture(action: suspend (ClientModel, ClientSearchTest.Fixture) -> Unit) {
        val source = ClientSearchTest.Fixture()
        val store = ProfileStore(ClientDatabase.get(instrumentation.targetContext), CredentialVault(instrumentation.targetContext))
        val profile = store.save(ServerProfile(name = "Search UI fixture", address = source.url))
        lateinit var model: ClientModel
        activity.scenario.onActivity { model = ViewModelProvider(it)[ClientModel::class.java] }
        InstrumentationRegistry.getArguments().getString("nfbFontScale")?.toFloat()?.let { expected ->
            assertEquals(expected, instrumentation.targetContext.resources.configuration.fontScale, 0.01f)
        }
        try {
            withContext(Dispatchers.Main) { model.selectProfile(profile); model.connectDraft(profile.name, source.url, BackendKind.NAS, "one", "fixture-only", "direct") }
            withTimeout(10_000) { model.state.first { it.connected && !it.busy } }
            (device.wait(Until.findObject(By.desc("搜索文件")), 5000) ?: error("Header search action missing")).click()
            assertTrue(device.wait(Until.hasObject(By.desc("搜索文件名")), 5000))
            assertFalse("Search paths must not include server/account metadata", device.hasObject(By.textContains(model.state.value.serverLabel)))
            action(model, source)
        } catch (error: Exception) { capture("search-failure"); throw error }
        catch (error: AssertionError) { capture("search-failure"); throw error }
        finally {
            source.releaseSearch.countDown(); source.releaseMetadata.countDown(); source.releasePlayback.countDown()
            withContext(Dispatchers.Main) { model.disconnect() }
            store.remove(profile); source.close()
        }
    }
    private fun submit(value: String) {
        val input = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 5000)
            ?: throw AssertionError("Native editable search field missing")
        input.text = value
        objectWithText("搜索").click()
    }

    @Test fun actualSearchControlsCancelRetryScopeDetailsAndDirectoryNavigation(): Unit = runBlocking {
        fixture { model, source ->
            submit("slow")
            withTimeout(5000) { model.search.state.first { it.items.isNotEmpty() && it.running } }
            objectWithText("取消").click()
            assertTrue(withContext(Dispatchers.IO) { source.canceled.await(5, TimeUnit.SECONDS) })
            assertEquals(SearchEnding.CANCELED, model.search.state.value.ending)
            objectWithText("当前目录").click()
            assertEquals(SearchScope.CURRENT, model.search.state.value.scope)
            submit("retry")
            withTimeout(5000) { model.search.state.first { it.ending == SearchEnding.FAILED } }
            objectWithText("重新搜索").click()
            withTimeout(5000) { model.search.state.first { it.ending == SearchEnding.COMPLETED } }
            assertEquals("retry", model.search.state.value.query)
            resultWithName("retry").longClick()
            assertTrue(device.wait(Until.hasObject(By.text("/retry")), 5000))
            objectWithText("关闭").click()
            resultWithName("retry").click()
            withTimeout(5000) { model.state.first { !it.busy && it.path == "/retry" } }
            assertFalse(model.search.state.value.open)
            assertTrue(device.wait(Until.hasObject(By.desc("上一级")), 5000))
        }
    }

    @Test fun returningToFilesCancelsPendingMovieSourceConfirmation(): Unit = runBlocking {
        fixture { model, source ->
            submit("back.mkv")
            withTimeout(5000) { model.search.state.first { it.ending == SearchEnding.COMPLETED } }
            resultWithName("back.mkv").click()
            assertTrue(withContext(Dispatchers.IO) { source.playbackStarted.await(5, TimeUnit.SECONDS) })
            val back = device.wait(Until.findObject(By.desc("返回文件")), 5000) ?: throw AssertionError("Return action missing")
            back.click()
            assertTrue(device.wait(Until.gone(By.desc("返回文件")), 5000))
            source.releasePlayback.countDown()
            withTimeout(5000) { model.state.first { !it.busy } }
            delay(200)
            assertNull("Leaving search must not start the previously selected movie after its identity response arrives", model.state.value.selected)
            assertFalse(model.search.state.value.open)
        }
    }

    @Test fun longFilenameAndScopeRemainUsableInPortraitAndLandscape(): Unit = runBlocking {
        fixture { model, _ ->
            val title = "秋日旅行.导演剪辑版.2026.2160p.特别长的完整文件名称.中文字幕.mkv"
            submit(title)
            withTimeout(5000) { model.search.state.first { it.ending == SearchEnding.COMPLETED } }
            resultWithName(title)
            capture("search-results")
            device.setOrientationLeft()
            try {
                val list = device.wait(Until.findObject(By.desc("搜索结果")), 5000) ?: throw AssertionError("Scrollable results missing")
                if (!device.hasObject(By.clazz("android.widget.TextView").text(title))) list.scroll(Direction.DOWN, 0.8f)
                resultWithName(title).longClick()
                capture("search-details-top-landscape")
                if (!device.hasObject(By.text("/$title"))) {
                    val content = device.wait(Until.findObject(By.desc("文件详情内容")), 5000)
                        ?: throw AssertionError("Scrollable file details missing")
                    content.scroll(Direction.DOWN, 0.8f)
                }
                assertTrue(device.wait(Until.hasObject(By.text("/$title")), 5000))
                capture("search-details-landscape")
                objectWithText("关闭").click()
                capture("search-landscape")
                assertEquals(title, model.search.state.value.query)
            } finally { device.setOrientationNatural(); device.unfreezeRotation() }
        }
    }
}
