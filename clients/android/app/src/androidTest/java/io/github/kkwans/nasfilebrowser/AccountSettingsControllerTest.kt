package io.github.kkwans.nasfilebrowser

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.kkwans.nasfilebrowser.app.*
import io.github.kkwans.nasfilebrowser.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

internal class AccountSettingsAuthority {
    var profile = JSONObject().put("id", 1).put("username", "fixture").put("lockPassword", false)
        .put("singleClick", false).put("redirectAfterCopyMove", false).put("dateFormat", false).put("aceEditorTheme", "")
        .put("password", "").put("perm", JSONObject().put("admin", false).put("share", true))
        .put("commands", listOf("owned-command")).put("futureField", "preserved")
    var capabilities = JSONObject().put("authMethod", "json").put("enableExec", false).put("minimumPasswordLength", 6)
    var writes = 0
    var writeBody: JSONObject? = null
    var status = 200
    var commitThenFail = false
    var tokenFailure = false
    var hold: CompletableDeferred<Unit>? = null
    val entered = CompletableDeferred<Unit>()
    val finished = CompletableDeferred<Unit>()
    suspend fun context(owner: String): SessionContext {
        val payload = Base64.encodeToString("{\"user\":{\"id\":1,\"username\":\"fixture\"}}".toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val token = "e30.$payload.fixture"
        val server = ServerProfile(id = "fixture-$owner", name = "Owned account fixture", address = "https://account.invalid")
        val api = NasSession.restore(server, token, 1) { command ->
            when (command.getString("op")) {
                "open" -> "owned-account-$owner"
                "token" -> if (tokenFailure && writes > 0) error("Owned token persistence failure") else token
                "request" -> {
                    val endpoint = command.getString("endpoint")
                    if (command.getString("method") == "GET") {
                        JSONObject().put("status", 200).put("body", when (endpoint) {
                            "/api/client-capabilities" -> capabilities.toString()
                            "/api/users/1" -> profile.toString()
                            else -> error("Unexpected account read")
                        })
                    } else {
                        check(command.getString("method") == "PUT" && endpoint == "/api/users/1")
                        writes++; writeBody = JSONObject(command.getJSONObject("body").toString())
                        entered.complete(Unit)
                        hold?.let { withContext(NonCancellable) { it.await(); finished.complete(Unit) } }
                        if (status == 200) {
                            val data = command.getJSONObject("body").getJSONObject("data")
                            data.keys().asSequence().filter { it != "id" && it != "password" }.forEach { profile.put(it, data.get(it)) }
                        }
                        if (commitThenFail) error("Owned response loss after commit")
                        JSONObject().put("status", status).put("body", if (status == 200) "200 OK\n" else "owned rejection")
                    }
                }
                else -> error("Unexpected account operation")
            }
        }
        return SessionContext(server, AccountRecord(owner, server.id, 0, 1, "fixture", "fixture-only", 0), api, 1, owner)
    }
}

@RunWith(AndroidJUnit4::class)
class AccountSettingsControllerTest {
    private suspend fun main(action: () -> Unit) = withContext(Dispatchers.Main) { action() }
    private suspend fun loaded(controller: AccountSettingsController) = withTimeout(5000) { controller.state.first { !it.loading && it.profile != null } }
    private suspend fun settled(controller: AccountSettingsController) = withTimeout(5000) { controller.state.first { !it.saving && (it.notice != null || it.error != null) } }

    @Test fun preferenceWriteUsesOnlyChangedFieldsAndPreservesFullServerSnapshot(): Unit = runBlocking {
        val authority = AccountSettingsAuthority(); val context = authority.context("prefs")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val controller = AccountSettingsController(scope, { it === context })
        try {
            main { controller.bind(context); controller.refresh() }; loaded(controller)
            main { controller.editPreferences { it.copy(dateFormat = true) }; controller.savePreferences() }; settled(controller)
            val body = authority.writeBody!!
            assertEquals("user", body.getString("what")); assertEquals(listOf("dateFormat"), body.getJSONArray("which").let { (0 until it.length()).map(it::getString) })
            assertEquals(setOf("id", "dateFormat"), body.getJSONObject("data").keys().asSequence().toSet())
            assertFalse(body.has("current_password")); assertEquals(1L, body.getJSONObject("data").getLong("id"))
            val snapshot = controller.state.value.profile!!.snapshot()
            assertEquals("preserved", snapshot.getString("futureField")); assertTrue(snapshot.getJSONObject("perm").getBoolean("share"))
            assertFalse(snapshot.has("password")); assertFalse(controller.state.value.dirty)
            val preferences = controller.state.value.draft!!
            preferences.copy(prefixes = preferences.prefixes + AccountPrefixRule("😀")).validate()
            assertTrue(runCatching { preferences.copy(prefixes = preferences.prefixes + AccountPrefixRule("bad/path")).validate() }.isFailure)
            main { controller.editPreferences { it.copy(singleClick = true) } }
            authority.profile.put("dateFormat", false)
            main { controller.refresh() }; loaded(controller)
            val patch = controller.state.value.profile!!.preferencePatch(controller.state.value.draft!!)
            assertEquals(setOf("singleClick"), patch.keys().asSequence().toSet())
        } finally { scope.cancel() }
    }

    @Test fun passwordCurrentCredentialLockAndNonJsonPolicyFollowServerContract(): Unit = runBlocking {
        val authority = AccountSettingsAuthority(); val context = authority.context("password")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        var acknowledged = 0
        val controller = AccountSettingsController(scope, { it === context }, { _, password -> assertEquals("owned-new-password", password); acknowledged++ })
        try {
            main { controller.bind(context); controller.refresh() }; loaded(controller)
            main { controller.editPassword { it.copy(replacement = "owned-new-password", confirmation = "owned-new-password") }; controller.savePassword() }
            assertEquals(0, authority.writes)
            main { controller.editPassword { it.copy(current = "owned-current-password") }; controller.savePassword() }; settled(controller)
            assertEquals(1, acknowledged); assertEquals("owned-current-password", authority.writeBody!!.getString("current_password"))
            assertEquals("password", authority.writeBody!!.getJSONArray("which").getString(0))
            assertEquals(AccountPasswordDraft(), controller.state.value.password)
            authority.capabilities.put("authMethod", "hook")
            main { controller.refresh() }; loaded(controller)
            main { controller.editPassword { it.copy(replacement = "owned-new-password", confirmation = "owned-new-password") }; controller.savePassword() }; settled(controller)
            assertFalse(authority.writeBody!!.has("current_password")); assertEquals(2, acknowledged)
            authority.profile.put("lockPassword", true)
            main { controller.refresh() }; loaded(controller)
            assertFalse(controller.state.value.canChangePassword)
            main { controller.savePassword() }; assertEquals(2, authority.writes)
            authority.profile.put("lockPassword", false); authority.capabilities.put("authMethod", "noauth")
            main { controller.refresh() }; loaded(controller)
            assertFalse(controller.state.value.canChangePassword)
        } finally { scope.cancel() }
    }

    @Test fun unknownPasswordWriteRetainsDraftAndNeverRetriesOrUnlocksOnRead(): Unit = runBlocking {
        val authority = AccountSettingsAuthority(); authority.commitThenFail = true
        val context = authority.context("unknown"); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val controller = AccountSettingsController(scope, { it === context })
        try {
            main { controller.bind(context); controller.refresh() }; loaded(controller)
            val draft = AccountPasswordDraft("owned-current", "owned-new-password", "owned-new-password")
            main { controller.editPassword { draft }; controller.savePassword() }; settled(controller)
            assertTrue(controller.state.value.passwordUnknown); assertEquals(draft, controller.state.value.password)
            assertFalse(controller.state.value.password.toString().contains("owned-new"))
            main { controller.savePassword(); controller.refresh() }; loaded(controller)
            assertEquals(1, authority.writes); assertTrue(controller.state.value.passwordUnknown)
            assertNotNull(controller.state.value.error)
        } finally { scope.cancel() }
    }

    @Test fun acknowledgedPasswordSurvivesLaterTokenStorageFailure(): Unit = runBlocking {
        val authority = AccountSettingsAuthority(); val context = authority.context("token")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main); var acknowledged = 0
        val controller = AccountSettingsController(scope, { it === context }, { _, _ -> acknowledged++ })
        try {
            main { controller.bind(context); controller.refresh() }; loaded(controller)
            authority.tokenFailure = true
            main { controller.editPassword { AccountPasswordDraft("owned-current", "owned-new-password", "owned-new-password") }; controller.savePassword() }; settled(controller)
            assertEquals(1, acknowledged); assertFalse(controller.state.value.passwordUnknown)
            assertTrue(controller.state.value.notice!!.contains("密码已修改")); assertEquals(AccountPasswordDraft(), controller.state.value.password)
        } finally { scope.cancel() }
    }

    @Test fun unknownPreferenceWriteIsReadBackBeforeAnotherSubmission(): Unit = runBlocking {
        val authority = AccountSettingsAuthority(); authority.commitThenFail = true
        val context = authority.context("preference-unknown"); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val controller = AccountSettingsController(scope, { it === context })
        try {
            main { controller.bind(context); controller.refresh() }; loaded(controller)
            main { controller.editPreferences { it.copy(dateFormat = true) }; controller.savePreferences() }; settled(controller)
            assertTrue(controller.state.value.preferencesUnknown); assertTrue(controller.state.value.draft!!.dateFormat)
            main { controller.savePreferences() }; assertEquals(1, authority.writes)
            main { controller.refresh() }; loaded(controller)
            assertFalse(controller.state.value.preferencesUnknown); assertFalse(controller.state.value.dirty)
            assertTrue(controller.state.value.notice!!.contains("已核对")); assertEquals(1, authority.writes)
        } finally { scope.cancel() }
    }

    @Test fun explicitPasswordRejectionKeepsEditableDraftWithoutCallingCredentialCallback(): Unit = runBlocking {
        val authority = AccountSettingsAuthority(); authority.status = 400
        val context = authority.context("rejected"); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        var acknowledged = 0
        val controller = AccountSettingsController(scope, { it === context }, { _, _ -> acknowledged++ })
        try {
            main { controller.bind(context); controller.refresh() }; loaded(controller)
            val draft = AccountPasswordDraft("owned-wrong-current", "owned-new-password", "owned-new-password")
            main { controller.editPassword { draft }; controller.savePassword() }; settled(controller)
            assertFalse(controller.state.value.passwordUnknown); assertEquals(draft, controller.state.value.password)
            assertEquals(0, acknowledged); assertEquals(1, authority.writes)
        } finally { scope.cancel() }
    }

    @Test fun lateAcknowledgementCannotUpdateNextAccountOrItsCredentialVault(): Unit = runBlocking {
        val authority = AccountSettingsAuthority(); authority.hold = CompletableDeferred()
        val old = authority.context("old"); val next = AccountSettingsAuthority().context("next")
        var current = old; var acknowledged = 0
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val controller = AccountSettingsController(scope, { it === current }, { _, _ -> acknowledged++ })
        try {
            main { controller.bind(old); controller.refresh() }; loaded(controller)
            main { controller.editPassword { AccountPasswordDraft("owned-current", "owned-new-password", "owned-new-password") }; controller.savePassword() }
            withTimeout(5000) { authority.entered.await() }
            main { current = next; controller.bind(next); controller.refresh() }; loaded(controller)
            authority.hold!!.complete(Unit); withTimeout(5000) { authority.finished.await() }; main { }
            assertEquals(next.owner, controller.state.value.scope); assertEquals(0, acknowledged)
            assertEquals(AccountPasswordDraft(), controller.state.value.password); assertFalse(controller.state.value.passwordUnknown)
        } finally { authority.hold?.complete(Unit); scope.cancel() }
    }
}
