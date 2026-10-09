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

internal class AdminUsersAuthority {
    val users = linkedMapOf(1L to user(1, "operator", true), 2L to user(2, "member", false))
    var authMethod = "json"
    var allowed = true
    var writes = 0
    var lastBody: JSONObject? = null
    var lastMethod = ""
    var commitThenFail = false
    var hold: CompletableDeferred<Unit>? = null
    val entered = CompletableDeferred<Unit>(); val finished = CompletableDeferred<Unit>()
    suspend fun context(owner: String): SessionContext {
        val payload = Base64.encodeToString("{\"user\":{\"id\":1,\"username\":\"operator\"}}".toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val token = "e30.$payload.fixture"
        val profile = ServerProfile(id = "owned-$owner", name = "Owned admin fixture", address = "https://admin.invalid")
        val api = NasSession.restore(profile, token, 1) { command -> when (command.getString("op")) {
            "open" -> "owned-admin-$owner"
            "token" -> token
            "request" -> {
                val method = command.getString("method"); val endpoint = command.getString("endpoint")
                var status = 200
                val body = if (method == "GET") {
                    when {
                        endpoint == "/api/client-capabilities" -> JSONObject().put("authMethod", authMethod).put("enableExec", true).put("minimumPasswordLength", 6).toString()
                        endpoint == "/api/settings" -> JSONObject().put("createUserDir", true).put("defaults", user(0, "", false).apply { put("scope", "/default") }).toString()
                        endpoint == "/api/users" -> if (allowed) JSONArray(users.values.toList()).toString() else { status = 403; "forbidden" }
                        endpoint.startsWith("/api/users/") -> users[endpoint.substringAfterLast('/').toLong()]?.toString() ?: run { status = 404; "missing" }
                        else -> error("Unexpected admin read")
                    }
                } else {
                    writes++; lastMethod = method; lastBody = JSONObject(command.getJSONObject("body").toString())
                    entered.complete(Unit)
                    hold?.let { withContext(NonCancellable) { it.await(); finished.complete(Unit) } }
                    if (!allowed) { status = 403; "forbidden" }
                    else if (authMethod == "json" && lastBody!!.optString("current_password") != "owned-operator-password") { status = 400; "bad operator credential" }
                    else {
                        when (method) {
                            "POST" -> {
                                val data = JSONObject(lastBody!!.getJSONObject("data").toString()).put("id", 3).put("password", "")
                                if (data.getString("scope").isEmpty()) data.put("scope", "/users/" + data.getString("username"))
                                users[3] = data; status = 201
                            }
                            "PUT" -> users[endpoint.substringAfterLast('/').toLong()] = JSONObject(lastBody!!.getJSONObject("data").toString()).put("password", "")
                            "DELETE" -> users.remove(endpoint.substringAfterLast('/').toLong())
                            else -> error("Unexpected admin write")
                        }
                        if (commitThenFail) error("Owned response loss after commit")
                        if (status == 201) "201 Created\n" else "200 OK\n"
                    }
                }
                JSONObject().put("status", status).put("body", body)
            }
            else -> error("Unexpected admin operation")
        } }
        return SessionContext(profile, AccountRecord(owner, profile.id, 0, 1, "operator", "fixture-only", 0), api, 1, owner)
    }
    companion object {
        fun user(id: Long, name: String, admin: Boolean) = JSONObject().put("id", id).put("username", name).put("scope", "/owned/$name")
            .put("password", "").put("lockPassword", false).put("locale", "zh-cn").put("viewMode", "mosaic")
            .put("futureField", "preserved").put("commands", JSONArray(listOf("owned-command"))).put("rules", JSONArray())
            .put("perm", ManagedPermissions(admin = admin, share = true, download = true).json().put("futurePermission", true))
    }
}

@RunWith(AndroidJUnit4::class)
class AdminUsersControllerTest {
    private suspend fun main(action: () -> Unit) = withContext(Dispatchers.Main) { action() }
    private suspend fun ready(controller: AdminUsersController) = withTimeout(5000) { controller.state.first { !it.loading && (it.authorized || it.error != null) } }
    private suspend fun editor(controller: AdminUsersController) = withTimeout(5000) { controller.state.first { !it.loading && it.draft != null } }
    private suspend fun settled(controller: AdminUsersController) = withTimeout(5000) { controller.state.first { !it.saving && (it.notice != null || it.error != null) } }

    @Test fun legacyMissingRegexpIsNeverInventedAsAnEmptyMatchAllExpression() {
        val missing = JSONObject().put("allow", true).put("regex", true).put("path", "/old").put("futureRule", "kept")
        val absent = ManagedRule.from(missing).copy(allow = false).json()
        assertFalse(absent.has("regexp")); assertEquals("kept", absent.getString("futureRule"))
        val oldNull = ManagedRule.from(JSONObject(missing.toString()).put("regexp", JSONObject.NULL))
        val preserved = oldNull.copy(path = "/changed").json()
        assertTrue(preserved.has("regexp")); assertTrue(preserved.isNull("regexp"))
        assertEquals("^/docs/", oldNull.copy(expression = "^/docs/").json().getJSONObject("regexp").getString("raw"))
        assertEquals("", ManagedRule(regex = true).json().getJSONObject("regexp").getString("raw"))
    }

    @Test fun editKeepsUnknownFieldsShareAndUsesOperatorPasswordWithAllContract(): Unit = runBlocking {
        val authority = AdminUsersAuthority(); val context = authority.context("edit")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main); val controller = AdminUsersController(scope, { it === context })
        try {
            main { controller.bind(context); controller.refresh() }; ready(controller)
            assertEquals(listOf(1L, 2L), controller.state.value.users.map { it.id })
            main { controller.select(2) }; editor(controller)
            main { controller.edit { it.copy(username = "member-renamed", scope = "/changed", lockPassword = true,
                rules = listOf(ManagedRule(regex = true, expression = "(?P<name>docs)/[[:alpha:]]+"))) }; controller.requestSave(); controller.confirm() }
            assertEquals(0, authority.writes)
            main { controller.currentPassword("owned-operator-password"); controller.confirm() }; settled(controller)
            val body = authority.lastBody!!; val data = body.getJSONObject("data")
            assertEquals("all", body.getJSONArray("which").getString(0)); assertEquals("owned-operator-password", body.getString("current_password"))
            assertEquals(2, data.getInt("id")); assertEquals("", data.getString("password"))
            assertEquals("preserved", data.getString("futureField")); assertTrue(data.getJSONObject("perm").getBoolean("futurePermission"))
            assertTrue(data.getJSONObject("perm").getBoolean("share")); assertEquals("member-renamed", authority.users[2]!!.getString("username"))
            assertFalse(ManagedPermissions(share = false).administrator(true).share)
        } finally { scope.cancel() }
    }

    @Test fun createUsesServerDefaultsAndDeleteRequiresSeparateConfirmation(): Unit = runBlocking {
        val authority = AdminUsersAuthority(); authority.authMethod = "hook"
        val context = authority.context("create-delete"); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val controller = AdminUsersController(scope, { it === context })
        try {
            main { controller.bind(context); controller.refresh() }; ready(controller)
            main { controller.create() }; editor(controller)
            assertTrue(controller.state.value.draft!!.autoHome)
            main { controller.edit { it.copy(username = "new-member", password = "owned-new-password") }; controller.requestSave(); controller.confirm() }; settled(controller)
            assertEquals(0, authority.lastBody!!.getJSONArray("which").length()); assertFalse(authority.lastBody!!.has("current_password"))
            assertEquals("", authority.lastBody!!.getJSONObject("data").getString("scope"))
            assertEquals("mosaic", authority.users[3]!!.getString("viewMode")); assertEquals(3, controller.state.value.users.size)
            main { controller.select(3) }; editor(controller)
            main { controller.requestDelete() }; assertEquals(1, authority.writes)
            main { controller.confirm() }; settled(controller)
            assertEquals("DELETE", authority.lastMethod); assertFalse(authority.users.containsKey(3)); assertEquals(2, controller.state.value.users.size)
        } finally { scope.cancel() }
    }

    @Test fun unknownMutationKeepsDraftAndCannotBeRepeatedAfterRefreshingList(): Unit = runBlocking {
        val authority = AdminUsersAuthority(); authority.commitThenFail = true
        val context = authority.context("unknown"); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val controller = AdminUsersController(scope, { it === context })
        try {
            main { controller.bind(context); controller.refresh() }; ready(controller)
            main { controller.select(2) }; editor(controller)
            main { controller.edit { it.copy(password = "owned-new-password") }; controller.requestSave(); controller.currentPassword("owned-operator-password"); controller.confirm() }; settled(controller)
            assertTrue(controller.state.value.unknown); assertEquals("owned-new-password", controller.state.value.draft!!.password)
            assertFalse(controller.state.value.toString().contains("owned-new-password"))
            main { controller.confirm(); controller.refresh() }; ready(controller)
            assertTrue(controller.state.value.unknown); assertEquals(1, authority.writes)
        } finally { scope.cancel() }
    }

    @Test fun currentAccountChangeAndDeletionNotifyOnlyTheBoundOwner(): Unit = runBlocking {
        val authority = AdminUsersAuthority(); val context = authority.context("self")
        authority.users[2]!!.getJSONObject("perm").put("admin", true)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main); var changes = 0; var deletes = 0
        val controller = AdminUsersController(scope, { it === context }, { bound, user, password ->
            assertSame(context, bound); assertEquals(1L, user.id); assertEquals("owned-new-password", password); changes++
        }, { bound -> assertSame(context, bound); deletes++ })
        try {
            main { controller.bind(context); controller.refresh() }; ready(controller)
            main { controller.select(1) }; editor(controller)
            main { controller.edit { it.copy(password = "owned-new-password") }; controller.requestSave(); controller.currentPassword("owned-operator-password"); controller.confirm() }; settled(controller)
            assertEquals(1, changes); assertFalse(controller.state.value.authorized)
            main { controller.refresh() }; ready(controller)
            main { controller.select(1) }; editor(controller)
            main { controller.requestDelete(); controller.currentPassword("owned-operator-password"); controller.confirm() }; settled(controller)
            assertEquals(1, deletes); assertNull(controller.state.value.draft); assertFalse(controller.state.value.authorized)
        } finally { scope.cancel() }
    }

    @Test fun deniedAdminReadCannotCreateOrModifyAndLateWriteCannotReachNextAccount(): Unit = runBlocking {
        val denied = AdminUsersAuthority(); denied.allowed = false; val deniedContext = denied.context("denied")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        var current = deniedContext; var callbacks = 0
        val controller = AdminUsersController(scope, { it === current }, { _, _, _ -> callbacks++ })
        val old = AdminUsersAuthority(); old.hold = CompletableDeferred(); val oldContext = old.context("old")
        val next = AdminUsersAuthority().context("next")
        try {
            main { controller.bind(deniedContext); controller.refresh() }; ready(controller)
            main { controller.create(); controller.requestSave(); controller.confirm() }
            assertFalse(controller.state.value.authorized); assertEquals(0, denied.writes)
            main { current = oldContext; controller.bind(oldContext); controller.refresh() }; ready(controller)
            main { controller.select(1) }; editor(controller)
            main { controller.requestSave(); controller.currentPassword("owned-operator-password"); controller.confirm() }
            withTimeout(5000) { old.entered.await() }
            main { current = next; controller.bind(next); controller.refresh() }; ready(controller)
            old.hold!!.complete(Unit); withTimeout(5000) { old.finished.await() }; main { }
            assertEquals(next.owner, controller.state.value.scope); assertEquals(0, callbacks); assertNull(controller.state.value.draft)
        } finally { old.hold?.complete(Unit); scope.cancel() }
    }
}
