package io.github.kkwans.nasfilebrowser.data

import org.json.JSONArray
import org.json.JSONObject

data class ManagedPermissions(val admin: Boolean = false, val execute: Boolean = false, val create: Boolean = false,
    val rename: Boolean = false, val modify: Boolean = false, val delete: Boolean = false, val share: Boolean = false,
    val download: Boolean = false) {
    fun administrator(value: Boolean) = if (value) copy(admin = true, execute = true, create = true, rename = true,
        modify = true, delete = true, download = true) else copy(admin = false)
    fun json(original: JSONObject = JSONObject()): JSONObject = JSONObject(original.toString()).apply {
        put("admin", admin); put("execute", execute); put("create", create); put("rename", rename)
        put("modify", modify); put("delete", delete); put("share", share); put("download", download)
    }
    companion object {
        fun from(value: JSONObject): ManagedPermissions {
            fun flag(key: String) = value.get(key) as? Boolean ?: error("服务器用户权限格式无效")
            return ManagedPermissions(flag("admin"), flag("execute"), flag("create"), flag("rename"), flag("modify"), flag("delete"), flag("share"), flag("download"))
        }
    }
}
data class ManagedRule(val allow: Boolean = true, val regex: Boolean = false, val path: String = "", val expression: String = "",
    private val original: String = "{}") {
    fun json(): JSONObject {
        val value = JSONObject(original)
        // An old missing/null regexp is not an empty (match-all) expression.
        // Keep its provenance until the user actually supplies an expression;
        // server validation then rejects invalid legacy regex rows safely.
        val preserveMissing = original != "{}" && value.optJSONObject("regexp") == null && expression.isEmpty()
        return value.apply {
            put("allow", allow); put("regex", regex); put("path", path)
            if (!preserveMissing) put("regexp", JSONObject(optJSONObject("regexp")?.toString() ?: "{}").put("raw", expression))
        }
    }
    companion object {
        fun from(value: JSONObject) = ManagedRule(value.get("allow") as? Boolean ?: error("规则状态无效"),
            value.get("regex") as? Boolean ?: error("规则类型无效"), value.optString("path"),
            value.optJSONObject("regexp")?.optString("raw").orEmpty(), value.toString())
    }
}
data class ManagedUserDraft(val id: Long, val username: String, val password: String = "", val scope: String = "/",
    val permissions: ManagedPermissions = ManagedPermissions(), val lockPassword: Boolean = false,
    val commands: List<String> = emptyList(), val rules: List<ManagedRule> = emptyList(),
    val creating: Boolean = false, val homeCreationAvailable: Boolean = false, val autoHome: Boolean = false,
    private val original: String = "{}") {
    fun data(minimumPasswordLength: Int): JSONObject {
        require(username.isNotBlank() && !username.contains('\u0000') && !scope.contains('\u0000')) { "请填写用户名和有效的用户目录" }
        val bytes = password.toByteArray(Charsets.UTF_8).size
        require((!creating && password.isEmpty()) || bytes in minimumPasswordLength..72) { "新密码不符合服务器长度要求（最多 72 字节）" }
        require(!permissions.share || permissions.download) { "已有分享授权需要保留下载权限" }
        require(commands.none { it.contains('\u0000') }) { "命令白名单不能包含空字节" }
        require(rules.none { it.path.contains('\u0000') || it.expression.contains('\u0000') }) { "路径规则不能包含空字节" }
        return JSONObject(original).apply {
            put("id", id); put("username", username); put("password", password)
            put("scope", if (creating && autoHome && homeCreationAvailable) "" else scope)
            put("lockPassword", lockPassword)
            put("perm", permissions.json(optJSONObject("perm") ?: JSONObject()))
            put("commands", JSONArray(commands)); put("rules", JSONArray().apply { rules.forEach { put(it.json()) } })
        }
    }
    override fun toString() = "ManagedUserDraft(id=$id, creating=$creating, password=redacted)"
}

class ManagedUser private constructor(val id: Long, val username: String, val scope: String,
    val permissions: ManagedPermissions, private val original: String) {
    fun draft(): ManagedUserDraft {
        val value = JSONObject(original)
        val commands = value.optJSONArray("commands") ?: JSONArray()
        val rules = value.optJSONArray("rules") ?: JSONArray()
        return ManagedUserDraft(id, username, scope = scope, permissions = permissions,
            lockPassword = value.get("lockPassword") as? Boolean ?: error("密码锁定状态无效"),
            commands = (0 until commands.length()).map { commands.getString(it) },
            rules = (0 until rules.length()).map { ManagedRule.from(rules.getJSONObject(it)) }, original = original)
    }
    override fun toString() = "ManagedUser(id=$id)"
    companion object {
        fun from(value: JSONObject): ManagedUser {
            val id = (value.get("id") as? Number)?.toString()?.toLongOrNull()
            val name = value.get("username") as? String
            require(id != null && id > 0 && !name.isNullOrBlank()) { "服务器用户标识无效" }
            val scope = value.get("scope") as? String ?: error("服务器用户目录无效")
            val safe = JSONObject(value.toString()).apply { remove("password") }
            return ManagedUser(id, name, scope, ManagedPermissions.from(value.getJSONObject("perm")), safe.toString())
        }
        fun newDraft(settings: JSONObject): ManagedUserDraft {
            val defaults = JSONObject(settings.getJSONObject("defaults").toString()).apply {
                put("id", 0); put("username", ""); put("password", ""); put("lockPassword", false); put("rules", JSONArray())
            }
            val permission = ManagedPermissions.from(defaults.getJSONObject("perm"))
            val commands = defaults.optJSONArray("commands") ?: JSONArray()
            val home = settings.get("createUserDir") as? Boolean ?: error("服务器用户目录策略无效")
            return ManagedUserDraft(0, "", scope = defaults.optString("scope"), permissions = permission,
                commands = (0 until commands.length()).map { commands.getString(it) }, creating = true,
                homeCreationAvailable = home, autoHome = home, original = defaults.toString())
        }
    }
}
