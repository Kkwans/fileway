package io.github.kkwans.nasfilebrowser

import android.net.Uri
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import io.github.kkwans.nasfilebrowser.app.ClientModel
import io.github.kkwans.nasfilebrowser.data.*
import io.github.kkwans.nasfilebrowser.download.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class BatchDownloadTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val database = ClientDatabase.get(context)
    private val dao = database.downloads()
    private suspend fun main(action: () -> Unit) = withContext(Dispatchers.Main) { action() }
    private suspend fun fixture(block: suspend (ClientModel, ServerProfile, ByteArray) -> Unit) {
        val id = UUID.randomUUID().toString()
        val bytes = instrumentation.context.assets.open("media/fixture.mkv").use { it.readBytes() }
        val source = NativePlaybackTest.Fixture(bytes, download = true, videos = listOf("owned-$id-one.mkv", "owned-$id-two.mkv"))
        val previous = database.profiles().activeSession()
        val store = ProfileStore(database, CredentialVault(context))
        val profile = store.save(ServerProfile(name = "Owned batch download", address = source.url))
        lateinit var model: ClientModel
        activity.scenario.onActivity { model = ViewModelProvider(it)[ClientModel::class.java] }
        try {
            main { model.selectProfile(profile); model.connectDraft(profile.name, source.url, BackendKind.NAS, "fixture", "fixture-only", "direct") }
            withTimeout(15000) { model.state.first { it.connected && !it.busy && it.files.size == 2 } }
            block(model, profile, bytes)
        } finally { withContext(NonCancellable) {
            main { model.disconnect() }
            for (record in dao.observe().first().filter { it.profileId == profile.id }) {
                DownloadScheduler.pause(context, record.id)
                DownloadRuntime.get(context).awaitStopped(record.id)
                dao.get(record.id)?.let { if (it.localUri.isNotEmpty()) DownloadTarget(context).delete(it) }
                dao.removeRecord(record.id)
            }
            source.close(); store.remove(profile)
            previous?.let { if (database.profiles().account(it.accountKey) != null) database.profiles().saveActiveSession(it) }
        } }
    }

    @Test fun globalSelectionQueuesBothFilesAndDownloadsOriginalBytes(): Unit = runBlocking {
        fixture { model, profile, bytes ->
            val device = UiDevice.getInstance(instrumentation)
            fun text(value: String) = device.wait(Until.findObject(By.text(value)), 5000) ?: error("Missing $value")
            text("多选").click(); text("全选").click(); text("已选 2 项")
            text("反选").click(); text("已选 0 项")
            text("全选").click()
            val download = device.wait(Until.findObject(By.desc("批量下载")), 5000) ?: error("Missing batch download")
            download.click()
            val rows = withTimeout(30000) { dao.observe().first { values -> values.count { it.profileId == profile.id && it.complete } == 2 } }
                .filter { it.profileId == profile.id }
            assertEquals(2, rows.size)
            assertEquals(2, rows.map { it.jobId }.distinct().size)
            rows.forEach { record ->
                assertArrayEquals(bytes, context.contentResolver.openInputStream(Uri.parse(record.localUri))!!.use { it.readBytes() })
            }
            withTimeout(5000) { model.downloads.state.first { !it.busy } }
            assertNull(model.downloads.state.value.error)
            text("已选 0 项")
            device.executeShellCommand("mkdir -p /sdcard/Download/nfb-client-acceptance")
            device.executeShellCommand("screencap -p /sdcard/Download/nfb-client-acceptance/batch-download.png")
            device.pressBack()
            assertTrue(device.wait(Until.hasObject(By.text("多选")), 5000))
            assertFalse(device.hasObject(By.desc("批量下载")))
        }
    }

    @Test fun sourceChangeStopsRemainingQueueWithoutErasingCreatedTask(): Unit = runBlocking {
        fixture { model, profile, _ ->
            val files = model.state.value.files.toList()
            var callbacks = 0
            main { model.downloadFiles(files) { callbacks++; model.disconnect() } }
            withTimeout(15000) { model.downloads.state.first { !it.busy && it.error != null } }
            assertEquals(1, callbacks)
            assertEquals(1, dao.observe().first().count { it.profileId == profile.id })
            assertTrue(model.downloads.state.value.error!!.contains("1 / 2"))
        }
    }
}
