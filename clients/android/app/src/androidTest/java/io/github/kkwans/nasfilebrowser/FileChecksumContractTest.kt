package io.github.kkwans.nasfilebrowser

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.kkwans.nasfilebrowser.app.*
import io.github.kkwans.nasfilebrowser.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FileChecksumContractTest {
    private suspend fun context(owner: String, read: suspend (String) -> JSONObject): SessionContext {
        val token = "owned." + android.util.Base64.encodeToString("{\"user\":{\"id\":1,\"username\":\"fixture\",\"perm\":{\"download\":true}}}".toByteArray(), android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP) + ".fixture"
        val profile = ServerProfile(name = "Owned checksum", address = "https://fixture.invalid")
        val api = NasSession.restore(profile, token, 1) { command -> when (command.getString("op")) {
            "open" -> owner
            "token" -> token
            "request" -> JSONObject().put("status", 200).put("body", read(command.getString("endpoint")).toString())
            else -> error("Unexpected checksum fixture command")
        } }
        return SessionContext(profile, AccountRecord(owner, profile.id, 0, 1, "fixture", "owned", 0), api, 1, owner)
    }
    private val file = ResourceRef("/�.txt", "/%FF.txt", "�.txt", false, "text", 12)
    private fun result(algorithm: String, digest: String, wire: String = file.wirePath) = JSONObject().put("isDir", false)
        .put("path", file.path).put("wirePath", wire).put("size", file.size).put("checksums", JSONObject().put(algorithm, digest))

    @Test fun calculationIsExplicitUsesOriginalWireAndRejectsUnrelatedResult(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val seen = mutableListOf<String>()
        var reply = result("md5", "A".repeat(32))
        val session = context("first") { endpoint -> seen.add(endpoint); reply }
        val controller = FileChecksumController(scope) { it === session }
        try {
            withContext(Dispatchers.Main) { controller.bind(session); controller.open(file, session.api.id) }
            assertTrue(seen.isEmpty())
            withContext(Dispatchers.Main) { controller.algorithm(ChecksumAlgorithm.MD5); controller.expected("a".repeat(32)); controller.calculate() }
            val done = withTimeout(5000) { controller.state.first { !it.busy } }
            assertEquals(listOf("/api/resources/%FF.txt?checksum=md5"), seen)
            assertEquals(true, done.matches)
            reply = result("md5", "B".repeat(32), "/%FE.txt")
            withContext(Dispatchers.Main) { controller.calculate() }
            val rejected = withTimeout(5000) { controller.state.first { !it.busy } }
            assertNull(rejected.result); assertNotNull(rejected.error)
        } finally { scope.cancel() }
    }

    @Test fun lateOldAccountDigestCannotReplaceCurrentFile(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); val finished = CompletableDeferred<Unit>()
        val old = context("old") {
            entered.complete(Unit)
            try { withContext(NonCancellable) { release.await() }; result("sha256", "a".repeat(64)) }
            finally { finished.complete(Unit) }
        }
        val new = context("new") { result("sha256", "b".repeat(64)) }
        var active = old
        val controller = FileChecksumController(scope) { it === active }
        try {
            withContext(Dispatchers.Main) { controller.bind(old); controller.open(file, old.api.id); controller.calculate() }
            withTimeout(5000) { entered.await() }
            active = new
            withContext(Dispatchers.Main) { controller.bind(new); controller.open(file, new.api.id); controller.calculate() }
            withTimeout(5000) { controller.state.first { !it.busy } }
            release.complete(Unit); withTimeout(5000) { finished.await() }
            withContext(Dispatchers.Main) { yield() }
            assertEquals("new", controller.state.value.scope)
            assertEquals("b".repeat(64), controller.state.value.result)
            withContext(Dispatchers.Main) { controller.close() }
            assertNull(controller.state.value.file)
        } finally { release.complete(Unit); scope.cancel() }
    }
}
