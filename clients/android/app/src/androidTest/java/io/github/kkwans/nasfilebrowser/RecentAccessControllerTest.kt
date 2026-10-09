package io.github.kkwans.nasfilebrowser

import android.util.Base64
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

@RunWith(AndroidJUnit4::class)
class RecentAccessControllerTest {
    private fun response(status: Int = 200, body: String) = JSONObject().put("status", status).put("body", body)
    private fun row(id: String, path: String, time: Long, wire: String? = null): JSONObject =
        JSONObject().put("id", id).put("path", path).put("name", path.substringAfterLast('/').ifEmpty { "根目录" })
            .put("isDir", false).put("accessedAt", time).apply { wire?.let { put("wirePath", it) } }

    private suspend fun context(owner: String, request: suspend (JSONObject) -> JSONObject): SessionContext {
        val profile = ServerProfile(id = "fixture-profile-$owner", name = "Owned fixture", address = "https://recent.invalid")
        val payload = JSONObject().put("user", JSONObject().put("id", 1).put("username", "fixture"))
        val encoded = Base64.encodeToString(payload.toString().toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val token = "e30.$encoded.owned-fixture"
        val api = NasSession.login(profile, "fixture", "test-owned-password", native = { command ->
            when (command.getString("op")) {
                "open" -> "fixture-session-$owner"
                "login" -> response(body = token)
                "token" -> token
                "request" -> request(command)
                else -> error("Unexpected owned-fixture operation")
            }
        })
        val account = AccountRecord("fixture-account-$owner", profile.id, 0, 1, "fixture", "unused-fixture-vault", 0)
        return SessionContext(profile, account, api, 1, owner)
    }

    @Test fun parsesServerMillisecondsAndKeepsSameDisplayNamesWithDifferentBytes(): Unit = runBlocking {
        val entries = parseRecentAccess(JSONArray().put(row("opaque", "/中文", 10, "/%D6%D0%CE%C4"))
            .put(row("utf8", "/中文", 20, "/%E4%B8%AD%E6%96%87")))
        assertEquals(listOf("utf8", "opaque"), entries.map { it.id })
        assertEquals(20L, entries.first().accessedAt)
        assertNotEquals(entries[0].resource().wirePath, entries[1].resource().wirePath)
        assertFalse(parseRecentAccessEntry(row("old", "/lost�", 30)).openable)
        assertTrue(parseRecentAccessEntry(row("new", "/lost�", 30, "/lost%FF")).openable)
        assertFalse(parseRecentAccessEntry(row("unverified", "/lost�", 30, "/lost%EF%BF%BD").put("pathVerified", false)).openable)
        assertEquals("", parseRecentAccessEntry(row("unverified", "/lost�", 30).put("pathVerified", false)).wirePath)
        assertTrue(parseRecentAccessEntry(row("literal", "/lost�", 30, "/lost%EF%BF%BD").put("pathVerified", true)).openable)
        assertTrue(runCatching { parseRecentAccessEntry(row("bad", "/x", 0)) }.isFailure)
        assertTrue(runCatching { parseRecentAccessEntry(row("bad", "/x", 1).put("isDir", "false")) }.isFailure)
    }

    @Test fun failureRetriesWithoutFabricatingPaginationOrTimestamps(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        var requests = 0
        val session = context("retry") { command ->
            assertEquals("/api/recent?limit=100", command.getString("endpoint"))
            requests++
            if (requests == 1) response(403, "forbidden")
            else response(body = JSONArray().put(row("owned", "/a", 123)).toString())
        }
        try {
            val controller = withContext(Dispatchers.Main) { RecentAccessController(scope) { it === session }.also { it.bind(session); it.setVisible(true) } }
            withTimeout(5000) { controller.state.first { it.error != null } }
            assertTrue(controller.state.value.error!!.contains("没有读取")); assertFalse(controller.state.value.loaded)
            withContext(Dispatchers.Main) { controller.refresh(more = true) }
            assertEquals(1, requests)
            withContext(Dispatchers.Main) { controller.refresh() }
            val state = withTimeout(5000) { controller.state.first { it.loaded && !it.loading } }
            assertEquals(123L, state.items.single().accessedAt); assertNull(state.error)
        } finally { scope.cancel() }
    }

    @Test fun lateListCannotCrossAccountOwnerAndNewOwnerStartsEmpty(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val started = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>(); val completed = CompletableDeferred<Unit>()
        val old = context("old") {
            started.complete(Unit)
            withContext(NonCancellable) { finish.await(); completed.complete(Unit); response(body = JSONArray().put(row("old", "/private", 9)).toString()) }
        }
        val next = context("next") { response(body = "[]") }
        var current = old
        try {
            val controller = withContext(Dispatchers.Main) { RecentAccessController(scope) { it === current }.also { it.bind(old); it.setVisible(true) } }
            withTimeout(5000) { started.await() }
            withContext(Dispatchers.Main) { current = next; controller.bind(next) }
            withTimeout(5000) { controller.state.first { it.scope == next.owner && it.loaded } }
            finish.complete(Unit); withTimeout(5000) { completed.await() }
            withContext(Dispatchers.Main) { yield() }
            assertEquals(next.owner, controller.state.value.scope); assertTrue(controller.state.value.items.isEmpty())
        } finally { finish.complete(Unit); scope.cancel() }
    }

    @Test fun opaqueWriteToOldServerNeverFallsBackToDisplayNameOrAddsLocalRecord(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        var writes = 0
        val session = context("old-server") { command ->
            assertEquals("POST", command.getString("method")); writes++
            assertFalse(command.getJSONObject("body").has("path"))
            assertEquals("/%D6%D0%CE%C4", command.getJSONObject("body").getString("wirePath"))
            response(400, "missing path")
        }
        try {
            val controller = withContext(Dispatchers.Main) { RecentAccessController(scope) { it === session }.also {
                it.bind(session); it.record(ResourceRef("/中文", "/%D6%D0%CE%C4", "中文", false, "", 0))
            } }
            val state = withTimeout(5000) { controller.state.first { it.recordWarning != null } }
            assertTrue(state.recordWarning!!.contains("升级服务器")); assertEquals(1, writes)
            assertTrue(state.items.isEmpty()); assertFalse(state.loaded)
        } finally { scope.cancel() }
    }

    @Test fun acknowledgedOpaqueVisitUsesReturnedTimeAndDoesNotMergeUnicodeSibling(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val session = context("new-server") { command ->
            if (command.getString("method") == "GET") response(body = JSONArray().put(row("utf8", "/中文", 100, "/%E4%B8%AD%E6%96%87")).toString())
            else response(body = row("opaque", "/中文", 200, "/%D6%D0%CE%C4").toString())
        }
        try {
            val controller = withContext(Dispatchers.Main) { RecentAccessController(scope) { it === session }.also { it.bind(session); it.refresh() } }
            withTimeout(5000) { controller.state.first { it.loaded } }
            withContext(Dispatchers.Main) { controller.record(ResourceRef("/中文", "/%D6%D0%CE%C4", "中文", false, "", 0)) }
            val state = withTimeout(5000) { controller.state.first { it.items.size == 2 } }
            assertEquals(listOf("opaque", "utf8"), state.items.map { it.id }); assertEquals(200L, state.items.first().accessedAt)
            assertNull(state.recordWarning)
        } finally { scope.cancel() }
    }

    @Test fun legacyUtf8WriteFallsBackOnceAndPreservesLiteralPercentName(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        var writes = 0
        val path = "/a%2Fb +?#.mkv"
        val session = context("legacy-utf8") { command ->
            writes++
            val body = command.getJSONObject("body")
            if (writes == 1) {
                assertFalse(body.has("path")); response(400, "old server")
            } else {
                assertEquals(path, body.getString("path")); response(body = row("legacy", path, 456).toString())
            }
        }
        try {
            val controller = withContext(Dispatchers.Main) { RecentAccessController(scope) { it === session }.also {
                it.bind(session); it.record(ResourceRef(path, "/a%252Fb%20%2B%3F%23.mkv", "a%2Fb +?#.mkv", false, "video", 0))
            } }
            val state = withTimeout(5000) { controller.state.first { it.items.isNotEmpty() } }
            assertEquals(2, writes); assertEquals(path, state.items.single().path); assertEquals(456L, state.items.single().accessedAt)
        } finally { scope.cancel() }
    }

    @Test fun freshLiteralReplacementCharacterVisitDoesNotMergeUnverifiedHistory(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val session = context("replacement-identity") { command ->
            if (command.getString("method") == "GET") response(body = JSONArray().put(row("legacy", "/lost�", 100).put("pathVerified", false)).toString())
            else response(body = row("fresh", "/lost�", 200, "/lost%EF%BF%BD").put("pathVerified", true).toString())
        }
        try {
            val controller = withContext(Dispatchers.Main) { RecentAccessController(scope) { it === session }.also { it.bind(session); it.refresh() } }
            withTimeout(5000) { controller.state.first { it.loaded } }
            withContext(Dispatchers.Main) { controller.record(ResourceRef("/lost�", "/lost%EF%BF%BD", "lost�", false, "", 0)) }
            val state = withTimeout(5000) { controller.state.first { it.items.size == 2 } }
            val legacy = state.items.single { it.id == "legacy" }
            assertFalse(legacy.openable); assertEquals("", legacy.wirePath)
            assertTrue(state.items.single { it.id == "fresh" }.openable)
            assertNull(state.recordWarning)
        } finally { scope.cancel() }
    }

    @Test fun lateWriteCannotCrossAccountOrBlockNewOwnersWrite(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val started = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>(); val completed = CompletableDeferred<Unit>()
        val old = context("old-write") {
            started.complete(Unit)
            withContext(NonCancellable) { finish.await(); completed.complete(Unit); response(body = row("old", "/old", 999).toString()) }
        }
        val next = context("new-write") { response(body = row("new", "/new", 100).toString()) }
        var current = old
        try {
            val controller = withContext(Dispatchers.Main) { RecentAccessController(scope) { it === current }.also {
                it.bind(old); it.record(ResourceRef("/old", "/old", "old", false, "", 0))
            } }
            withTimeout(5000) { started.await() }
            withContext(Dispatchers.Main) {
                current = next; controller.bind(next); controller.record(ResourceRef("/new", "/new", "new", false, "", 0))
            }
            withTimeout(5000) { controller.state.first { it.items.singleOrNull()?.id == "new" } }
            finish.complete(Unit); withTimeout(5000) { completed.await() }
            withContext(Dispatchers.Main) { yield() }
            assertEquals(next.owner, controller.state.value.scope); assertEquals("new", controller.state.value.items.single().id)
        } finally { finish.complete(Unit); scope.cancel() }
    }
}
