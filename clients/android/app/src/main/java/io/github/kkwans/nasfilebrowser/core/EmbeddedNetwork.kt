package io.github.kkwans.nasfilebrowser.core

import android.content.Context
import android.util.Base64
import io.github.kkwans.nasfilebrowser.data.CredentialVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class NetworkState(val state: String = "Unconfigured", val authUrl: String = "", val ips: List<String> = emptyList(), val acceptSubnets: Boolean = false, val health: List<String> = emptyList(), val error: String? = null, val authenticated: Boolean = false) {
    val connected get() = state == "Running"
    val canLogout get() = authenticated && state != "Starting"
    val label get() = when (state) {
        "Running" -> "已连接"
        "NeedsLogin" -> "等待登录"
        "NeedsMachineAuth" -> "等待设备批准"
        "Starting" -> "正在连接"
        "Error" -> "连接失败"
        "Stopped", "Configured", "Unconfigured", "Closed" -> "未连接"
        else -> "正在准备连接"
    }
}

class EmbeddedNetwork(context: Context) {
    private val application = context.applicationContext
    private val mutex = Mutex()
    private suspend fun ensureConfigured() = mutex.withLock {
        AndroidNetworkPlatform.refresh(application)
        val status = NativeTransport.call(JSONObject().put("op", "network_status")) as JSONObject
        if (status.optString("state") != "Unconfigured") return@withLock
        val keyAndName = withContext(Dispatchers.IO) {
            val vault = CredentialVault(application)
            val identity = vault.read("installation-id")?.toString(Charsets.UTF_8) ?: UUID.randomUUID().toString().also { vault.write("installation-id", it.toByteArray()) }
            val key = vault.nodeDataKey()
            val encoded = Base64.encodeToString(key, Base64.NO_WRAP); key.fill(0)
            encoded to "nfb-android-${identity.take(8)}"
        }
        NativeTransport.call(JSONObject().put("op", "network_configure")
            .put("stateDir", File(application.noBackupFilesDir, "tailnet").absolutePath)
            .put("hostname", keyAndName.second).put("storageKey", keyAndName.first))
    }
    suspend fun status(): NetworkState { ensureConfigured(); return parse(NativeTransport.call(JSONObject().put("op", "network_status")) as JSONObject) }
    suspend fun start(): NetworkState { ensureConfigured(); return parse(NativeTransport.call(JSONObject().put("op", "network_start")) as JSONObject) }
    suspend fun stop() { NativeTransport.call(JSONObject().put("op", "network_stop")) }
    suspend fun logout() { NativeTransport.call(JSONObject().put("op", "network_logout")) }
    private fun parse(json: JSONObject): NetworkState {
        fun strings(key: String): List<String> { val values = json.optJSONArray(key) ?: return emptyList(); return (0 until values.length()).map { values.optString(it) } }
        return NetworkState(json.optString("state"), json.optString("authUrl"), strings("ips"), json.optBoolean("acceptSubnets"), strings("health"), authenticated = json.optBoolean("authenticated"))
    }
}
