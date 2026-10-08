package io.github.kkwans.nasfilebrowser

import android.net.Uri
import android.content.Intent
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
import org.junit.rules.RuleChain
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Only owned fixture profile/file/record; preserves the previously active account. */
@RunWith(AndroidJUnit4::class)
class DownloadPlaybackTest {
    private val activity = ActivityScenarioRule(MainActivity::class.java)
    @get:Rule val rules: RuleChain = RuleChain.outerRule(OwnedUiTraceRule()).around(activity)
    @Test fun savedPrefixStreamsMissingRangesThenCompletedFilePlaysOffline(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val db = ClientDatabase.get(context); val dao = db.downloads()
        val previous = db.profiles().activeSession()
        val bytes = instrumentation.context.assets.open("media/fixture.mkv").use { it.readBytes() }
        val source = NativePlaybackTest.Fixture(bytes, download = true)
        val store = ProfileStore(db, CredentialVault(context))
        val profile = store.save(ServerProfile(name = "Owned download fixture", address = source.url))
        val target = DownloadTarget(context)
        var saved: DownloadRecord? = null
        lateinit var model: ClientModel
        lateinit var launchIntent: Intent
        activity.scenario.onActivity {
            model = ViewModelProvider(it)[ClientModel::class.java]
            launchIntent = Intent(it.intent)
        }
        suspend fun main(action: () -> Unit) = withContext(Dispatchers.Main) { action() }
        suspend fun waitFor(stage: String, predicate: () -> Boolean) {
            try { withTimeout(20_000) { while (!predicate()) delay(100) } }
            catch (failure: TimeoutCancellationException) {
                UiDevice.getInstance(instrumentation).executeShellCommand("mkdir -p /sdcard/Download/nfb-client-acceptance")
                UiDevice.getInstance(instrumentation).executeShellCommand("screencap -p /sdcard/Download/nfb-client-acceptance/download-prefix-failure.png")
                throw AssertionError("$stage: player=${model.player.state.value}, trace=${model.player.diagnosticSnapshot()}, raw=${source.rawRequests.get()}, unexpected=${source.unexpected.get()}, selected=${model.state.value.selected?.downloadId}, busy=${model.state.value.busy}, tab=${model.state.value.tab}, client=${model.state.value.error}, download=${model.downloads.state.value.error}", failure)
            }
        }
        try {
            main { model.selectProfile(profile); model.connectDraft(profile.name, source.url, BackendKind.NAS, "fixture", "fixture-only", "direct") }
            waitFor("owned login") { model.state.value.connected && !model.state.value.busy }
            val account = store.accounts(profile).single()
            val id = UUID.randomUUID().toString(); val prefix = minOf(bytes.size / 4, 8192)
            val record = DownloadRecord(id, dao.lastJobId() + 1, account.key, profile.id, profile.sourceRevision,
                "/fixture.mkv", "/fixture.mkv", "fileway-owned-download-$id.mkv", "video", bytes.size.toLong(), "owned-download-v1", "${bytes.size}/owned-download-v1", "Owned fixture", "",
                status = "paused", downloaded = prefix.toLong(), createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis())
            val uri = target.allocate(record); saved = record.copy(localUri = uri.toString())
            context.contentResolver.openOutputStream(uri, "w")!!.use { it.write(bytes, 0, prefix) }
            dao.insert(saved)
            main { model.tab("downloads") }
            val device = UiDevice.getInstance(instrumentation)
            assertNotNull(device.wait(Until.findObject(By.text("本机下载")), 5000))
            withTimeout(5000) { model.downloads.state.first { it.items.any { row -> row.id == id } } }
            waitFor("download window focus") {
                var focused = false
                activity.scenario.onActivity { focused = it.hasWindowFocus() }
                focused
            }
            fun clickOwned(description: String) {
                device.waitForIdle()
                var button = device.wait(Until.findObject(By.desc(description)), 5000)
                    ?: error("Missing owned download action $description")
                while (!button.isClickable) button = button.parent ?: error("Owned download action has no clickable owner")
                assertTrue(button.isEnabled)
                button.click()
            }
            clickOwned("打开下载：${record.name}")
            waitFor("prefix playback") { model.player.state.value.let { it.firstFrameRendered && it.playing && it.seekable && it.positionMs > 600 } }
            assertTrue("The missing range must actually use authenticated transport", source.rawRequests.get() > 0)
            assertEquals(prefix.toLong(), dao.get(id)!!.downloaded)
            assertEquals("paused", dao.get(id)!!.status)
            main { model.player.seek(8000) }
            waitFor("range seek") { model.player.state.value.let { it.positionMs >= 7500 && it.phase != "正在跳转" && !it.waitingForBuffer && it.playing } }
            main { model.leavePlayer() }
            clickOwned("继续下载：${record.name}")
            withTimeout(20_000) { dao.observe().first { rows -> rows.any { it.id == id && it.complete } } }
            val complete = dao.get(id)!!; saved = complete
            assertEquals(bytes.size.toLong(), complete.downloaded)
            val local = context.contentResolver.openInputStream(Uri.parse(complete.localUri))!!.use { it.readBytes() }
            assertArrayEquals("Resume must append the original remaining bytes", bytes, local)
            DownloadRuntime.get(context).awaitStopped(id)
            source.close()
            val requests = source.rawRequests.get()
            main { model.openDownload(complete) }
            waitFor("offline first frame") { model.player.state.value.let { it.firstFrameRendered && it.playing && it.positionMs > 600 } }
            main { model.player.seek(4000) }
            waitFor("offline seek") { model.player.state.value.let { it.positionMs >= 3500 && it.phase != "正在跳转" && !it.waitingForBuffer && it.playing } }
            assertEquals(requests, source.rawRequests.get())
            device.executeShellCommand("mkdir -p /sdcard/Download/nfb-client-acceptance")
            device.executeShellCommand("screencap -p /sdcard/Download/nfb-client-acceptance/download-offline-phone.png")
            // Exercise the actual notification PendingIntent while the viewer
            // owns the foreground: the original Activity/model must navigate.
            OwnedUiTraceRule.trace("notification-send")
            DownloadNotice.notification(context, complete).contentIntent.send()
            withTimeout(5000) { model.state.first { it.tab == "downloads" && it.selected == null && it.image == null } }
            assertTrue(device.wait(Until.hasObject(By.text("本机下载")), 5000))
            activity.scenario.onActivity {
                assertSame("Notification must reuse the original page/model", model, ViewModelProvider(it)[ClientModel::class.java])
                assertFalse("Notification navigation must be consumed", it.intent.hasExtra("open_downloads"))
            }
            OwnedUiTraceRule.trace("notification-navigation-confirmed")
        } finally {
            withContext(NonCancellable) {
                // ActivityScenario matches lifecycle callbacks by launch Intent
                // action/categories. onNewIntent legitimately replaces that Intent;
                // restore only the harness identity after all notification assertions
                // so close() observes the real DESTROYED callback instead of ignoring it.
                activity.scenario.onActivity { it.intent = launchIntent }
                main { model.leavePlayer(); model.disconnect() }
                saved?.let { item ->
                    DownloadScheduler.pause(context, item.id); DownloadRuntime.get(context).awaitStopped(item.id)
                    target.delete(item); dao.removeRecord(item.id)
                }
                source.close(); store.remove(profile)
                previous?.let { if (db.profiles().account(it.accountKey) != null) db.profiles().saveActiveSession(it) }
            }
        }
    }
}
