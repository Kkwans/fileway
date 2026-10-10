package io.github.kkwans.nasfilebrowser

import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiObject2
import io.github.kkwans.nasfilebrowser.data.*
import io.github.kkwans.nasfilebrowser.download.*
import io.github.kkwans.nasfilebrowser.upload.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
internal class UploadRestartTest : LibraryUiHarness() {
    private fun clickable(node: UiObject2): UiObject2 {
        var owner = node
        while (!owner.isClickable) owner = owner.parent ?: error("Clickable owner missing")
        return owner
    }
    private fun confirm() {
        var dialog = text("从零重新开始上传？")
        while (dialog.findObject(By.text("保留原任务")) == null) dialog = dialog.parent ?: error("Restart confirmation missing")
        clickable(dialog.findObject(By.text("确认重新开始")) ?: error("Restart confirmation button missing")).click()
    }
    @Test fun changedSourceRequiresConfirmationFreshSessionAndConfirmedCleanupAndKeepsCompletedFiles(): Unit = runBlocking {
        val context = instrumentation.targetContext
        if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        val database = ClientDatabase.get(context); val dao = database.uploads(); val store = ProfileStore(database, CredentialVault(context))
        val previous = database.profiles().activeSession(); val server = OwnedUploadFixture().apply { durableCancellation = true }
        val profile = store.save(ServerProfile(name = "Owned restart acceptance", address = server.url))
        val target = DownloadTarget(context); val id = UUID.randomUUID().toString()
        val local = DownloadRecord(id, 0, "owned", profile.id, 0, "/owned.bin", "/owned.bin", "fileway-owned-restart-$id.bin", "blob", 8,
            "owned", "owned", "Owned source", "", createdAt = 1, updatedAt = 1, relativeDirectory = "owned-upload-restart/$id")
        val uri = target.allocate(local); val saved = local.copy(localUri = uri.toString(), status = "completed", downloaded = 8)
        val updated = "NEW original document contents".toByteArray()
        activity.scenario.onActivity { model = ViewModelProvider(it)[io.github.kkwans.nasfilebrowser.app.ClientModel::class.java] }
        try {
            context.contentResolver.openOutputStream(uri, "w")!!.use { it.write("original".toByteArray()) }; target.complete(saved)
            val oldSource = UploadSources(context).read(uri)
            main { model.selectProfile(profile); model.connectDraft(profile.name, profile.address, BackendKind.NAS, "fixture", "owned", "direct") }
            withTimeout(10_000) { model.state.first { it.connected && !it.busy } }
            withTimeout(5000) { while (true) {
                var focused = false; activity.scenario.onActivity { focused = it.hasWindowFocus() }
                if (focused) break; delay(50)
            } }
            val account = store.accounts(profile).single()
            suspend fun row(path: String, bytes: ByteArray): UploadRecord {
                val key = UUID.randomUUID().toString(); server.files[path] = bytes; server.lengths[path] = 8; server.uploads[path] = "fileway-$key"
                return UploadRecord(key, dao.lastJobId() + 1, account.key, profile.id, profile.sourceRevision, uri.toString(), oldSource.name,
                    oldSource.mime, 8, oldSource.modified, path, path, "/", "Owned restart", remoteCreated = true, status = "paused", uploaded = 4,
                    createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis()).also { dao.insert(it) }
            }
            val first = row("/owned-restart.bin", "abcd".toByteArray())
            context.contentResolver.openOutputStream(uri, "w")!!.use { it.write(updated) }
            assertEquals(updated.size.toLong(), context.contentResolver.openFileDescriptor(uri, "r")!!.use { it.statSize })
            assertThrows(IllegalStateException::class.java) { UploadSources(context).reauthorize(first, uri) }
            assertEquals(updated.size.toLong(), UploadSources(context).read(uri).size)
            assertEquals(first, dao.get(first.id)); assertEquals(4L, dao.get(first.id)!!.uploaded)
            assertEquals(0, server.deletes.get()); assertArrayEquals("abcd".toByteArray(), server.files[first.targetPath])
            main { model.openUploads(); model.uploads.prepareRestart(first.id, first.generation, uri) }
            withTimeout(5000) { model.uploads.state.first { !it.busy && it.restart != null } }
            text("从零重新开始上传？"); clickable(text("保留原任务")).click()
            assertEquals(0, server.deletes.get()); assertEquals(4L, dao.get(first.id)!!.uploaded)
            main { model.uploads.prepareRestart(first.id, first.generation, uri) }
            withTimeout(5000) { model.uploads.state.first { !it.busy && it.restart != null } }
            capture("upload-restart-confirmation"); confirm()
            val complete = withTimeout(15_000) { model.uploads.state.first { !it.busy && it.items.any { item -> item.profileId == profile.id && item.complete } } }
                .items.single { it.profileId == profile.id && it.complete }
            assertNotEquals(first.id, complete.id); assertNotEquals(first.jobId, complete.jobId)
            assertEquals("fileway-${complete.id}", server.uploads[first.targetPath]); assertEquals(first.accountKey, complete.accountKey)
            assertEquals(first.targetWire, complete.targetWire); assertEquals(updated.size.toLong(), complete.expectedSize)
            assertEquals(updated.size.toLong(), complete.uploaded); assertArrayEquals(updated, server.files[first.targetPath])
            assertEquals("restarted", dao.get(first.id)!!.status); assertEquals(4L, dao.get(first.id)!!.uploaded)
            assertFalse(dao.get(first.id)!!.canResume); assertFalse(dao.get(first.id)!!.canRestart)
            assertEquals(0, dao.progress(first.id, first.generation, 8, System.currentTimeMillis()))

            val failed = row("/owned-retry-restart.bin", "abcd".toByteArray())
            val count = dao.observe().first().count { it.profileId == profile.id }
            server.deleteStatus = 500
            main { model.uploads.prepareRestart(failed.id, failed.generation, uri) }
            withTimeout(5000) { model.uploads.state.first { !it.busy && it.restart != null } }; confirm()
            withTimeout(10_000) { model.uploads.state.first { !it.busy && it.items.any { item -> item.id == failed.id && item.status == "cancel_failed" } } }
            assertNull(model.uploads.state.value.restart)
            assertEquals(count, dao.observe().first().count { it.profileId == profile.id }); assertArrayEquals("abcd".toByteArray(), server.files[failed.targetPath])
            server.deleteStatus = 0
            val retryRecord = dao.get(failed.id)!!
            main { model.uploads.cancel(retryRecord) }
            withTimeout(10_000) { model.uploads.state.first { !it.busy && it.items.any { item -> item.id == failed.id && item.status == "canceled" } } }
            val canceled = dao.get(failed.id)!!
            main { model.uploads.prepareRestart(canceled.id, canceled.generation, uri) }
            withTimeout(5000) { model.uploads.state.first { !it.busy && it.restart != null } }; confirm()
            withTimeout(15_000) { model.uploads.state.first { !it.busy && it.items.any { item -> item.profileId == profile.id && item.targetWire == failed.targetWire && item.complete } } }
            assertArrayEquals(updated, server.files[failed.targetPath])

            val published = row("/owned-already-complete.bin", "complete".toByteArray())
            val before = dao.observe().first().count { it.profileId == profile.id }; val deleted = server.deletes.get()
            main { model.uploads.prepareRestart(published.id, published.generation, uri) }
            withTimeout(5000) { model.uploads.state.first { !it.busy && it.restart != null } }; confirm()
            withTimeout(10_000) { model.uploads.state.first { !it.busy && it.items.any { item -> item.id == published.id && item.complete } } }
            assertEquals(before, dao.observe().first().count { it.profileId == profile.id }); assertEquals(deleted, server.deletes.get())
            assertArrayEquals("complete".toByteArray(), server.files[published.targetPath])
            assertArrayEquals(updated, context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }); capture("upload-restart-completed")
        } finally { withContext(NonCancellable) {
            main { model.disconnect() }
            for (row in dao.observe().first().filter { it.profileId == profile.id }) {
                UploadScheduler.stop(context, row); UploadRuntime.get(context).awaitStopped(row.id); dao.removeRecord(row.id)
            }
            target.delete(saved); store.remove(profile); server.close()
            previous?.let { if (database.profiles().account(it.accountKey) != null) database.profiles().saveActiveSession(it) }
        } }
    }
}
