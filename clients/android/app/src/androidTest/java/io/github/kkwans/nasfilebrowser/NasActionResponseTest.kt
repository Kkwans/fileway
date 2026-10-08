package io.github.kkwans.nasfilebrowser

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.kkwans.nasfilebrowser.data.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NasActionResponseTest {
    private suspend fun session(status: Int, body: String): NasSession {
        val token = "owned." + android.util.Base64.encodeToString("{\"user\":{\"id\":1,\"username\":\"fixture\"}}".toByteArray(), android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP) + ".fixture"
        return NasSession.restore(ServerProfile(name = "Owned status response", address = "https://fixture.invalid"), token, 1) { command ->
            when (command.getString("op")) {
                "open" -> "owned-status"
                "token" -> token
                "request" -> JSONObject().put("status", status).put("body", body)
                else -> error("Unexpected operation")
            }
        }
    }
    @Test fun acceptedPlainStatusAndEmptyBodiesAreAcknowledgedWhileStructuredObjectsRemainRequired(): Unit = runBlocking {
        for ((status, body) in listOf(200 to "200 OK\n", 201 to "201 Created", 202 to "202 Accepted\n", 204 to ""))
            assertEquals(0, session(status, body).action("DELETE", "/api/tags/owned").length())
        assertEquals("owned-task", session(202, "{\"id\":\"owned-task\"}").action("POST", "/api/resources/transfer").getString("id"))
    }
    @Test fun malformedMismatchedAndFailedResponsesAreNeverAcknowledged(): Unit = runBlocking {
        for (body in listOf("not-json", "201 Created", "200 OK error")) {
            var rejected = false
            try { session(200, body).action("DELETE", "/api/tags/owned") } catch (_: org.json.JSONException) { rejected = true }
            assertTrue(rejected)
        }
        var forbidden = false
        try { session(403, "200 OK").action("DELETE", "/api/tags/owned") } catch (failure: ServiceException) { forbidden = failure.status == 403 }
        assertTrue(forbidden)
        var invalidRead = false
        try { session(200, "200 OK").request("GET", "/api/tasks/owned") } catch (_: org.json.JSONException) { invalidRead = true }
        assertTrue(invalidRead)
    }
}
