package io.github.kkwans.nasfilebrowser

import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.StaleObjectException
import io.github.kkwans.nasfilebrowser.data.*
import io.github.kkwans.nasfilebrowser.upload.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Real Go/HTTP cleanup and native controls; the remote byte authority is owned. */
@RunWith(AndroidJUnit4::class)
internal class UploadCancellationTest : LibraryUiHarness() {
    private fun clickable(find: () -> UiObject2): UiObject2 {
        val deadline = android.os.SystemClock.elapsedRealtime() + 5000
        while (true) {
            try {
                var node = find()
                while (!node.isClickable) node = node.parent ?: error("Missing clickable owner")
                return node
            } catch (stale: StaleObjectException) {
                if (android.os.SystemClock.elapsedRealtime() >= deadline) throw stale
                device.waitForIdle()
            }
        }
    }
    private fun clickAction(label: String) {
        clickable { action(label) }.click()
    }
    private fun clickText(label: String) {
        clickable { text(label) }.click()
    }
    private fun confirmCancel(name: String) {
        clickAction("取消上传：$name")
        clickable {
            var dialog = text("取消这项上传？")
            while (dialog.findObject(By.text("保留任务")) == null) dialog = dialog.parent ?: error("Owned cancellation dialog missing")
            dialog.findObject(By.text("取消上传")) ?: error("Owned confirmation missing")
        }.click()
    }
    @Test fun cancellationUsesOriginalSessionKeepsPublishedFilesAndRetriesUnconfirmedCleanup(): Unit = runBlocking {
        val context = instrumentation.targetContext
        val database = ClientDatabase.get(context); val dao = database.uploads()
        val store = ProfileStore(database, CredentialVault(context))
        val previous = database.profiles().activeSession()
        val server = OwnedUploadFixture().apply { durableCancellation = true }
        val profile = store.save(ServerProfile(name = "Owned cleanup", address = server.url))
        val rows = mutableListOf<UploadRecord>()
        suspend fun settled(id: String, status: String) {
            withTimeout(10_000) { model.uploads.state.first { !it.busy && it.items.any { row -> row.id == id && row.status == status } } }
        }
        activity.scenario.onActivity { model = ViewModelProvider(it)[io.github.kkwans.nasfilebrowser.app.ClientModel::class.java] }
        try {
            main { model.selectProfile(profile); model.connectDraft(profile.name, profile.address, BackendKind.NAS, "fixture", "owned", "direct") }
            withTimeout(10_000) { model.state.first { it.connected && !it.busy } }
            val account = store.accounts(profile).single()
            suspend fun row(name: String, bytes: ByteArray, original: ByteArray? = null): UploadRecord {
                val id = UUID.randomUUID().toString(); val path = "/$name"
                server.files[path] = bytes; server.lengths[path] = 8; server.uploads[path] = "fileway-$id"
                if (original != null) server.published[path] = original
                return UploadRecord(id, dao.lastJobId() + 1, account.key, profile.id, profile.sourceRevision,
                    "content://fixture.invalid/missing", name, "application/octet-stream", 8, 1, path, path, "/", "Owned cleanup",
                    overwrite = original != null, remoteCreated = true, status = "paused", uploaded = 4,
                    createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis()).also { dao.insert(it); rows.add(it) }
            }
            val original = "original published target".toByteArray()
            val partial = row("owned-partial.bin", "abcd".toByteArray(), original)
            main { model.openUploads() }
            clickAction("取消上传：${partial.name}"); text("取消这项上传？"); clickText("保留任务")
            assertEquals("paused", dao.get(partial.id)!!.status); assertEquals(0, server.deletes.get())
            confirmCancel(partial.name)
            settled(partial.id, "canceled")
            assertFalse(server.files.containsKey(partial.targetPath))
            assertArrayEquals(original, server.published[partial.targetPath])
            assertFalse(device.hasObject(By.desc("继续上传：${partial.name}")))
            val complete = row("owned-complete.bin", "complete".toByteArray())
            val before = server.deletes.get()
            confirmCancel(complete.name)
            settled(complete.id, "completed")
            assertEquals(before, server.deletes.get()); assertEquals(8L, dao.get(complete.id)!!.uploaded)
            assertArrayEquals("complete".toByteArray(), server.files[complete.targetPath])
            val retry = row("owned-retry.bin", "abcd".toByteArray())
            server.includeTusVersion = false
            confirmCancel(retry.name)
            settled(retry.id, "cancel_failed")
            assertEquals(before, server.deletes.get())
            server.includeTusVersion = true; server.deleteStatus = 500
            val previousGeneration = dao.get(retry.id)!!.generation
            clickAction("重试清理上传：${retry.name}")
            withTimeout(10_000) { dao.observe().first { items -> items.any { it.id == retry.id && it.generation > previousGeneration && it.status == "cancel_failed" } } }
            settled(retry.id, "cancel_failed")
            assertTrue(server.files.containsKey(retry.targetPath))
            server.deleteStatus = 0
            clickAction("重试清理上传：${retry.name}")
            settled(retry.id, "canceled")
            assertFalse(server.files.containsKey(retry.targetPath))
            assertArrayEquals(original, server.published[partial.targetPath])
            capture("upload-cancellation")
        } finally { withContext(NonCancellable) {
            main { model.disconnect() }
            for (row in rows) { UploadScheduler.stop(context, row); UploadRuntime.get(context).awaitStopped(row.id); dao.removeRecord(row.id) }
            store.remove(profile); server.close()
            previous?.let { if (database.profiles().account(it.accountKey) != null) database.profiles().saveActiveSession(it) }
        } }
    }
}
