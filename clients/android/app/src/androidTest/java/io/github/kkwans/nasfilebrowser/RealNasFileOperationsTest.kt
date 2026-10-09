package io.github.kkwans.nasfilebrowser

import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import io.github.kkwans.nasfilebrowser.data.*
import io.github.kkwans.nasfilebrowser.ui.collectionDescription
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** Explicit external acceptance against an owned, marked NAS directory only.
 * Uses ordinary MainActivity/product controls and an independent NAS session.
 */
@RunWith(AndroidJUnit4::class)
internal class RealNasFileOperationsTest : LibraryUiHarness() {
    private fun clickText(label: String) {
        var button = text(label)
        while (!button.isClickable) button = button.parent ?: error("Missing clickable control")
        assertTrue(button.isEnabled); button.click()
    }
    private fun ownedTag(label: String) {
        repeat(12) {
            val found = device.findObject(By.text(label))
            if (found != null) { var button = found; while (!button.isClickable) button = button.parent ?: error("Missing tag row"); button.click(); return }
            assertTrue(device.swipe(device.displayWidth / 2, device.displayHeight * 72 / 100, device.displayWidth / 2, device.displayHeight * 45 / 100, 20))
        }
        error("Owned tag was not visible")
    }
    private suspend fun metadata(api: NasSession, path: String): JSONObject? = try {
        api.request("GET", "/api/resources${SearchResult.encodePath(path)}?metadata=1")
    } catch (failure: ServiceException) { if (failure.status == 404) null else throw failure }
    private suspend fun raw(api: NasSession, path: String): ByteArray {
        val lease = api.lease(path, SearchResult.encodePath(path))
        try {
            return withContext(Dispatchers.IO) {
                val connection = URL(lease).openConnection() as HttpURLConnection
                connection.connectTimeout = 10_000; connection.readTimeout = 10_000
                try { check(connection.responseCode == 200); connection.inputStream.use { input ->
                    val bytes = java.io.ByteArrayOutputStream(); val buffer = ByteArray(4096)
                    while (true) { val count = input.read(buffer); if (count < 0) break; check(bytes.size() + count <= 2 * 1024 * 1024); bytes.write(buffer, 0, count) }
                    bytes.toByteArray()
                } }
                finally { connection.disconnect() }
            }
        } finally { io.github.kkwans.nasfilebrowser.core.NativeTransport.call(JSONObject().put("op", "revoke").put("url", lease)) }
    }
    private suspend fun stage(value: String) {
        instrumentation.sendStatus(2, android.os.Bundle().apply { putString("stream", "REAL_FILEOPS_STAGE=$value\n") })
        if (value == "done") return
        val reply = privateAdbConfiguration("REAL_FILEOPS_CHECKPOINT", "fileway-owned-step-")
        check(reply.getString("stage") == value && reply.getBoolean("verified")) { "Independent NAS checkpoint failed" }
    }
    private suspend fun queuedTransfer(folder: String, label: String) {
        action("目标目录：$folder").click()
        withTimeout(10_000) { model.fileOperations.state.first { it.transfer?.let { draft -> !draft.loading && draft.directory.label == folder } == true } }
        clickText("检查此目录")
        withTimeout(10_000) { model.fileOperations.state.first { !it.changing && it.transfer?.reviewed == true } }
        clickText("$label 1 项")
        withTimeout(15_000) { model.fileOperations.state.first { !it.changing && it.lastTask?.let { task -> task.type == if (label == "复制") "file.copy" else "file.move" } == true } }
        withTimeout(15_000) { model.fileOperations.state.first { it.lastTask?.status == "completed" } }
        assertNull(model.fileOperations.state.value.taskError)
    }
    private fun restoreOwnedTrash(name: String) {
        var card = text(name)
        repeat(8) {
            val restore = card.findObject(By.text("恢复"))
            if (restore != null) { restore.click(); return }
            card = card.parent ?: error("Missing owned trash card")
        }
        error("Owned restore control missing")
    }
    @ExternalNetworkAcceptance
    @Test fun ordinaryAppFileActionsPreserveRealNasMetadataAndOriginalBytes(): Unit = runBlocking {
        require(android.os.Build.DEVICE == "houji" && androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("nfbRealFileOperations") == "true")
        val config = privateAdbConfiguration("REAL_FILEOPS_SOCKET", "fileway-owned-")
        val nonce = UUID.fromString(config.getString("nonce")).toString()
        val root = config.getString("rootPath")
        require(root.startsWith('/') && root.split('/').none { it in setOf(".", "..") } && root.substringAfterLast('/') == "fileway-owned-files-$nonce")
        val sourceName = config.getString("sourceName"); val tagId = config.getString("tagId"); val tagName = config.getString("tagName")
        require(sourceName == "Fileway验收-${nonce.take(8)} +% #.png" && tagName == "Fileway验收-${nonce.take(8)}")
        val database = ClientDatabase.get(instrumentation.targetContext)
        val store = ProfileStore(database, CredentialVault(instrumentation.targetContext))
        val previous = database.profiles().activeSession()
        val profile = store.save(ServerProfile(name = "Owned real file acceptance $nonce", address = config.getString("baseUrl")))
        var token = config.getString("token"); config.remove("token")
        val identity = NasSession.parseIdentity(token)
        val api = NasSession.restore(profile, token, identity.id)
        val account = store.saveLogin(profile, identity.id, identity.username, token)
        token = ""
        val original = "$root/$sourceName"; val renamedName = "验收-${nonce.take(8)} +% #.png"
        val renamed = "$root/$renamedName"; val targetDirectory = "$root/相册"
        var favoriteId: String? = null
        var ownershipVerified = false
        var primaryFailure: Throwable? = null
        activity.scenario.onActivity { model = ViewModelProvider(it)[io.github.kkwans.nasfilebrowser.app.ClientModel::class.java] }
        try {
            check(api.permissions().let { it.create && it.rename && it.delete }) { "Acceptance needs authorized file permissions" }
            val marker = JSONObject(raw(api, "$root/fileway-acceptance-owner.json").toString(Charsets.UTF_8))
            check(marker.getString("nonce") == nonce && marker.getInt("schema") == 1) { "Owned directory marker mismatch" }
            val tagRows = api.array("/api/tags")
            check((0 until tagRows.length()).map(tagRows::getJSONObject).single { it.getString("id") == tagId }.getString("name") == tagName)
            ownershipVerified = true
            // Recover only the exact preflight-failure profile observed by the
            // authorized host, in its short creation window, never an active
            // account or a similarly named personal profile.
            if (config.has("recoverProfileCreatedAfter")) {
                val after = config.getLong("recoverProfileCreatedAfter"); val before = config.getLong("recoverProfileCreatedBefore")
                require(before >= after && before - after <= 60_000)
                val leaked = store.profiles.first().filter { it.name == "Owned real file acceptance" && it.address == profile.address &&
                    it.sourceRevision == 0L && it.updatedAt in after..before && it.id != profile.id }
                check(leaked.size <= 1) { "Ambiguous owned profile recovery" }
                for (owned in leaked) {
                    val accounts = store.accounts(owned)
                    check(accounts.isNotEmpty() && accounts.all { it.userId == identity.id && it.key != previous?.accountKey })
                    check(accounts.all { store.directory(it) == null })
                    store.remove(owned)
                }
            }
            check(metadata(api, root)?.getBoolean("isDir") == true && metadata(api, original)?.getBoolean("isDir") == false)
            val originalBytes = raw(api, original)
            store.saveDirectory(account, root, SearchResult.encodePath(root))
            main { model.selectProfile(profile) }
            withTimeout(10_000) { model.state.first { !it.busy && it.profile?.id == profile.id } }
            main { model.restore(account) }
            withTimeout(20_000) { model.state.first { it.connected && !it.busy && it.path == root } }
            main { model.fileLayout(FileLayout.LIST) }
            withTimeout(5000) { model.state.first { it.fileLayout == FileLayout.LIST } }
            assertTrue(device.wait(Until.hasObject(By.desc(FileLayout.LIST.collectionDescription())), 5000))
            action("新建文件或文件夹").click()
            action("新建文件夹").click()
            val field = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 5000) ?: error("Missing directory input")
            field.text = "相册"; clickText("创建")
            withTimeout(10_000) { model.state.first { it.files.any { file -> file.path == targetDirectory } } }
            assertTrue(metadata(api, targetDirectory)!!.getBoolean("isDir")); stage("created")
            fileDetails(config.getString("sourceName")); action("加入收藏").click(); clickText("保存收藏")
            val favorite = withTimeout(10_000) { model.favorites.state.first { !it.changing && it.items.any { favorite -> favorite.path == original } } }.items.single { it.path == original }
            favoriteId = favorite.id
            action("设置文件标签").click(); ownedTag(tagName); clickText("保存标记")
            withTimeout(10_000) { model.tags.state.first { !it.changing && it.items.any { tag -> tag.id == tagId && original in tag.paths } } }
            assertEquals(favoriteId, favorites(api).single { it.getString("path") == original }.getString("id"))
            assertTrue(tagPaths(api, tagId).contains(original)); capture("real-fileops-tagged"); stage("tagged")
            action("更多文件操作").click(); action("重命名文件").click()
            val nameField = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 5000) ?: error("Missing rename input")
            nameField.text = renamedName; clickText("保存名称")
            withTimeout(10_000) { model.state.first { it.files.any { file -> file.path == renamed } } }
            assertNull(metadata(api, original)); assertNotNull(metadata(api, renamed)); assertArrayEquals(originalBytes, raw(api, renamed))
            assertEquals(favoriteId, favorites(api).single { it.getString("path") == renamed }.getString("id"))
            assertTrue(tagPaths(api, tagId).contains(renamed)); stage("renamed")
            fileDetails(renamedName); action("更多文件操作").click(); action("复制文件").click()
            queuedTransfer("相册", "复制")
            assertNotNull(metadata(api, renamed)); assertArrayEquals(originalBytes, raw(api, "$targetDirectory/$renamedName")); stage("copied")
            fileDetails(renamedName); action("更多文件操作").click(); action("移动文件").click()
            queuedTransfer("相册", "移动")
            val movedName = renamedName.removeSuffix(".png") + "(1).png"; val moved = "$targetDirectory/$movedName"
            assertNull(metadata(api, renamed)); assertArrayEquals(originalBytes, raw(api, moved))
            assertEquals(favoriteId, favorites(api).single { it.getString("path") == moved }.getString("id"))
            assertTrue(tagPaths(api, tagId).contains(moved)); stage("moved")
            clickText("打开目标目录")
            withTimeout(10_000) { model.state.first { !it.busy && it.path == targetDirectory } }
            fileDetails(movedName); action("移入回收站").click(); clickText("移入回收站")
            val trashed = withTimeout(15_000) { model.trash.state.first { !it.changing && it.items.any { item -> item.path == moved && item.status == "available" } } }.items.single { it.path == moved }
            assertNull(metadata(api, moved)); assertTrue(favorites(api).none { it.getString("id") == favoriteId }); assertFalse(tagPaths(api, tagId).contains(moved)); stage("trashed")
            main { model.librarySection(io.github.kkwans.nasfilebrowser.app.LibrarySection.TRASH) }
            withTimeout(10_000) { model.trash.state.first { !it.loading && it.items.any { item -> item.id == trashed.id } } }
            restoreOwnedTrash(movedName)
            withTimeout(15_000) { model.trash.state.first { !it.changing && it.items.none { item -> item.id == trashed.id } } }
            assertArrayEquals(originalBytes, raw(api, moved))
            assertEquals(favoriteId, favorites(api).single { it.getString("path") == moved }.getString("id"))
            assertTrue(tagPaths(api, tagId).contains(moved)); capture("real-fileops-restored"); stage("restored")
        } catch (failure: Throwable) { primaryFailure = failure; throw failure }
        finally { withContext(NonCancellable) {
            val cleanup = mutableListOf<Throwable>()
            suspend fun finish(block: suspend () -> Unit) { try { block() } catch (failure: Throwable) { cleanup.add(failure) } }
            finish { main { model.disconnect() } }
            if (ownershipVerified) {
                favoriteId?.let { id -> finish { try { api.action("DELETE", "/api/favorites/${android.net.Uri.encode(id)}") } catch (failure: ServiceException) { if (failure.status != 404) throw failure } } }
                finish { api.action("DELETE", "/api/tags/${android.net.Uri.encode(tagId)}") }
            }
            finish { api.close() }; finish { store.remove(profile) }
            finish { previous?.let { if (database.profiles().account(it.accountKey) != null) database.profiles().saveActiveSession(it) } }
            if (cleanup.isNotEmpty()) {
                val failure = primaryFailure ?: cleanup.first()
                cleanup.filter { it !== failure }.forEach(failure::addSuppressed)
                if (primaryFailure == null) throw failure
            }
        } }
        stage("done")
    }
    private suspend fun favorites(api: NasSession): List<JSONObject> = api.array("/api/favorites").let { rows -> (0 until rows.length()).map(rows::getJSONObject) }
    private suspend fun tagPaths(api: NasSession, id: String): List<String> = api.array("/api/tags").let { rows ->
        val tag = (0 until rows.length()).map(rows::getJSONObject).single { it.getString("id") == id }
        tag.optJSONArray("paths")?.let { paths -> (0 until paths.length()).map(paths::getString) } ?: emptyList()
    }
}
