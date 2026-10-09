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

/** Owned transport fixture shared by the batch contract and native UI tests. */
internal class BatchRenameAuthority(val files: List<ResourceRef> = listOf("/a.txt", "/b.txt").map {
    ResourceRef(it, it, it.substringAfterLast('/'), false, "", 12)
}) {
    private val token = "owned." + android.util.Base64.encodeToString("{\"user\":{\"id\":1,\"username\":\"fixture\",\"perm\":{\"rename\":true}}}".toByteArray(), android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP) + ".fixture"
    val requests = mutableListOf<JSONObject>()
    val executions get() = requests.count { !it.getBoolean("dryRun") }
    var reviewStatus = 200
    var executionStatus = 200
    var rejectWire = false
    var malformedExecution = false
    var conflict = false
    var hold: CompletableDeferred<Unit>? = null
    var entered = CompletableDeferred<Unit>()
    val heldFinished = CompletableDeferred<Unit>()
    suspend fun context(owner: String): SessionContext {
        val profile = ServerProfile(name = "Owned batch rename", address = "https://fixture.invalid")
        val api = NasSession.restore(profile, token, 1) { command ->
            when (command.getString("op")) {
                "open" -> "owned-batch-$owner"
                "token" -> token
                "request" -> {
                    check(command.getString("method") == "POST" && command.getString("endpoint") == "/api/resources/batch-rename")
                    val body = JSONObject(command.getJSONObject("body").toString())
                    requests.add(body)
                    val dryRun = body.getBoolean("dryRun")
                    val input = body.getJSONArray("items")
                    val wireProtocol = input.getJSONObject(0).has("fromWirePath")
                    val status = if (rejectWire && wireProtocol) 400 else if (dryRun) reviewStatus else executionStatus
                    val rows = JSONArray()
                    for (index in 0 until input.length()) {
                        val item = input.getJSONObject(index)
                        val fromWire = if (wireProtocol) item.getString("fromWirePath") else SearchResult.encodePath(item.getString("from"))
                        val toWire = if (wireProtocol) item.getString("toWirePath") else SearchResult.encodePath(item.getString("to"))
                        val source = files.single { resourceWireBytes(batchRenameSourceWire(it)).contentEquals(resourceWireBytes(fromWire)) }
                        // The target basename is UTF-8, while its parent still
                        // uses the source's opaque bytes and display presentation.
                        val targetName = resourceWireBytes("/" + toWire.substringAfterLast('/')).drop(1).toByteArray().toString(Charsets.UTF_8)
                        val row = JSONObject().put("from", source.path).put("to", source.path.substringBeforeLast('/') + "/" + targetName)
                            .put("status", if (dryRun) { if (conflict) "error" else "ready" } else "completed")
                        if (wireProtocol) row.put("fromWirePath", fromWire).put("toWirePath", toWire)
                        if (dryRun && conflict) row.put("error", "测试数据：目标名称已存在")
                        rows.put(row)
                    }
                    if (!dryRun && malformedExecution) rows.remove(rows.length() - 1)
                    val response = JSONObject().put("valid", !conflict || !dryRun).put("executed", !dryRun).put("items", rows).toString()
                    entered.complete(Unit)
                    hold?.let { try { withContext(NonCancellable) { it.await() } } finally { heldFinished.complete(Unit) } }
                    JSONObject().put("status", status).put("body", response)
                }
                else -> error("Unexpected native operation")
            }
        }
        return SessionContext(profile, AccountRecord(owner, profile.id, 0, 1, "fixture", "fixture-only", 0), api, 1, owner)
    }
}

@RunWith(AndroidJUnit4::class)
class BatchRenameContractTest {
    private suspend fun main(action: () -> Unit) = withContext(Dispatchers.Main) { action() }
    private suspend fun settled(controller: FileOperationsController) = withTimeout(5000) { controller.state.first { !it.changing } }
    private fun start(controller: FileOperationsController, context: SessionContext, files: List<ResourceRef>) {
        controller.bind(context)
        controller.startBatchRename(files, batchRenameParent(files.first()), context.api.id)
        controller.batchRenameOptions(BatchRenameOptions(text = "new-"))
    }
    @Test fun preflightDoesNotWriteEditsInvalidateAndExecutionCallsOneAtomicCallback(): Unit = runBlocking {
        val authority = BatchRenameAuthority(); val context = authority.context("one")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        var callbacks = 0; var changes = emptyList<BatchRenameChange>()
        val controller = FileOperationsController(scope, { it === context }, { _, _, _ -> error("Single rename callback must not run") },
            onBatchRenamed = { _, items -> callbacks++; changes = items })
        try {
            main { start(controller, context, authority.files); controller.executeBatchRename() }
            assertTrue(authority.requests.isEmpty())
            main { controller.checkBatchRename() }; settled(controller)
            assertTrue(controller.state.value.batchRename!!.reviewed)
            assertEquals(0, authority.executions)
            main { controller.batchRenameName("/a.txt", "manual.txt"); controller.executeBatchRename() }
            assertFalse(controller.state.value.batchRename!!.reviewed)
            assertEquals(0, authority.executions)
            main { controller.checkBatchRename() }; settled(controller)
            main { controller.executeBatchRename(); controller.executeBatchRename() }; settled(controller)
            assertEquals(1, authority.executions)
            assertEquals(1, callbacks)
            assertEquals(listOf("/manual.txt", "/new-b.txt"), changes.map { it.target.wirePath })
            assertEquals(1L, controller.state.value.batchCompletion)
            assertNull(controller.state.value.batchRename)
            assertTrue(authority.requests.take(2).all { it.getBoolean("dryRun") })
        } finally { scope.cancel() }
    }
    @Test fun serverConflictPreservesInputsAndUncertainExecutionCannotBeResubmitted(): Unit = runBlocking {
        val authority = BatchRenameAuthority(); val context = authority.context("one")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val controller = FileOperationsController(scope, { it === context }, { _, _, _ -> })
        try {
            authority.conflict = true
            main { start(controller, context, authority.files); controller.checkBatchRename() }; settled(controller)
            assertFalse(controller.state.value.batchRename!!.reviewed)
            assertEquals(2, controller.state.value.batchRename!!.serverErrors.size)
            assertEquals("new-", controller.state.value.batchRename!!.options.text)
            assertEquals(0, authority.executions)
            authority.conflict = false
            main { controller.checkBatchRename() }; settled(controller)
            authority.executionStatus = 409
            main { controller.executeBatchRename() }; settled(controller)
            assertFalse(controller.state.value.batchRename!!.reviewed)
            assertFalse(controller.state.value.batchRename!!.unknownExecution)
            main { controller.executeBatchRename() }
            assertEquals(1, authority.executions)
            main { controller.checkBatchRename() }; settled(controller)
            authority.executionStatus = 500
            main { controller.executeBatchRename() }; settled(controller)
            assertTrue(controller.state.value.batchRename!!.unknownExecution)
            val requests = authority.requests.size
            main { controller.executeBatchRename(); controller.checkBatchRename(); controller.batchRenameName("/a.txt", "retry.txt"); controller.closeBatchRename() }
            assertEquals(requests, authority.requests.size)
            assertEquals("new-a.txt", controller.state.value.batchRename!!.rows.first().newName)
            assertNotNull(controller.state.value.batchRename)
            main { controller.closeBatchRename(verifyUnknown = true) }
            assertNull(controller.state.value.batchRename)
        } finally { scope.cancel() }
    }
    @Test fun malformedExecutionAcknowledgementIsUnknownAndNeverCallsSuccess(): Unit = runBlocking {
        val authority = BatchRenameAuthority(); val context = authority.context("one")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        var callbacks = 0
        val controller = FileOperationsController(scope, { it === context }, { _, _, _ -> }, onBatchRenamed = { _, _ -> callbacks++ })
        try {
            main { start(controller, context, authority.files); controller.checkBatchRename() }; settled(controller)
            authority.malformedExecution = true
            main { controller.executeBatchRename() }; settled(controller)
            assertTrue(controller.state.value.batchRename!!.unknownExecution)
            assertEquals(0, callbacks)
            assertEquals(0L, controller.state.value.batchCompletion)
            main { controller.executeBatchRename() }
            assertEquals(1, authority.executions)
        } finally { scope.cancel() }
    }
    @Test fun legacyDryRunFallbackOnlyUsesExactUtf8PathsAndExecutionKeepsChosenProtocol(): Unit = runBlocking {
        for (opaque in listOf(false, true)) {
            val files = if (opaque) listOf(ResourceRef("/中文.txt", "/%D6%D0%CE%C4.txt", "中文.txt", false, "", 12)) else listOf(ResourceRef("/a.txt", "/a.txt", "a.txt", false, "", 12))
            val authority = BatchRenameAuthority(files); authority.rejectWire = true
            val context = authority.context("one")
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            val controller = FileOperationsController(scope, { it === context }, { _, _, _ -> })
            try {
                main { start(controller, context, files); controller.checkBatchRename() }; settled(controller)
                if (opaque) {
                    assertEquals(1, authority.requests.size)
                    assertTrue(controller.state.value.batchRename!!.error!!.contains("升级服务器"))
                    main { controller.executeBatchRename() }; assertEquals(0, authority.executions)
                } else {
                    assertTrue(controller.state.value.batchRename!!.legacy)
                    assertTrue(authority.requests.all { it.getBoolean("dryRun") })
                    main { controller.executeBatchRename() }; settled(controller)
                    assertEquals(1, authority.executions)
                    assertFalse(authority.requests.last().getJSONArray("items").getJSONObject(0).has("fromWirePath"))
                }
            } finally { scope.cancel() }
        }
    }
    @Test fun displayedPreviewAndManualReentrySendSameUtf8NameWithOriginalSourceAndParent(): Unit = runBlocking {
        val file = ResourceRef("/资料/中文.txt", "/%D7%CA%C1%CF/%D6%D0%CE%C4.txt", "中文.txt", false, "", 12)
        val authority = BatchRenameAuthority(listOf(file)); val context = authority.context("one")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        var acknowledged = emptyList<BatchRenameChange>()
        val controller = FileOperationsController(scope, { it === context }, { _, _, _ -> }, onBatchRenamed = { _, changes -> acknowledged = changes })
        try {
            main { start(controller, context, listOf(file)); controller.batchRenameOptions(BatchRenameOptions(text = "新")); controller.checkBatchRename() }
            settled(controller)
            assertTrue(controller.state.value.batchRename!!.reviewed)
            assertEquals("新中文.txt", controller.state.value.batchRename!!.rows.single().newName)
            val expectedWire = "/%D7%CA%C1%CF/%E6%96%B0%E4%B8%AD%E6%96%87.txt"
            val automatic = authority.requests.single().getJSONArray("items").getJSONObject(0)
            assertEquals(file.wirePath, automatic.getString("fromWirePath"))
            assertEquals(expectedWire, automatic.getString("toWirePath"))
            assertEquals(0, authority.executions)
            main { controller.batchRenameName(file.wirePath, "新中文.txt"); controller.checkBatchRename() }; settled(controller)
            val manual = authority.requests.last().getJSONArray("items").getJSONObject(0)
            assertEquals(automatic.getString("fromWirePath"), manual.getString("fromWirePath"))
            assertEquals(automatic.getString("toWirePath"), manual.getString("toWirePath"))
            main { controller.executeBatchRename() }; settled(controller)
            assertEquals(1, authority.executions)
            assertEquals(file.wirePath, acknowledged.single().file.wirePath)
            assertEquals(expectedWire, acknowledged.single().target.wirePath)
            assertEquals("/资料/新中文.txt", acknowledged.single().target.path)
            assertEquals("新中文.txt", acknowledged.single().target.name)
        } finally { scope.cancel() }
    }
    @Test fun latePreviousAccountPreflightCannotApproveOrReplaceNewDraft(): Unit = runBlocking {
        val old = BatchRenameAuthority(); val fresh = BatchRenameAuthority()
        val first = old.context("old"); val second = fresh.context("new")
        var active = first
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val controller = FileOperationsController(scope, { it === active }, { _, _, _ -> })
        old.hold = CompletableDeferred()
        try {
            main { start(controller, first, old.files); controller.checkBatchRename() }
            withTimeout(5000) { old.entered.await() }
            main { active = second; start(controller, second, fresh.files); controller.batchRenameOptions(BatchRenameOptions(text = "fresh-")) }
            old.hold!!.complete(Unit)
            withTimeout(5000) { old.heldFinished.await() }
            withContext(Dispatchers.Main) { yield() }
            assertEquals("new", controller.state.value.scope)
            assertFalse(controller.state.value.batchRename!!.reviewed)
            assertEquals("fresh-a.txt", controller.state.value.batchRename!!.rows.first().newName)
            assertEquals(0, old.executions)
            assertEquals(0, fresh.executions)
        } finally { old.hold?.complete(Unit); scope.cancel() }
    }
    @Test fun latePreviousAccountExecutionCannotRewriteNewAccountOrCallSuccess(): Unit = runBlocking {
        val old = BatchRenameAuthority(); val fresh = BatchRenameAuthority()
        val first = old.context("old"); val second = fresh.context("new")
        var active = first; var callbacks = 0
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val controller = FileOperationsController(scope, { it === active }, { _, _, _ -> }, onBatchRenamed = { _, _ -> callbacks++ })
        try {
            main { start(controller, first, old.files); controller.checkBatchRename() }; settled(controller)
            old.hold = CompletableDeferred(); old.entered = CompletableDeferred()
            main { controller.executeBatchRename() }
            withTimeout(5000) { old.entered.await() }
            main { active = second; start(controller, second, fresh.files) }
            old.hold!!.complete(Unit)
            withTimeout(5000) { old.heldFinished.await() }
            withContext(Dispatchers.Main) { yield() }
            assertEquals("new", controller.state.value.scope)
            assertEquals(0L, controller.state.value.batchCompletion)
            assertFalse(controller.state.value.batchRename!!.reviewed)
            assertEquals(0, callbacks)
            assertEquals(1, old.executions)
            assertEquals(0, fresh.executions)
        } finally { old.hold?.complete(Unit); scope.cancel() }
    }

    @Test fun acknowledgedBatchKeepsWriteLockedUntilTheAtomicCallbackFinishes(): Unit = runBlocking {
        val authority = BatchRenameAuthority(); val context = authority.context("callback-order")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val checked = CompletableDeferred<Result<Unit>>(); var callbacks = 0
        lateinit var controller: FileOperationsController
        controller = FileOperationsController(scope, { it === context }, { _, _, _ -> }, onBatchRenamed = { owner, changes ->
            callbacks++
            checked.complete(runCatching {
                assertSame(context, owner); assertEquals(1, authority.executions)
                assertEquals(authority.files.size, changes.size)
                assertTrue("Keep reentry locked while local references are refreshed", controller.state.value.changing)
                assertNotNull(controller.state.value.batchRename)
                assertEquals(0L, controller.state.value.batchCompletion)
                assertNull(controller.state.value.notice)
                controller.executeBatchRename(); controller.checkBatchRename(); controller.closeBatchRename()
                assertTrue(controller.state.value.changing); assertEquals(1, authority.executions)
            })
        })
        try {
            main { start(controller, context, authority.files); controller.checkBatchRename() }; settled(controller)
            main { controller.executeBatchRename() }
            withTimeout(5000) { checked.await() }.getOrThrow(); settled(controller)
            assertEquals(1, callbacks); assertEquals(1, authority.executions)
            assertNull(controller.state.value.batchRename); assertEquals(1L, controller.state.value.batchCompletion)
            assertEquals(authority.files, controller.state.value.lastBatchSources)
            assertNull(controller.state.value.error)
        } finally { scope.cancel() }
    }

    @Test fun batchCallbackMayUnbindOrReplaceItsOwnerWithoutRestoringOldCompletion(): Unit = runBlocking {
        for (replace in listOf(false, true)) {
            val authority = BatchRenameAuthority(); val context = authority.context("callback-old-$replace")
            val fresh = BatchRenameAuthority(); val next = fresh.context("callback-next-$replace")
            var active: SessionContext? = context
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            val checked = CompletableDeferred<Result<Unit>>()
            lateinit var controller: FileOperationsController
            controller = FileOperationsController(scope, { it === active }, { _, _, _ -> }, onBatchRenamed = { _, _ ->
                checked.complete(runCatching {
                    assertTrue(controller.state.value.changing)
                    active = if (replace) next else null
                    controller.bind(active)
                    if (replace) controller.startBatchRename(fresh.files, batchRenameParent(fresh.files.first()), next.api.id)
                })
            })
            try {
                main { start(controller, context, authority.files); controller.checkBatchRename() }; settled(controller)
                main { controller.executeBatchRename() }
                withTimeout(5000) { checked.await() }.getOrThrow(); main { }
                assertEquals(if (replace) next.owner else "", controller.state.value.scope)
                assertFalse(controller.state.value.changing); assertEquals(0L, controller.state.value.batchCompletion)
                assertTrue(controller.state.value.lastBatchSources.isEmpty())
                assertNull(controller.state.value.notice); assertNull(controller.state.value.error)
                assertEquals(replace, controller.state.value.batchRename != null)
                assertEquals(1, authority.executions); assertEquals(0, fresh.executions)
            } finally { scope.cancel() }
        }
    }

    @Test fun acknowledgedBatchCallbackFailureCannotReopenOrReplayTheRemoteWrite(): Unit = runBlocking {
        for (cancel in listOf(false, true)) {
            val authority = BatchRenameAuthority(); val context = authority.context("callback-failure-$cancel")
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            val checked = CompletableDeferred<Result<Unit>>(); var callbacks = 0
            lateinit var controller: FileOperationsController
            controller = FileOperationsController(scope, { it === context }, { _, _, _ -> }, onBatchRenamed = { _, _ ->
                callbacks++
                checked.complete(runCatching {
                    assertTrue(controller.state.value.changing)
                    assertEquals(0L, controller.state.value.batchCompletion)
                })
                if (cancel) throw CancellationException("Owned local refresh canceled after acknowledgement")
                throw IllegalStateException("Owned local refresh failed after acknowledgement")
            })
            try {
                main { start(controller, context, authority.files); controller.checkBatchRename() }; settled(controller)
                main { controller.executeBatchRename() }
                withTimeout(5000) { checked.await() }.getOrThrow(); settled(controller)
                assertNull(controller.state.value.batchRename); assertEquals(1L, controller.state.value.batchCompletion)
                assertEquals(authority.files, controller.state.value.lastBatchSources)
                assertTrue(controller.state.value.error.orEmpty().contains("重命名已完成"))
                assertTrue(controller.state.value.error.orEmpty().contains("本地关联刷新"))
                main { controller.executeBatchRename(); controller.checkBatchRename() }
                assertEquals(1, callbacks); assertEquals(1, authority.executions)
            } finally { scope.cancel() }
        }
    }
}
