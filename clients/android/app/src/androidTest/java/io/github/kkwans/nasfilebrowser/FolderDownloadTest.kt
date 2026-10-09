package io.github.kkwans.nasfilebrowser

import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.kkwans.nasfilebrowser.app.*
import io.github.kkwans.nasfilebrowser.data.*
import io.github.kkwans.nasfilebrowser.download.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
internal class FolderDownloadTest : LibraryUiHarness() {
    @Test fun folderConfirmationKeepsNestedOriginalFilesAndLocalDirectoryUris(): Unit = runBlocking {
        val context = instrumentation.targetContext
        // This case accepts file/byte/directory behavior. Do not let a fresh
        // emulator's unrelated notification prompt obscure its native controls.
        // Notification denial/recovery belongs to the separate permission flow.
        if (android.os.Build.VERSION.SDK_INT >= 33)
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        val database = ClientDatabase.get(context)
        val dao = database.downloads()
        val store = ProfileStore(database, CredentialVault(context))
        val target = DownloadTarget(context)
        val previous = database.profiles().activeSession()
        val originalTree = target.selectedTree()
        val root = "fileway-owned-folder-${UUID.randomUUID()}"
        val sub = "$root/子目录 +% #"
        val media = instrumentation.context.assets.open("media/fixture.mkv").use { it.readBytes() }
        val source = NativePlaybackTest.Fixture(media, videos = listOf("$root/原片.mkv", "$sub/原片.mkv"),
            download = true, directories = listOf(root, sub, "$root/空目录"))
        val profile = store.save(ServerProfile(name = "Owned folder download", address = source.url))
        activity.scenario.onActivity { model = ViewModelProvider(it)[ClientModel::class.java] }
        try {
            target.selectTree(null)
            main { model.selectProfile(profile); model.connectDraft(profile.name, source.url, BackendKind.NAS, "fixture", "fixture-only", "direct") }
            withTimeout(15_000) { model.state.first { it.connected && !it.busy && it.files.size == 1 } }
            val folder = model.state.value.files.single()
            withTimeout(5000) {
                while (true) { var focused = false; activity.scenario.onActivity { focused = it.hasWindowFocus() }; if (focused) break; delay(50) }
            }
            fileDetails(root); action("下载到本机").click()
            withTimeout(10_000) { model.downloads.state.first { !it.busy && it.folderPlan != null } }
            text("开始下载")
            assertTrue("No records before confirmation", dao.observe().first().none { it.profileId == profile.id })
            val plan = model.downloads.state.value.folderPlan!!
            assertEquals(2, plan.entries.size); assertEquals(media.size * 2L, plan.bytes); assertEquals(1, plan.emptyDirectories)
            capture("folder-download-confirmation")
            text("取消").click()
            assertTrue(dao.observe().first().none { it.profileId == profile.id })
            fileDetails(root); action("下载到本机").click()
            withTimeout(10_000) { model.downloads.state.first { !it.busy && it.folderPlan != null } }
            text("开始下载").click()
            val saved = withTimeout(30_000) { dao.observe().first { rows -> rows.count { it.profileId == profile.id && it.complete } == 2 } }
                .filter { it.profileId == profile.id }
            assertEquals(setOf(root, sub), saved.map { it.relativeDirectory }.toSet())
            for (item in saved) {
                assertArrayEquals(media, context.contentResolver.openInputStream(Uri.parse(item.localUri))!!.use { it.readBytes() })
                context.contentResolver.query(Uri.parse(item.localUri), arrayOf(MediaStore.MediaColumns.RELATIVE_PATH), null, null, null)!!.use { cursor ->
                    assertTrue(cursor.moveToFirst()); assertEquals("Download/fileway/${item.relativeDirectory}/", cursor.getString(0))
                }
                assertEquals("primary:Download/fileway/${item.relativeDirectory}", DocumentsContract.getDocumentId(target.directoryUri("", item.relativeDirectory)))
                assertEquals(target.directoryUri("", item.relativeDirectory), target.directoryIntent("", item.relativeDirectory).data)
            }
            main { model.tab("downloads") }
            text("本机下载"); capture("folder-download-completed")
            assertNull(model.downloads.state.value.error)
        } finally { withContext(NonCancellable) {
            main { model.disconnect() }
            for (item in dao.observe().first().filter { it.profileId == profile.id }) {
                DownloadScheduler.pause(context, item.id); DownloadRuntime.get(context).awaitStopped(item.id)
                dao.get(item.id)?.let { if (it.localUri.isNotEmpty()) target.delete(it) }
                dao.removeRecord(item.id)
            }
            target.selectTree(originalTree.takeIf { it.isNotEmpty() }?.let(Uri::parse))
            store.remove(profile); source.close()
            previous?.let { if (database.profiles().account(it.accountKey) != null) database.profiles().saveActiveSession(it) }
        } }
    }

    @Test fun scannerDeduplicatesNestedSelectionPreservesOpaquePathsAndRejectsForeignChildren(): Unit = runBlocking {
        fun file(path: String, directory: Boolean, wire: String = SearchResult.encodePath(path)) = ResourceRef(path, wire, path.substringAfterLast('/'), directory, "", if (directory) 0 else 12, "owned-v1")
        val root = file("/owned", true)
        val child = file("/owned/旧� +% #.mkv", false, "/owned/%FF%20%2B%25%20%23.mkv")
        fun row(file: ResourceRef) = JSONObject().put("path", file.path).put("wirePath", file.wirePath).put("name", file.name)
            .put("isDir", file.directory).put("size", file.size).put("modified", file.modified)
        val endpoints = arrayListOf<String>()
        val plan = scanFolderDownloads(listOf(root, child), { true }, { endpoint ->
            endpoints.add(endpoint)
            if (endpoint.endsWith("metadata=1")) if (endpoint.contains("%FF")) row(child) else row(root)
            else JSONObject().put("items", JSONArray().put(row(child)))
        }, {})
        assertEquals(1, plan.roots.size); assertEquals(child.wirePath, plan.entries.single().file.wirePath)
        assertEquals("owned", plan.entries.single().relativeDirectory)
        assertTrue(endpoints.any { it.contains("%FF%20%2B%25%20%23") })
        var rejected = false
        try { scanFolderDownloads(listOf(root), { true }, { endpoint ->
            if (endpoint.endsWith("metadata=1")) row(root) else JSONObject().put("items", JSONArray().put(row(file("/elsewhere/private.mkv", false))))
        }, {}) } catch (_: IllegalStateException) { rejected = true }
        assertTrue("Foreign source must never become a task", rejected)
        var current = true; rejected = false
        try { scanFolderDownloads(listOf(root), { current }, { endpoint ->
            current = false; row(root)
        }, {}) } catch (_: IllegalStateException) { rejected = true }
        assertTrue("Late old-account results are rejected", rejected)
        for (path in listOf("../other", "/absolute", "owned//other", "owned/..", "owned/with\\slash"))
            assertThrows(IllegalArgumentException::class.java) { downloadDirectorySegments(path) }
    }
}
