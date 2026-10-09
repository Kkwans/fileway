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

@RunWith(AndroidJUnit4::class)
class TagsContractTest {
    private class Authority {
        val refs = JSONArray()
        val writes = mutableListOf<JSONObject>()
        val batches = mutableListOf<JSONObject>()
        var legacy = false
        var mismatch = false
        var hold: CompletableDeferred<Unit>? = null
        val entered = CompletableDeferred<Unit>()
        val token = "owned." + android.util.Base64.encodeToString("{\"user\":{\"id\":1,\"username\":\"fixture\"}}".toByteArray(), android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP) + ".owned"
        fun tag() = JSONObject().put("id", "owned-tag").put("name", "Owned tag").put("color", "#123456").put("paths", JSONArray((0 until refs.length()).map { refs.getJSONObject(it).getString("path") })).apply { if (!legacy) put("pathRefs", JSONArray(refs.toString())) }
        fun add(path: String, wire: String, verified: Boolean = true) { refs.put(JSONObject().put("path", path).put("wirePath", wire).put("pathVerified", verified)) }
        suspend fun call(command: JSONObject): Any = when (command.getString("op")) {
            "open" -> "owned-tag-session"
            "token" -> token
            "request" -> {
                check(command.toString().toByteArray(Charsets.UTF_8).size < 1024 * 1024) { "Owned JNI request exceeded 1 MiB" }
                val endpoint = command.getString("endpoint"); val method = command.getString("method")
                var status = 200
                val body: Any = when {
                    method == "GET" && endpoint == "/api/tags" -> {
                        val captured = JSONArray().put(tag()).toString(); val gate = hold
                        if (gate != null) { entered.complete(Unit); withContext(NonCancellable) { gate.await() } }
                        captured
                    }
                    endpoint == "/api/resources/batch" -> {
                        val input = command.getJSONObject("body"); batches.add(JSONObject(input.toString()))
                        val wires = input.getJSONArray("wirePaths"); val rows = JSONArray()
                        for (i in 0 until wires.length()) {
                            val wire = wires.getString(i)
                            val ref = (0 until refs.length()).map { refs.getJSONObject(it) }.first { it.optString("wirePath") == wire }
                            val row = JSONObject().put("path", ref.getString("path")).put("wirePath", if (mismatch) "/wrong.txt" else wire).put("status", if (wire.endsWith("missing.txt")) 404 else 200)
                            if (row.getInt("status") == 200) row.put("item", JSONObject().put("path", ref.getString("path")).put("wirePath", wire).put("name", "owned.txt").put("isDir", false).put("type", "text").put("size", 1))
                            rows.put(row)
                        }
                        rows
                    }
                    endpoint.endsWith("/paths") -> {
                        val input = command.getJSONObject("body"); writes.add(JSONObject(command.toString()))
                        if (legacy && !input.has("path")) { status = 400; "{}" }
                        else {
                            val wire = input.getString("wirePath")
                            if (method == "POST") add(input.optString("path").ifEmpty { "/中文.txt" }, wire)
                            else for (i in refs.length() - 1 downTo 0) if (refs.getJSONObject(i).optString("wirePath") == wire) refs.remove(i)
                            tag()
                        }
                    }
                    else -> error("Unexpected owned call $method $endpoint")
                }
                JSONObject().put("status", status).put("body", body.toString())
            }
            else -> error("Unexpected native op")
        }
        suspend fun context(owner: String): SessionContext {
            val profile = ServerProfile(name = "Owned tags fixture", address = "https://fixture.invalid")
            val api = NasSession.restore(profile, token, 1, ::call)
            return SessionContext(profile, AccountRecord(owner, profile.id, 0, 1, "fixture", "owned", 0), api, 1, owner)
        }
    }

    @Test fun sameDisplayAssociationsAndPaginationKeepMissingRowLocal(): Unit = runBlocking {
        val authority = Authority(); val context = authority.context("one")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val controller = TagsController(scope) { it === context }
        val opaque = ResourceRef("/中文.txt", "/%D6%D0%CE%C4.txt", "中文.txt", false, "text", 1)
        val utf8 = opaque.copy(wirePath = SearchResult.encodePath(opaque.path))
        try {
            withContext(Dispatchers.Main) { controller.bind(context); controller.refresh() }
            withTimeout(3000) { controller.state.first { it.loaded && !it.loading } }
            for (file in listOf(opaque, utf8)) {
                withContext(Dispatchers.Main) { controller.assign(file, emptySet(), setOf("owned-tag")) }
                withTimeout(3000) { controller.state.first { !it.changing && controller.assigned(file).isNotEmpty() } }
            }
            assertFalse(authority.writes[0].getJSONObject("body").has("path"))
            assertEquals(utf8.path, authority.writes[1].getJSONObject("body").getString("path"))
            assertEquals(2, controller.state.value.items.single().pathRefs.size)
            for (index in 0 until 119) authority.add("/owned-$index.txt", "/owned-$index.txt")
            authority.add("/missing.txt", "/missing.txt")
            withContext(Dispatchers.Main) { controller.refresh() }
            withTimeout(3000) { controller.state.first { !it.loading && it.items.single().pathRefs.size == 122 } }
            withContext(Dispatchers.Main) { controller.loadPaths("owned-tag", null) }
            withTimeout(3000) { controller.state.first { !it.pathsLoading && it.paths.size == 40 } }
            repeat(3) {
                withContext(Dispatchers.Main) { controller.loadPaths("owned-tag", null, true); controller.loadPaths("owned-tag", null, true) }
                withTimeout(3000) { controller.state.first { !it.pathsLoading } }
            }
            assertEquals(122, controller.state.value.paths.size)
            assertEquals(121, controller.state.value.paths.count { it.file != null })
            assertEquals("文件已移走或删除", controller.state.value.paths.last().error)
            assertEquals(122, controller.state.value.paths.map { it.key }.toSet().size)
            assertTrue(authority.batches.all { it.getJSONArray("wirePaths").length() <= 100 })
            val ref = controller.state.value.items.single().pathRefs.first()
            withContext(Dispatchers.Main) { controller.removePath(controller.state.value.items.single(), ref) }
            withTimeout(3000) { controller.state.first { !it.changing && it.items.single().pathRefs.size == 121 } }
            assertTrue(controller.assigned(opaque).isEmpty()); assertTrue(controller.assigned(utf8).isNotEmpty())
        } finally { scope.cancel() }
    }

    @Test fun legacyOpaqueWriteIsNotRetriedAndLateAccountCannotReplaceNewReferences(): Unit = runBlocking {
        val old = Authority(); old.legacy = true; val fresh = Authority(); fresh.add("/fresh.txt", "/fresh.txt")
        val one = old.context("old"); val two = fresh.context("new"); var active = one
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val controller = TagsController(scope) { it === active }
        try {
            withContext(Dispatchers.Main) { controller.bind(one); controller.refresh() }
            withTimeout(3000) { controller.state.first { it.loaded && !it.loading } }
            withContext(Dispatchers.Main) { controller.assign(ResourceRef("/中文.txt", "/%D6%D0%CE%C4.txt", "中文", false, "", 0), emptySet(), setOf("owned-tag")) }
            withTimeout(3000) { controller.state.first { !it.changing && it.error != null } }
            assertEquals(1, old.writes.size); assertFalse(old.writes.single().getJSONObject("body").has("path"))
            old.hold = CompletableDeferred()
            withContext(Dispatchers.Main) { controller.refresh() }
            withTimeout(3000) { old.entered.await() }
            withContext(Dispatchers.Main) { active = two; controller.bind(two); controller.refresh() }
            withTimeout(3000) { controller.state.first { it.loaded && it.scope == "new" } }
            old.hold!!.complete(Unit); delay(100)
            assertEquals("/fresh.txt", controller.state.value.items.single().pathRefs.single().path)
            val previous = ServerTag.from(old.tag())
            withContext(Dispatchers.Main) { controller.removePath(previous, tagPathRef("/old.txt", "/old.txt", true), one.owner) }
            assertTrue(fresh.writes.isEmpty()); assertNull(controller.state.value.error)
        } finally { old.hold?.complete(Unit); scope.cancel() }
    }

    @Test fun readonlyBatchSplitsItsEncodedBudgetAndUnverifiedReferenceCannotUnlinkUnicodeSibling(): Unit = runBlocking {
        val authority = Authority(); val context = authority.context("budget")
        val prefix = "/" + List(16) { "\u0001".repeat(200) }.joinToString("/")
        val paths = (0 until 40).map { "$prefix/$it.txt" }
        val wires = paths.map { SearchResult.encodePath(it) }
        paths.indices.forEach { authority.add(paths[it], wires[it]) }
        val result = withTimeout(5000) { context.api.resourceBatch(paths, wires) }
        assertEquals(40, result.length())
        assertTrue(authority.batches.size > 1)
        assertTrue(authority.batches.all { it.toString().toByteArray(Charsets.UTF_8).size <= 768 * 1024 })
        paths.indices.forEach { assertEquals(wires[it], result.getJSONObject(it).getString("wirePath")) }
        authority.refs.remove(0)
        authority.add("/lost�", "", false); authority.add("/lost�", "/lost%EF%BF%BD", true)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val controller = TagsController(scope) { it === context }
        try {
            withContext(Dispatchers.Main) { controller.bind(context); controller.refresh() }
            withTimeout(3000) { controller.state.first { it.loaded && !it.loading } }
            val tag = controller.state.value.items.single()
            val unknown = tag.pathRefs.first { !it.openable }
            withContext(Dispatchers.Main) { controller.removePath(tag, unknown) }
            withTimeout(3000) { controller.state.first { !it.changing && it.error != null } }
            assertTrue(authority.writes.isEmpty())
            assertTrue(controller.assigned(ResourceRef("/lost�", "/lost%EF%BF%BD", "actual", false, "", 0)).isNotEmpty())
        } finally { scope.cancel() }
    }

    @Test fun opaqueParentsFilterIndependentlyAndWrongBatchAcknowledgementKeepsUsableRows(): Unit = runBlocking {
        val authority = Authority()
        val opaque = "/%D6%D0%CE%C4/child.txt"; val utf8 = "/%E4%B8%AD%E6%96%87/child.txt"
        authority.add("/中文/child.txt", opaque); authority.add("/中文/child.txt", utf8)
        val context = authority.context("parents")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val controller = TagsController(scope) { it === context }
        try {
            withContext(Dispatchers.Main) { controller.bind(context); controller.refresh() }
            withTimeout(3000) { controller.state.first { it.loaded && !it.loading } }
            withContext(Dispatchers.Main) { controller.loadPaths("owned-tag", "/中文", parentWire = "/%D6%D0%CE%C4") }
            withTimeout(3000) { controller.state.first { !it.pathsLoading && it.paths.size == 1 } }
            assertEquals(opaque, controller.state.value.paths.single().file!!.wirePath)
            withContext(Dispatchers.Main) { controller.loadPaths("owned-tag", "/中文", parentWire = "/%E4%B8%AD%E6%96%87") }
            withTimeout(3000) { controller.state.first { !it.pathsLoading && it.paths.size == 1 } }
            assertEquals(utf8, controller.state.value.paths.single().file!!.wirePath)
            authority.mismatch = true
            withContext(Dispatchers.Main) { controller.loadPaths("owned-tag", "/中文", parentWire = "/%E4%B8%AD%E6%96%87") }
            withTimeout(3000) { controller.state.first { !it.pathsLoading && it.pathsError != null } }
            assertEquals(utf8, controller.state.value.paths.single().file!!.wirePath)
        } finally { scope.cancel() }
    }
}
