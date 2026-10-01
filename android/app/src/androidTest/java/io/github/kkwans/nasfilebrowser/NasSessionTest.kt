package io.github.kkwans.nasfilebrowser

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.kkwans.nasfilebrowser.data.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NasSessionTest {
    private fun token(id: Long): String {
        val payload = JSONObject().put("user", JSONObject().put("id", id).put("username", "viewer"))
        return "header." + Base64.encodeToString(payload.toString().toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING) + ".signature"
    }
    @Test fun textJwtLoginAndOpaqueEndpointRemainBoundToSession() = runBlocking {
        val profile = ServerProfile(name = "NAS", address = "https://nas.example.test:8443/base")
        val calls = mutableListOf<JSONObject>()
        val native: suspend (JSONObject) -> Any? = { command ->
            calls += command
            when (command.getString("op")) {
                "open" -> { assertEquals(profile.address, command.getString("baseUrl")); "session-a" }
                "login" -> JSONObject().put("status", 200).put("body", token(7))
                "token" -> token(7)
                "request" -> JSONObject().put("status", 200).put("body", "{\"items\":[]}")
                else -> null
            }
        }
        val session = NasSession.login(profile, "viewer", "test-fixture", native)
        assertEquals(7L, session.identity.id)
        session.request("GET", "/api/resources/%ed%a0%80%2B")
        assertEquals("/api/resources/%ed%a0%80%2B", calls.first { it.optString("op") == "request" }.getString("endpoint"))
        assertEquals("session-a", calls.first { it.optString("op") == "request" }.getString("session"))
    }
    @Test fun failedLoginClosesHandleAndForbiddenMeansIncorrectCredentials() = runBlocking {
        var closed = false
        val native: suspend (JSONObject) -> Any? = {
            when (it.getString("op")) {
                "open" -> "fresh"
                "login" -> JSONObject().put("status", 403).put("body", "")
                "close_session" -> { closed = true; null }
                else -> null
            }
        }
        val failure = runCatching { NasSession.login(ServerProfile(name = "NAS", address = "http://nas.example.test"), "viewer", "test-fixture", native) }.exceptionOrNull()
        assertEquals("账号或密码不正确", failure?.message)
        assertTrue(closed)
    }
    @Test fun renewedIdentityCannotSilentlyChangeAccount() = runBlocking {
        val profile = ServerProfile(name = "NAS", address = "https://nas.example.test")
        val session = NasSession.restore(profile, token(7), 7) { if (it.getString("op") == "open") "handle" else token(8) }
        assertTrue(runCatching { session.token() }.isFailure)
        assertTrue(runCatching { NasSession.restore(profile, token(7), 8) { error("must not open") } }.isFailure)
        assertTrue(runCatching { NasSession.login(profile.copy(backend = BackendKind.WINDOWS), "viewer", "test-fixture") { error("must not open") } }.isFailure)
    }
    @Test fun malformedPrincipalIsNotCoercedIntoAnotherAccount() {
        for (id in listOf("7", 7.5, -1)) {
            val payload = JSONObject().put("user", JSONObject().put("id", id).put("username", "viewer"))
            val encoded = Base64.encodeToString(payload.toString().toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            assertTrue(runCatching { NasSession.parseIdentity("header.$encoded.signature") }.isFailure)
        }
    }
}
