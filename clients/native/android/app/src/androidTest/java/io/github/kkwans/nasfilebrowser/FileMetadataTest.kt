package io.github.kkwans.nasfilebrowser

import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import io.github.kkwans.nasfilebrowser.app.ClientModel
import io.github.kkwans.nasfilebrowser.data.*
import io.github.kkwans.nasfilebrowser.ui.displayModified
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.text.SimpleDateFormat
import java.time.ZoneId
import java.util.Date
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class FileMetadataTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private fun text(value: String) = device.wait(Until.findObject(By.text(value)), 5000) ?: error("Missing metadata: $value")
    private fun capture(name: String) {
        instrumentation.waitForIdleSync()
        android.os.SystemClock.sleep(250)
        device.executeShellCommand("mkdir -p /sdcard/Download/nfb-client-acceptance")
        device.executeShellCommand("screencap -p /sdcard/Download/nfb-client-acceptance/$name.png")
    }

    @Test fun timestampKeepsItsInstantAndMissingValuesAreNotInvented() {
        assertEquals("2026/10/1 08:00", displayModified("2026-10-01T00:00:00Z", ZoneId.of("Asia/Shanghai")))
        assertEquals("2026/10/1 00:00", displayModified("2026-10-01T08:00:00.123456789+08:00", ZoneId.of("UTC")))
        assertNull(displayModified("")); assertNull(displayModified("not-a-time"))
        assertNull(displayModified("0001-01-01T00:00:00Z"))
    }

    @Test fun authenticatedListingAndSearchReuseModifiedFieldAndDetailActions(): Unit = runBlocking {
        val source = ClientSearchTest.Fixture(listOf("影片.mkv", "照片.png", "目录"), ownedPreviewPng(), "2026-10-01T00:00:00Z")
        val store = ProfileStore(ClientDatabase.get(instrumentation.targetContext), CredentialVault(instrumentation.targetContext))
        val profile = store.save(ServerProfile(name = "Metadata fixture", address = source.url))
        val expected = SimpleDateFormat("yyyy/M/d HH:mm", Locale.ROOT).format(Date(1790812800000L))
        lateinit var model: ClientModel
        activity.scenario.onActivity { model = ViewModelProvider(it)[ClientModel::class.java] }
        try {
            withContext(Dispatchers.Main) { model.selectProfile(profile); model.connectDraft(profile.name, source.url, BackendKind.NAS, "one", "fixture-only", "direct") }
            withTimeout(10_000) { model.state.first { it.connected && !it.busy && it.files.size == 3 } }
            assertTrue(model.state.value.files.all { it.modified == "2026-10-01T00:00:00Z" })
            withContext(Dispatchers.Main) { model.fileLayout(FileLayout.LIST) }
            withTimeout(5000) { model.state.first { it.fileLayout == FileLayout.LIST } }
            assertTrue(device.wait(Until.hasObject(By.textContains(expected)), 5000))
            capture("file-list-modified")
            text("影片.mkv").longClick(); text("文件详情"); text("MKV 视频"); text("修改时间"); text(expected)
            assertEquals(0, source.metadataReads.get())
            capture("file-details-modified"); text("关闭").click()
            withContext(Dispatchers.Main) { model.openSearch(); model.search.query("影片.mkv"); model.search.submit() }
            withTimeout(5000) { model.search.state.first { it.ending == SearchEnding.COMPLETED } }
            assertTrue(device.wait(Until.hasObject(By.desc("搜索结果")), 5000))
            val result = device.wait(Until.findObject(By.clazz("android.widget.TextView").text("影片.mkv")), 5000) ?: error("Search result missing")
            result.longClick(); text("文件详情"); text("MKV 文件"); text("大小"); text("修改时间"); text(expected); text("打开")
            assertEquals(0, source.metadataReads.get())
            capture("search-details-modified"); text("关闭").click()
        } catch (error: Throwable) { capture("file-metadata-failure"); throw error }
        finally { withContext(Dispatchers.Main) { model.disconnect() }; store.remove(profile); source.close() }
    }
}
