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

internal class OwnedShellFixture {
    val directory = DirectoryCrumb("原目录", "/�", "/%FF")
    var enabled = true
    var execute = true
    var hold: CompletableDeferred<Unit>? = null
    val entered = CompletableDeferred<Unit>()
    val finished = CompletableDeferred<Unit>()
    val starts = mutableListOf<JSONObject>()
    val cancels = mutableListOf<JSONObject>()
    var polls = 0
    suspend fun session(owner: String): SessionContext {
        val profile = ServerProfile(name = "Owned command fixture", address = "https://fixture.invalid")
        val token = "owned." + Base64.encodeToString("{\"user\":{\"id\":1,\"username\":\"fixture\",\"perm\":{\"execute\":true}}}".toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP) + ".fixture"
        val api = NasSession.restore(profile, token, 1) { command -> when (command.getString("op")) {
            "open" -> "owned-shell-$owner"
            "token" -> token
            "request" -> {
                assertEquals("GET", command.getString("method"))
                val response = when (command.getString("endpoint")) {
                    "/api/client-capabilities" -> JSONObject().put("enableExec", enabled)
                    "/api/users/1" -> JSONObject().put("id", 1).put("perm", JSONObject().put("execute", execute)).put("commands", JSONArray().put("echo").put("pwd"))
                    "/api/resources/%FF?metadata=1" -> JSONObject().put("path", directory.path).put("wirePath", directory.wirePath).put("isDir", true)
                    else -> error("Unexpected policy endpoint")
                }
                JSONObject().put("status", 200).put("body", response.toString())
            }
            "command_start" -> {
                starts.add(JSONObject(command.toString())); assertEquals(directory.wirePath, command.getString("wirePath")); "command-$owner"
            }
            "command_poll" -> {
                polls++
                val gate = hold
                val lines = JSONArray().put(if (gate == null) "\u001B[31mowned output\u001B[0m" else "old output")
                entered.complete(Unit)
                gate?.let { try { withContext(NonCancellable) { it.await() } } finally { finished.complete(Unit) } }
                JSONObject().put("lines", lines).put("done", true).put("state", "closed").put("dropped", 0)
            }
            "command_cancel" -> { cancels.add(JSONObject(command.toString())); Unit }
            else -> error("Unexpected native operation")
        } }
        return SessionContext(profile, AccountRecord(owner, profile.id, 0, 1, "fixture", "fixture-only", 0), api, 1, owner)
    }
}

@RunWith(AndroidJUnit4::class)
class ShellControllerTest {
    private suspend fun main(action: () -> Unit) = withContext(Dispatchers.Main) { action() }
    @Test fun disabledCapabilityAndMissingPermissionNeverStartAndNativeCloseHasNoExitStatusClaim(): Unit = runBlocking {
        val fixture = OwnedShellFixture(); val owner = fixture.session("one")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val controller = ShellController(scope) { it === owner }
        try {
            fixture.enabled = false
            main { controller.bind(owner); controller.open(fixture.directory, owner.api.id) }
            withTimeout(5000) { controller.state.first { !it.loading && it.error != null } }
            main { controller.input("echo owned"); controller.run() }
            withTimeout(5000) { controller.state.first { !it.loading && !it.running && it.error != null } }
            assertTrue(fixture.starts.isEmpty())
            fixture.enabled = true; fixture.execute = false
            main { controller.refresh() }
            withTimeout(5000) { controller.state.first { !it.loading && !it.execute } }
            main { controller.run() }
            withTimeout(5000) { controller.state.first { !it.loading && it.error != null } }
            assertTrue(fixture.starts.isEmpty())
            fixture.execute = true
            main { controller.run() }
            withTimeout(5000) { controller.state.first { !it.running && !it.loading && it.output.lines.isNotEmpty() } }
            assertEquals(listOf("owned output"), controller.state.value.output.lines)
            assertTrue(controller.state.value.message!!.contains("未提供退出码"))
            withTimeout(5000) { while (fixture.cancels.isEmpty()) delay(10) }
            assertEquals(1, fixture.starts.size)
            assertEquals("owned-shell-one", fixture.cancels.single().getString("session"))
        } finally { main { controller.close() }; scope.cancel() }
    }
    @Test fun lateOldSessionOutputIsCanceledAtItsOriginalHandleAndCannotEnterNewAccount(): Unit = runBlocking {
        val old = OwnedShellFixture(); val fresh = OwnedShellFixture()
        val first = old.session("old"); val second = fresh.session("new"); var active = first
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val controller = ShellController(scope) { it === active }
        old.hold = CompletableDeferred()
        try {
            main { controller.bind(first); controller.open(old.directory, first.api.id) }
            withTimeout(5000) { controller.state.first { !it.loading && it.execute } }
            main { controller.input("echo owned"); controller.run() }
            withTimeout(5000) { old.entered.await() }
            main { active = second; controller.bind(second); controller.open(fresh.directory, second.api.id) }
            old.hold!!.complete(Unit)
            withTimeout(5000) { while (old.cancels.isEmpty()) delay(10) }
            assertEquals("new", controller.state.value.scope)
            assertTrue(controller.state.value.output.lines.isEmpty())
            assertEquals("command-old", old.cancels.single().getString("commandHandle"))
            assertTrue(fresh.starts.isEmpty())
        } finally { old.hold?.complete(Unit); main { controller.close() }; scope.cancel() }
    }
}
