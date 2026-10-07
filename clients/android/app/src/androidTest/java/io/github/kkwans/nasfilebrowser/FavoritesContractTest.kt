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

/** API contract and stale-account isolation; no personal database or server mutations. */
@RunWith(AndroidJUnit4::class)
class FavoritesContractTest {
    private class Authority {
        var rows = JSONArray().put(JSONObject().put("id", "web-id").put("path", "/films/web.mkv").put("name", "Web收藏").put("groupId", "web-group").put("order", 0))
        var groups = JSONArray().put(JSONObject().put("id", "web-group").put("name", "电影").put("color", "#3F72D8").put("order", 0))
        val token = "owned." + android.util.Base64.encodeToString("{\"user\":{\"id\":1,\"username\":\"fixture\"}}".toByteArray(), android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP) + ".fixture"
        val mutations = mutableListOf<JSONObject>()
        var hold: CompletableDeferred<Unit>? = null
        suspend fun call(command: JSONObject): Any = when (command.getString("op")) {
            "open" -> "owned-session"
            "token" -> token
            "request" -> {
                val endpoint = command.getString("endpoint"); val method = command.getString("method")
                var status = 200
                val body = if (method == "GET") when (endpoint) {
                    "/api/favorites" -> rows.toString()
                    "/api/favorites/groups" -> {
                        val captured = groups.toString()
                        hold?.let { withContext(NonCancellable) { it.await() } }
                        captured
                    }
                    else -> error("Unexpected GET $endpoint")
                } else {
                    mutations.add(JSONObject(command.toString()))
                    val input = command.optJSONObject("body")
                    when {
                        endpoint == "/api/favorites" && method == "POST" -> {
                            status = 201
                            rows.put(JSONObject(input!!.toString()).put("id", "server-created").put("order", rows.length()))
                        }
                        endpoint == "/api/favorites/reorder" -> {
                            val ids = input!!.getJSONArray("ids")
                            for (i in 0 until ids.length()) for (j in 0 until rows.length()) if (rows.getJSONObject(j).getString("id") == ids.getString(i)) rows.getJSONObject(j).put("order", i)
                        }
                        endpoint == "/api/favorites/server-created" && method == "DELETE" -> { status = 204; rows.remove((0 until rows.length()).single { rows.getJSONObject(it).getString("id") == "server-created" }) }
                        else -> error("Unexpected mutation $method $endpoint")
                    }
                    if (status == 204) "" else "{}"
                }
                JSONObject().put("status", status).put("body", body)
            }
            else -> error("Unexpected native op")
        }
        suspend fun context(owner: String): SessionContext {
            val profile = ServerProfile(name = "Owned contract fixture", address = "https://fixture.invalid")
            val api = NasSession.restore(profile, token, 1, ::call)
            return SessionContext(profile, AccountRecord(owner, profile.id, 0, 1, "fixture", "fixture-only", 0), api, 1, owner)
        }
    }
    @Test fun favoritesUseRemoteIdsExactPathsAndEmptyMutationResponses(): Unit = runBlocking {
        val authority = Authority(); val context = authority.context("one")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val controller = FavoritesController(scope) { it === context }
        suspend fun main(action: () -> Unit) = withContext(Dispatchers.Main) { action() }
        try {
            main { controller.bind(context); controller.refresh() }
            withTimeout(3000) { controller.state.first { it.loaded && !it.loading } }
            assertEquals("web-id", controller.state.value.items.single().id)
            assertEquals("电影", controller.state.value.groups.single().name)
            val path = "/films/中文 #?%.mkv"
            main { controller.add(ResourceRef(path, SearchResult.encodePath(path), "中文 #?%.mkv", false, "video", 12), "web-group") }
            withTimeout(3000) { controller.state.first { !it.changing && it.items.size == 2 } }
            assertEquals(path, authority.mutations.single().getJSONObject("body").getString("path"))
            assertEquals("server-created", controller.favorite(path)!!.id)
            main { controller.move(controller.favorite(path)!!, -1) }
            withTimeout(3000) { controller.state.first { !it.changing && it.items.first().id == "server-created" } }
            assertEquals(listOf("server-created", "web-id"), controller.state.value.items.map { it.id })
            main { controller.remove(controller.favorite(path)!!) }
            withTimeout(3000) { controller.state.first { !it.changing && it.items.size == 1 } }
            assertNull(controller.state.value.error)
            authority.rows.getJSONObject(0).put("name", "网页端改名")
            main { controller.refresh() }
            withTimeout(3000) { controller.state.first { !it.loading && it.items.single().name == "网页端改名" } }
        } finally { scope.cancel() }
    }
    @Test fun latePreviousAccountReadCannotReplaceTheNewCollection(): Unit = runBlocking {
        val old = Authority(); val fresh = Authority()
        fresh.rows.getJSONObject(0).put("id", "new-account-id")
        val first = old.context("old"); val second = fresh.context("new")
        var active = first
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val controller = FavoritesController(scope) { it === active }
        old.hold = CompletableDeferred()
        try {
            withContext(Dispatchers.Main) { controller.bind(first); controller.refresh() }
            delay(100)
            withContext(Dispatchers.Main) { active = second; controller.bind(second); controller.refresh() }
            withTimeout(3000) { controller.state.first { it.loaded && it.items.single().id == "new-account-id" } }
            old.hold!!.complete(Unit); delay(100)
            assertEquals("new", controller.state.value.scope)
            assertEquals("new-account-id", controller.state.value.items.single().id)
        } finally { old.hold?.complete(Unit); scope.cancel() }
    }
}
