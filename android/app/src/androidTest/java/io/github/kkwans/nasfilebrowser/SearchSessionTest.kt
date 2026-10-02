package io.github.kkwans.nasfilebrowser

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.kkwans.nasfilebrowser.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SearchSessionTest {
    private fun token(id: Long): String {
        val payload = JSONObject().put("user", JSONObject().put("id", id).put("username", "fixture"))
        return "header." + Base64.encodeToString(payload.toString().toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING) + ".signature"
    }
    private fun item(path: String = "目录/片🎬 %2F?#.mkv") = JSONObject().put("path", path).put("name", path.substringAfterLast('/'))
        .put("dir", false).put("size", 123).put("modified", "2026-10-01T00:00:00Z").put("riskLevel", "low")
    private fun batch(done: Boolean, state: String = if (done) "finished" else "running", reason: String = "completed") =
        JSONObject().put("items", if (done) JSONArray() else JSONArray().put(item())).put("count", 1).put("done", done).put("state", state)
            .apply { if (done) put("summary", JSONObject().put("reason", reason).put("count", 1)) }

    @Test fun batchesBindAccountPreserveEncodingAndReportPartialCompletion() = runBlocking {
        val calls = mutableListOf<JSONObject>()
        var polls = 0
        val profile = ServerProfile(name = "fixture", address = "http://fixture.example.test")
        val session = NasSession.restore(profile, token(7), 7) { command ->
            calls += command
            when (command.getString("op")) {
                "open" -> "source-a"
                "token" -> token(7)
                "search_start" -> "query-a"
                "search_poll" -> batch(polls++ > 0, reason = "limit")
                else -> null
            }
        }
        val updates = mutableListOf<SearchUpdate>()
        session.search("/旧目录", "/%D6%D0", "片🎬 & + #", SearchScope.RECURSIVE).collect { updates += it }
        assertEquals(2, updates.size)
        assertEquals(SearchEnding.LIMIT, updates.last().ending)
        assertTrue(updates.last().message!!.contains("上限"))
        val resource = updates.first().items.single().resource("/旧目录", "/%D6%D0")!!
        assertEquals("/旧目录/目录/片🎬 %2F?#.mkv", resource.path)
        assertEquals("/%D6%D0/%E7%9B%AE%E5%BD%95/%E7%89%87%F0%9F%8E%AC%20%252F%3F%23.mkv", resource.wirePath)
        val start = calls.first { it.getString("op") == "search_start" }
        assertEquals("片🎬 & + #", start.getString("query"))
        assertEquals("recursive", start.getString("scope"))
        for (command in calls.filter { it.getString("op").startsWith("search_") }) assertEquals("source-a", command.getString("session"))
        assertEquals("query-a", calls.last().getString("search"))
        assertEquals("search_cancel", calls.last().getString("op"))
    }

    @Test fun cancellationAndChangedPrincipalReleaseWithoutEmittingForeignResults() = runBlocking {
        var cancel = false
        val suspended = CompletableDeferred<Unit>()
        val session = NasSession.restore(ServerProfile(name = "fixture", address = "http://fixture.example.test"), token(7), 7) { command ->
            when (command.getString("op")) {
                "open" -> "source-a"
                "token" -> token(7)
                "search_start" -> "query-a"
                "search_poll" -> { suspended.complete(Unit); awaitCancellation() }
                "search_cancel" -> { cancel = true; null }
                else -> null
            }
        }
        val collecting = launch { session.search("/", "", "film", SearchScope.CURRENT).collect { fail("Canceled search emitted a result") } }
        suspended.await()
        collecting.cancelAndJoin()
        assertTrue(cancel)

        cancel = false
        var changed = false
        val switched = NasSession.restore(ServerProfile(name = "fixture", address = "http://fixture.example.test"), token(7), 7) { command ->
            when (command.getString("op")) {
                "open" -> "source-b"
                "token" -> token(if (changed) 8 else 7)
                "search_start" -> "query-b"
                "search_poll" -> { changed = true; batch(false) }
                "search_cancel" -> { cancel = true; null }
                else -> null
            }
        }
        val failure = runCatching { switched.search("/", "", "film", SearchScope.CURRENT).collect { fail("Foreign account result was accepted") } }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty().contains("账号已变化"))
        assertTrue(cancel)
    }

    @Test fun ambiguousOrUnsafePathsAreNeverGuessedAndInvalidTerminationFails() = runBlocking {
        assertNull(SearchResult("坏�名字.mkv", "坏�名字.mkv", false, 1, "", "low").resource("/", "/"))
        assertEquals("/base/%20", SearchResult(" ", " ", false, 1, "", "low").resource("/base", "")!!.wirePath)
        for (path in listOf("../file", "/absolute", "a//b", "a/./b", "a\u0000b")) assertTrue(runCatching { SearchResult(path, "file", false, 1, "", "low") }.isFailure)
        var canceled = false
        val session = NasSession.restore(ServerProfile(name = "fixture", address = "http://fixture.example.test"), token(7), 7) { command ->
            when (command.getString("op")) {
                "open" -> "source"
                "token" -> token(7)
                "search_start" -> "query"
                "search_poll" -> batch(true).apply { remove("summary") }
                "search_cancel" -> { canceled = true; null }
                else -> null
            }
        }
        assertTrue(runCatching { session.search("/", "", "film", SearchScope.CURRENT).collect {} }.isFailure)
        assertTrue(canceled)
    }
}
