package io.github.kkwans.nasfilebrowser.data

import android.util.Base64
import io.github.kkwans.nasfilebrowser.core.NativeTransport
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray
import kotlinx.coroutines.flow.Flow

data class AccountIdentity(val id: Long, val username: String, val hostname: String)
class ServiceException(val status: Int, message: String) : Exception(message)

/** One immutable server/account context. Never reuse a handle for another login. */
class NasSession private constructor(val profile: ServerProfile, val id: String, val identity: AccountIdentity,
    private val native: suspend (JSONObject) -> Any?) {
    suspend fun token(): String {
        val token = native(JSONObject().put("op", "token").put("session", id)) as String
        check(parseIdentity(token).id == identity.id) { "服务器账号已变化，请重新登录" }
        return token
    }
    private suspend fun response(method: String, endpoint: String, body: JSONObject? = null): String {
        val command = JSONObject().put("op", "request").put("session", id).put("method", method).put("endpoint", endpoint)
        body?.let { command.put("body", it) }
        val result = native(command) as JSONObject
        when (result.getInt("status")) {
            200 -> { token(); return result.getString("body") }
            401 -> throw ServiceException(401, "登录已过期，请重新登录")
            403 -> throw ServiceException(403, "当前账号没有访问权限")
            404 -> throw ServiceException(404, "文件、目录或服务功能不存在")
            else -> throw ServiceException(result.getInt("status"), "服务器暂时无法完成请求，请重试")
        }
    }
    suspend fun request(method: String, endpoint: String, body: JSONObject? = null) = JSONObject(response(method, endpoint, body))
    suspend fun array(endpoint: String) = JSONArray(response("GET", endpoint))
    fun search(path: String, wirePath: String, query: String, scope: SearchScope): Flow<SearchUpdate> =
        searchFlow(path, wirePath, query, scope, identity = { token(); Unit }) { command -> native(command.put("session", id)) }
    suspend fun lease(path: String, wirePath: String, cacheKey: String = ""): String {
        token()
        return native(JSONObject().put("op", "lease").put("cacheKey", cacheKey).put("session", id).put("path", path).put("wirePath", wirePath)) as String
    }
    suspend fun preview(path: String, wirePath: String, contain: Boolean = false): PreviewLease {
        token()
        val url = native(JSONObject().put("op", "preview").put("scope", if (contain) "contain" else "").put("session", id).put("path", path).put("wirePath", wirePath)) as String
        return PreviewLease(url, id) { native(JSONObject().put("op", "revoke").put("url", url)); Unit }
    }
    suspend fun image(path: String, wirePath: String, quality: ImageQuality): PreviewLease {
        token()
        val url = if (quality == ImageQuality.ORIGINAL || quality == ImageQuality.HIGH) lease(path, wirePath)
        else {
            val wire = wirePath.ifEmpty { path.split('/').joinToString("/") { android.net.Uri.encode(it) } }
            val endpoint = if (quality == ImageQuality.MEDIUM) "/api/preview/big$wire" else "/api/preview/thumb$wire?fit=contain"
            native(JSONObject().put("op", "asset").put("session", id).put("endpoint", endpoint)) as String
        }
        return PreviewLease(url, id) { native(JSONObject().put("op", "revoke").put("url", url)); Unit }
    }
    suspend fun close() { native(JSONObject().put("op", "close_session").put("session", id)) }

    companion object {
        fun parseIdentity(token: String): AccountIdentity {
            try {
                val parts = token.trim().split('.')
                require(parts.size == 3 && parts[1].length <= 262_144)
                val payload = JSONObject(Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_WRAP).toString(Charsets.UTF_8))
                val user = payload.getJSONObject("user")
                val rawId = user.get("id")
                require(rawId is Number)
                val id = rawId.toString().toLongOrNull() ?: error("invalid id")
                val name = user.get("username") as? String ?: error("invalid username")
                require(id >= 0 && name.isNotBlank())
                return AccountIdentity(id, name, payload.optJSONObject("instance")?.optString("hostname").orEmpty())
            } catch (_: Exception) { error("服务器返回了不支持的账号格式") }
        }
        suspend fun login(profile: ServerProfile, username: String, password: String,
            native: suspend (JSONObject) -> Any? = NativeTransport::call): NasSession {
            require(profile.backend == BackendKind.NAS) { "Windows 服务适配尚未完成，暂不支持连接" }
            val id = native(JSONObject().put("op", "open").put("baseUrl", profile.address).put("network", profile.network.name.lowercase())) as String
            try {
                val result = native(JSONObject().put("op", "login").put("session", id).put("username", username).put("password", password)) as JSONObject
                when (result.getInt("status")) {
                    200 -> return NasSession(profile, id, parseIdentity(result.getString("body")), native)
                    401, 403 -> error("账号或密码不正确")
                    else -> error("登录失败，请检查服务器地址和账号")
                }
            } catch (error: Exception) {
                runCatching { withContext(NonCancellable) { native(JSONObject().put("op", "close_session").put("session", id)) } }.onFailure { error.addSuppressed(it) }
                throw error
            }
        }
        suspend fun restore(profile: ServerProfile, token: String, expectedUser: Long,
            native: suspend (JSONObject) -> Any? = NativeTransport::call): NasSession {
            require(profile.backend == BackendKind.NAS) { "Windows 服务适配尚未完成，暂不支持连接" }
            val identity = parseIdentity(token)
            check(identity.id == expectedUser) { "保存的账号不匹配，请重新登录" }
            val id = native(JSONObject().put("op", "open").put("baseUrl", profile.address).put("network", profile.network.name.lowercase()).put("token", token)) as String
            return NasSession(profile, id, identity, native)
        }
    }
}
