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
    private fun token(id: Long, issued: Long = 0): String {
        val payload = JSONObject().put("iat", issued).put("user", JSONObject().put("id", id).put("username", "viewer"))
        return "header." + Base64.encodeToString(payload.toString().toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING) + ".signature"
    }
    @Test fun renewedTokensArePersistedOnceAndAnotherIdentityCannotReachStorage() = runBlocking {
        val original = token(7, 10)
        val renewed = token(7, 20)
        var current = original
        val saved = mutableListOf<String>()
        val session = NasSession.restore(ServerProfile(name = "Owned login", address = "https://login.example.test"), original, 7) { command ->
            when (command.getString("op")) {
                "open" -> "owned-login"
                "token" -> current
                "request" -> { current = renewed; JSONObject().put("status", 200).put("body", "{}") }
                else -> null
            }
        }
        session.persistTokens { saved.add(it) }
        session.request("GET", "/api/resources/")
        session.token(); session.token()
        assertEquals(listOf(original, renewed), saved)
        current = token(8, 30)
        assertTrue(runCatching { session.token() }.isFailure)
        assertEquals(listOf(original, renewed), saved)
        session.close()
    }
    @Test fun previewCapabilityPreservesWirePathAndReleasesOnce() = runBlocking {
        var account = 7L
        var previewCalls = 0
        var revoked = 0
        val wire = "/%D6%D0/a%252F.mkv"
        val native: suspend (JSONObject) -> Any? = { request ->
            when (request.getString("op")) {
                "open" -> "preview-session"
                "login" -> JSONObject().put("status", 200).put("body", token(account))
                "token" -> token(account)
                "preview" -> {
                    previewCalls++
                    assertEquals("preview-session", request.getString("session"))
                    assertEquals(wire, request.getString("wirePath"))
                    "http://127.0.0.1:12345/stream/owned-preview"
                }
                "revoke" -> { revoked++; assertEquals("http://127.0.0.1:12345/stream/owned-preview", request.getString("url")); null }
                else -> null
            }
        }
        val session = NasSession.login(ServerProfile(name = "NAS", address = "https://nas.example.test"), "viewer", "fixture", native)
        val asset = session.preview("/display.mkv", wire)
        assertEquals("preview-session", asset.scope)
        asset.release(); asset.release()
        assertEquals(1, revoked)
        account = 8
        try { session.preview("/display.mkv", wire); fail("Changed account cannot allocate a preview") }
        catch (_: IllegalStateException) { }
        assertEquals(1, previewCalls)
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
