package io.github.kkwans.nasfilebrowser

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.kkwans.nasfilebrowser.app.*
import io.github.kkwans.nasfilebrowser.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the real controller and NasSession envelope, keyed by raw wire
 * identity. The fixture never resolves a display path to an opaque sibling. */
private class TransferWireAuthority(val files: List<ResourceRef>, val directory: DirectoryCrumb) {
    val entries = fileTransferEntries(files, directory, FileTransferAction.COPY, allowOpaque = true)
    val transfers = mutableListOf<JSONObject>()
    val batches = mutableListOf<JSONObject>()
    val presentTargets = mutableSetOf<String>()
    var capability: Any? = true
    var capabilityStatus = 200
    var capabilityReads = 0
    var acknowledgement = "wire"
    var transferStatus = 202
    var holdCapability: CompletableDeferred<Unit>? = null
    val capabilityEntered = CompletableDeferred<Unit>()
    val capabilityReturned = CompletableDeferred<Unit>()
    private val token = "owned." + android.util.Base64.encodeToString(
        "{\"user\":{\"id\":1,\"username\":\"fixture\",\"perm\":{\"create\":true,\"rename\":true,\"modify\":true}}}".toByteArray(),
        android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP) + ".fixture"
    private fun item(file: ResourceRef) = JSONObject().put("path", file.path).put("wirePath", file.wirePath)
        .put("name", file.name).put("isDir", file.directory).put("size", file.size).put("modified", file.modified).put("type", file.type)
    suspend fun context(owner: String): SessionContext {
        val profile = ServerProfile(name = "Owned transfer wire", address = "https://fixture.invalid")
        val api = NasSession.restore(profile, token, 1) { command ->
            when (command.getString("op")) {
                "open" -> "owned-transfer-$owner"
                "token" -> token
                "request" -> {
                    val endpoint = command.getString("endpoint"); val method = command.getString("method")
                    var status = 200
                    val response: Any = when {
                        method == "GET" && endpoint == "/api/client-capabilities" -> {
                            capabilityReads++
                            val captured = JSONObject().put("authMethod", "json").put("enableExec", false).put("minimumPasswordLength", 6)
                                .apply { capability?.let { put("resourceWireOperations", it) } }.toString()
                            capabilityEntered.complete(Unit)
                            holdCapability?.let { withContext(NonCancellable) { it.await() } }
                            capabilityReturned.complete(Unit); status = capabilityStatus; captured
                        }
                        method == "GET" && endpoint.startsWith("/api/resources") -> {
                            val wire = endpoint.removePrefix("/api/resources").substringBefore('?')
                            check(recentAccessWireIdentity(wire) == recentAccessWireIdentity(directory.wirePath!!))
                            if (endpoint.endsWith("?metadata=1")) JSONObject().put("path", directory.path).put("wirePath", wire).put("isDir", true)
                            else JSONObject().put("items", JSONArray())
                        }
                        endpoint == "/api/resources/batch" -> {
                            check(method == "POST")
                            val body = JSONObject(command.getJSONObject("body").toString()); batches.add(body)
                            val wires = body.getJSONArray("wirePaths")
                            JSONArray().apply { for (index in 0 until wires.length()) {
                                val wire = wires.getString(index)
                                val source = files.firstOrNull { recentAccessWireIdentity(it.wirePath) == wire }
                                val target = entries.firstOrNull { it.targetWire == wire }
                                val path = source?.path ?: requireNotNull(target).targetPath
                                val present = source != null || wire in presentTargets
                                val row = JSONObject().put("path", path).put("status", if (present) 200 else 404)
                                if (acknowledgement != "legacy") row.put("wirePath", if (acknowledgement == "wrong-row") "/wrong.txt" else wire)
                                if (present) {
                                    val file = source ?: requireNotNull(target).file.copy(path = path, wirePath = wire)
                                    val metadata = item(file)
                                    if (acknowledgement == "legacy" || acknowledgement == "missing-item") metadata.remove("wirePath")
                                    if (acknowledgement == "wrong-item" || acknowledgement == "wrong-target-item" && source == null) metadata.put("wirePath", "/wrong.txt")
                                    row.put("item", metadata)
                                }
                                put(row)
                            } }
                        }
                        endpoint == "/api/resources/transfer" -> {
                            check(method == "POST")
                            val body = JSONObject(command.getJSONObject("body").toString()); transfers.add(body); status = transferStatus
                            JSONObject().put("id", "owned-task-${transfers.size}").put("userId", 1).put("type", "file.${body.getString("action")}")
                                .put("status", "queued").put("title", "Owned copy")
                        }
                        else -> error("Unexpected owned request $method $endpoint")
                    }
                    JSONObject().put("status", status).put("body", response.toString())
                }
                else -> error("Unexpected native operation")
            }
        }
        return SessionContext(profile, AccountRecord(owner, profile.id, 0, 1, "fixture", "owned", 0), api, 1, owner)
    }
}

@RunWith(AndroidJUnit4::class)
class FileTransferWireContractTest {
    private fun file(path: String, wire: String = SearchResult.encodePath(path)) = ResourceRef(path, wire, path.substringAfterLast('/'), false, "text", 12, "owned-version")
    private val directory = DirectoryCrumb("目标", "/目标", SearchResult.encodePath("/目标"))
    private suspend fun main(action: () -> Unit) = withContext(Dispatchers.Main) { action() }
    private suspend fun ready(controller: FileOperationsController) = withTimeout(5000) {
        controller.state.first { !it.changing && it.transfer?.loading != true }
    }
    private fun controller(scope: CoroutineScope, current: (SessionContext) -> Boolean) = FileOperationsController(scope, current, { _, _, _ -> error("Not a rename") })
    private suspend fun start(controller: FileOperationsController, owner: SessionContext, authority: TransferWireAuthority) {
        main { controller.bind(owner); controller.startTransfer(authority.files, FileTransferAction.COPY, authority.directory) }
        assertNull(ready(controller).transfer!!.error)
    }

    @Test fun sameDisplaySiblingsHaveIndependentReviewChoicesAndWireOnlyOpaqueSubmission(): Unit = runBlocking {
        val opaque = file("/源/中文.txt", "/%E6%BA%90/%D6%D0%CE%C4.txt")
        val utf8 = file(opaque.path); val percent = file("/源/100%2F.txt")
        val authority = TransferWireAuthority(listOf(opaque, utf8, percent), directory)
        authority.presentTargets.addAll(authority.entries.take(2).map { it.targetWire })
        val owner = authority.context("same-display"); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val controller = controller(scope) { it === owner }
        try {
            start(controller, owner, authority)
            assertEquals(0, authority.capabilityReads)
            main { controller.submitTransfer() }; ready(controller)
            val review = controller.state.value.transfer!!
            assertTrue(review.reviewed); assertEquals(setOf(opaque.mediaKey, utf8.mediaKey), review.conflicts)
            assertEquals(1, authority.capabilityReads); assertEquals(6, authority.batches.single().getJSONArray("wirePaths").length())
            assertFalse(authority.batches.single().has("paths")); assertTrue(authority.transfers.isEmpty())
            main { controller.chooseConflict(opaque.mediaKey, FileConflictChoice.REPLACE); controller.chooseConflict(utf8.mediaKey, FileConflictChoice.SKIP) }
            assertEquals(FileConflictChoice.REPLACE, controller.state.value.transfer!!.choices[opaque.mediaKey])
            assertEquals(FileConflictChoice.SKIP, controller.state.value.transfer!!.choices[utf8.mediaKey])
            main { controller.submitTransfer() }; ready(controller)
            assertTrue(controller.state.value.transfer!!.error.orEmpty().contains("确认替换")); assertTrue(authority.transfers.isEmpty())
            main { controller.submitTransfer(replaceConfirmed = true) }; ready(controller)
            val submitted = authority.transfers.single().getJSONArray("items")
            assertEquals(2, submitted.length())
            val raw = submitted.getJSONObject(0)
            assertEquals(opaque.wirePath, raw.getString("fromWirePath")); assertEquals(authority.entries[0].targetWire, raw.getString("toWirePath"))
            assertFalse(raw.has("from")); assertFalse(raw.has("to")); assertTrue(raw.getBoolean("overwrite")); assertFalse(raw.getBoolean("rename"))
            val literal = submitted.getJSONObject(1)
            assertEquals("/%E6%BA%90/100%252F.txt", literal.getString("fromWirePath"))
            assertEquals("/files/%E6%BA%90/100%252F.txt", literal.getString("from"))
            assertEquals(1, authority.capabilityReads); assertEquals(listOf(opaque, percent), controller.state.value.lastSources)
            assertEquals("queued", controller.state.value.lastTask!!.status)
        } finally { scope.cancel() }
    }

    @Test fun missingFalseMalformedOrUnavailableCapabilityNeverPostsOpaqueTask(): Unit = runBlocking {
        for ((flag, status) in listOf(null to 200, false to 200, "true" to 200, true to 404)) {
            val authority = TransferWireAuthority(listOf(file("/中文.txt", "/%D6%D0%CE%C4.txt")), directory.copy(label = "中文", path = "/中文", wirePath = "/%D6%D0%CE%C4"))
            authority.capability = flag; authority.capabilityStatus = status
            val owner = authority.context("unsupported"); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            val controller = controller(scope) { it === owner }
            try {
                start(controller, owner, authority) // Opaque directory browsing remains readonly and available.
                main { controller.submitTransfer() }; ready(controller)
                assertFalse(controller.state.value.transfer!!.reviewed); assertNotNull(controller.state.value.transfer!!.error)
                assertEquals(1, authority.capabilityReads); assertTrue(authority.batches.isEmpty()); assertTrue(authority.transfers.isEmpty())
            } finally { scope.cancel() }
        }
    }

    @Test fun lateCapabilityAfterAccountSwitchCannotReviewOrSubmitForEitherAccount(): Unit = runBlocking {
        val authority = TransferWireAuthority(listOf(file("/中文.txt", "/%D6%D0%CE%C4.txt")), directory)
        authority.holdCapability = CompletableDeferred()
        val old = authority.context("old"); val freshAuthority = TransferWireAuthority(listOf(file("/fresh.txt")), directory)
        val fresh = freshAuthority.context("fresh"); var active = old
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main); val controller = controller(scope) { it === active }
        try {
            start(controller, old, authority); main { controller.submitTransfer() }
            withTimeout(5000) { authority.capabilityEntered.await() }
            main { active = fresh; controller.bind(fresh) }
            authority.holdCapability!!.complete(Unit); withTimeout(5000) { authority.capabilityReturned.await() }; delay(100)
            assertEquals("fresh", controller.state.value.scope); assertNull(controller.state.value.transfer); assertFalse(controller.state.value.changing)
            assertTrue(authority.batches.isEmpty()); assertTrue(authority.transfers.isEmpty()); assertTrue(freshAuthority.transfers.isEmpty())
        } finally { authority.holdCapability!!.complete(Unit); scope.cancel() }
    }

    @Test fun wrongOrMissingOpaqueRowAndItemWireAcknowledgementsBlockSubmission(): Unit = runBlocking {
        for (mode in listOf("legacy", "wrong-row", "missing-item", "wrong-item", "wrong-target-item")) {
            val authority = TransferWireAuthority(listOf(file("/中文.txt", "/%D6%D0%CE%C4.txt")), directory)
            authority.acknowledgement = mode
            authority.presentTargets.add(authority.entries.single().targetWire)
            val owner = authority.context(mode); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main); val controller = controller(scope) { it === owner }
            try {
                start(controller, owner, authority); main { controller.submitTransfer() }; ready(controller)
                assertFalse("$mode must not review", controller.state.value.transfer!!.reviewed)
                assertNotNull(controller.state.value.transfer!!.error); assertTrue(authority.transfers.isEmpty())
            } finally { scope.cancel() }
        }
    }

    @Test fun legacyUtf8ReviewNeedsNoCapabilityGetAndPreservesFilesAndLiteralPercentRoutes(): Unit = runBlocking {
        val source = file("/files/100%2F +中文.txt")
        val authority = TransferWireAuthority(listOf(source), directory); authority.acknowledgement = "legacy"
        val owner = authority.context("legacy"); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main); val controller = controller(scope) { it === owner }
        try {
            start(controller, owner, authority); main { controller.submitTransfer() }; ready(controller)
            assertTrue(controller.state.value.transfer!!.reviewed); assertEquals(0, authority.capabilityReads)
            assertEquals(source.path, authority.batches.single().getJSONArray("paths").getString(0))
            main { controller.submitTransfer() }; ready(controller)
            val item = authority.transfers.single().getJSONArray("items").getJSONObject(0)
            assertEquals("/files/files/100%252F%20%2B%E4%B8%AD%E6%96%87.txt", item.getString("from"))
            assertFalse(item.has("fromWirePath")); assertFalse(item.has("toWirePath")); assertEquals(0, authority.capabilityReads)
        } finally { scope.cancel() }
    }

    @Test fun unknownWireSubmissionIsNeverRetried(): Unit = runBlocking {
        val authority = TransferWireAuthority(listOf(file("/中文.txt", "/%D6%D0%CE%C4.txt")), directory); authority.transferStatus = 503
        val owner = authority.context("unknown"); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main); val controller = controller(scope) { it === owner }
        try {
            start(controller, owner, authority); main { controller.submitTransfer() }; ready(controller)
            main { controller.submitTransfer() }; ready(controller)
            assertTrue(controller.state.value.transfer!!.unknownSubmission)
            main { controller.submitTransfer(); controller.readTransferDirectory(directory) }; delay(100)
            assertEquals(1, authority.transfers.size); assertEquals(1, authority.batches.size); assertEquals(1, authority.capabilityReads)
        } finally { scope.cancel() }
    }

    @Test fun optionalWireCapabilityIsStrictBooleanAndDefaultsToFalse() {
        val row = JSONObject().put("authMethod", "json").put("enableExec", false).put("minimumPasswordLength", 6)
        assertFalse(ServerCapabilities.from(row).resourceWireOperations)
        assertTrue(ServerCapabilities.from(row.put("resourceWireOperations", true)).resourceWireOperations)
        assertFalse(ServerCapabilities.from(row.put("resourceWireOperations", false)).resourceWireOperations)
        for (invalid in listOf("true", 1, JSONObject.NULL)) {
            assertThrows(IllegalStateException::class.java) { ServerCapabilities.from(row.put("resourceWireOperations", invalid)) }
        }
    }
}
