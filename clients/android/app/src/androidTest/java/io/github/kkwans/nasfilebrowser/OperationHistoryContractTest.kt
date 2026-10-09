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

/** Uses the real NasSession transport boundary with synthetic server-owned records. */
@RunWith(AndroidJUnit4::class)
class OperationHistoryContractTest {
    private class Authority(private val user: Long = 1) {
        val token = "owned." + android.util.Base64.encodeToString("{\"user\":{\"id\":$user,\"username\":\"fixture\"}}".toByteArray(), android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP) + ".fixture"
        val requests = mutableListOf<Pair<String, String>>()
        var first = page(listOf(entry("web-first", "/中文 #?%/web.txt")), "opaque-next", 2)
        var second = page(listOf(entry("web-first", "/中文 #?%/web.txt"), entry("web-second", "/second.txt")), "", 2)
        var hold: CompletableDeferred<Unit>? = null
        var entered = CompletableDeferred<Unit>()
        val heldReadEntered = CompletableDeferred<Unit>()
        val heldReadFinished = CompletableDeferred<Unit>()
        var failGet = false
        var deleteStatus = 200
        var deleteBody = "{\"deleted\":2}"
        suspend fun call(command: JSONObject): Any = when (command.getString("op")) {
            "open" -> "owned-history-$user"
            "token" -> token
            "request" -> {
                val method = command.getString("method"); val endpoint = command.getString("endpoint")
                check(endpoint == "/api/history" || endpoint.startsWith("/api/history?"))
                requests.add(method to endpoint)
                var status = 200
                val body = if (method == "GET") {
                    // Capture this response and its gate before notifying the test;
                    // the next filter may immediately replace both fixture values.
                    val captured = if (endpoint.contains("cursor=")) second.toString() else first.toString()
                    val pendingHold = hold
                    entered.complete(Unit)
                    pendingHold?.let { try { withContext(NonCancellable) {
                        heldReadEntered.complete(Unit)
                        it.await()
                    } } finally { heldReadFinished.complete(Unit) } }
                    if (failGet) error("Owned history read failure")
                    captured
                } else {
                    check(method == "DELETE" && endpoint == "/api/history")
                    status = deleteStatus
                    deleteBody
                }
                JSONObject().put("status", status).put("body", body)
            }
            else -> error("Unexpected native operation")
        }
        suspend fun context(owner: String): SessionContext {
            val profile = ServerProfile(name = "Owned history contract", address = "https://fixture.invalid")
            val session = NasSession.restore(profile, token, user, ::call)
            return SessionContext(profile, AccountRecord(owner, profile.id, 0, user, "fixture", "fixture-only", 0), session, 1, owner)
        }
        companion object {
            fun entry(id: String, path: String) = JSONObject().put("id", id).put("action", "file.rename").put("target", path)
                .put("detail", "/original #?%.txt").put("status", "success").put("createdAt", 1_800_000_000_000)
            fun page(rows: List<JSONObject>, cursor: String, total: Int) = JSONObject().put("items", JSONArray(rows)).put("nextCursor", cursor).put("total", total)
        }
    }
    private suspend fun main(action: () -> Unit) = withContext(Dispatchers.Main) { action() }
    private suspend fun settled(controller: OperationHistoryController) = withTimeout(5000) {
        controller.state.first { !it.loading && !it.paging && !it.clearing && (it.loaded || it.error != null) }
    }

    @Test fun remoteRowsOpaqueCursorAndPagingDeduplicationArePreserved(): Unit = runBlocking {
        val authority = Authority(); val context = authority.context("one")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val controller = OperationHistoryController(scope) { it === context }
        try {
            main { controller.bind(context); controller.refresh() }
            settled(controller)
            assertEquals("/中文 #?%/web.txt", controller.state.value.items.single().target)
            assertEquals("/original #?%.txt", controller.state.value.items.single().detail)
            authority.hold = CompletableDeferred(); authority.entered = CompletableDeferred()
            main { controller.refresh(more = true); controller.refresh(more = true) }
            withTimeout(5000) { authority.entered.await() }
            assertEquals(2, authority.requests.size)
            assertEquals("/api/history?limit=30&cursor=opaque-next", authority.requests.last().second)
            authority.hold!!.complete(Unit)
            settled(controller)
            assertEquals(listOf("web-first", "web-second"), controller.state.value.items.map { it.id })
            assertEquals("", controller.state.value.nextCursor)
            main { controller.refresh(more = true) }
            assertEquals(2, authority.requests.size)
        } finally { authority.hold?.complete(Unit); scope.cancel() }
    }
    @Test fun failedPageKeepsListAndCursorAndRetriesTheSamePage(): Unit = runBlocking {
        val authority = Authority(); val context = authority.context("one")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val controller = OperationHistoryController(scope) { it === context }
        try {
            main { controller.bind(context); controller.refresh() }; settled(controller)
            authority.failGet = true
            main { controller.refresh(more = true) }; settled(controller)
            assertEquals("web-first", controller.state.value.items.single().id)
            assertEquals("opaque-next", controller.state.value.nextCursor)
            assertTrue(controller.state.value.retryMore)
            authority.failGet = false
            main { controller.retry() }; settled(controller)
            assertEquals(2, controller.state.value.items.size)
            assertEquals(authority.requests[1], authority.requests[2])
        } finally { scope.cancel() }
    }
    @Test fun lateOldAccountReadCannotReplaceNewAccountRowsOrReviveDisconnectedState(): Unit = runBlocking {
        val old = Authority(1); val fresh = Authority(2)
        fresh.first = Authority.page(listOf(Authority.entry("new-account", "/new.txt")), "", 1)
        val first = old.context("old"); val second = fresh.context("new")
        var active: SessionContext? = first
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val controller = OperationHistoryController(scope) { it === active }
        old.hold = CompletableDeferred()
        try {
            main { controller.bind(first); controller.refresh() }
            withTimeout(5000) { old.entered.await() }
            main { active = second; controller.bind(second); controller.refresh() }; settled(controller)
            old.hold!!.complete(Unit)
            withTimeout(5000) { old.heldReadFinished.await() }
            withContext(Dispatchers.Main) { yield() }
            assertEquals("new", controller.state.value.scope)
            assertEquals("new-account", controller.state.value.items.single().id)
            main { active = null; controller.bind(null) }
            assertEquals("", controller.state.value.scope)
            assertTrue(controller.state.value.items.isEmpty())
        } finally { old.hold?.complete(Unit); scope.cancel() }
    }
    @Test fun latePreviousFilterReadCannotReplaceCurrentResults(): Unit = runBlocking {
        val authority = Authority(); val context = authority.context("one")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val controller = OperationHistoryController(scope) { it === context }
        val releaseOld = CompletableDeferred<Unit>()
        authority.hold = releaseOld
        try {
            main { controller.bind(context); controller.refresh() }
            withTimeout(5000) { authority.heldReadEntered.await() }
            authority.hold = null
            authority.first = Authority.page(listOf(Authority.entry("filtered", "/filtered.txt")), "", 1)
            main { controller.filter(OperationHistoryFilter(text = "filtered")) }; settled(controller)
            assertEquals(2, authority.requests.size)
            assertEquals("filtered", controller.state.value.items.single().id)
            assertFalse("Old response must still be held after the new filter settles", authority.heldReadFinished.isCompleted)
            releaseOld.complete(Unit)
            withTimeout(5000) { authority.heldReadFinished.await() }
            withContext(Dispatchers.Main) { yield() }
            assertEquals("filtered", controller.state.value.filter.text)
            assertEquals("filtered", controller.state.value.items.single().id)
        } finally { releaseOld.complete(Unit); scope.cancel() }
    }
    @Test fun clearNeedsCurrentConfirmationAndRejectedOrMalformedAcknowledgementKeepsRows(): Unit = runBlocking {
        val authority = Authority(); val context = authority.context("one")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val controller = OperationHistoryController(scope) { it === context }
        try {
            main { controller.bind(context); controller.refresh() }; settled(controller)
            main { controller.confirmClear(); controller.requestClear(); controller.cancelClear(); controller.confirmClear() }
            assertTrue(authority.requests.none { it.first == "DELETE" })
            authority.deleteStatus = 403
            main { controller.requestClear(); controller.confirmClear() }; settled(controller)
            assertEquals("web-first", controller.state.value.items.single().id)
            assertNull(controller.state.value.notice)
            assertNotNull(controller.state.value.error)
            authority.deleteStatus = 200; authority.deleteBody = "{}"
            main { controller.requestClear(); controller.confirmClear() }; settled(controller)
            assertEquals(1, controller.state.value.items.size)
            assertNull(controller.state.value.notice)
            authority.deleteBody = "{\"deleted\":2}"
            main { controller.requestClear(); controller.confirmClear(); controller.confirmClear() }; settled(controller)
            assertTrue(controller.state.value.items.isEmpty())
            assertEquals(0, controller.state.value.total)
            assertEquals(3, authority.requests.count { it.first == "DELETE" })
            assertNotNull(controller.state.value.notice)
        } finally { scope.cancel() }
    }
    @Test fun oldConfirmationCannotClearNewAccountAndFilterFailureRetainsPreviousResults(): Unit = runBlocking {
        val authority = Authority(); val other = Authority(2)
        val first = authority.context("old"); val second = other.context("new")
        var active = first
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val controller = OperationHistoryController(scope) { it === active }
        try {
            main { controller.bind(first); controller.refresh() }; settled(controller)
            authority.failGet = true
            main { controller.filter(OperationHistoryFilter(text = "missing", action = "file.rename", status = OperationHistoryStatus.FAILED)) }; settled(controller)
            assertTrue(controller.state.value.showingPreviousFilter)
            assertEquals("web-first", controller.state.value.items.single().id)
            assertTrue(authority.requests.last().second.contains("text=missing&action=file.rename&status=failed"))
            main { controller.requestClear(); active = second; controller.bind(second); controller.confirmClear() }
            assertFalse(controller.state.value.clearConfirmation)
            assertTrue(authority.requests.none { it.first == "DELETE" })
            assertTrue(other.requests.none { it.first == "DELETE" })
        } finally { scope.cancel() }
    }
}
