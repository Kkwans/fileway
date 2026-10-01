package io.github.kkwans.nasfilebrowser.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.kkwans.nasfilebrowser.core.NativeTransport
import io.github.kkwans.nasfilebrowser.core.EmbeddedNetwork
import io.github.kkwans.nasfilebrowser.core.NetworkState
import io.github.kkwans.nasfilebrowser.player.NativePlayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.net.URLEncoder

data class ResourceRef(val path: String, val wirePath: String, val name: String, val directory: Boolean, val type: String, val size: Long)
data class ClientState(
    val connected: Boolean = false, val busy: Boolean = false, val stage: String = "",
    val serverLabel: String = "", val path: String = "/", val wirePath: String = "/", val files: List<ResourceRef> = emptyList(),
    val error: String? = null, val selected: ResourceRef? = null,
)

class ClientModel(application: Application) : AndroidViewModel(application) {
    private val mutable = MutableStateFlow(ClientState())
    val state = mutable.asStateFlow()
    val player = NativePlayer(application)
    private val embeddedNetwork = EmbeddedNetwork(application)
    private val networkMutable = MutableStateFlow(NetworkState())
    val networkState = networkMutable.asStateFlow()
    private var networkJob: Job? = null
    private var networkPollJob: Job? = null
    private var networkActivated = false
    private var foreground = false
    private var session = ""
    private var lease = ""
    private var generation = 0
    private var operation: Job? = null
    private val cleanup = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val navigation = ArrayDeque<Pair<String, String>>()

    fun connect(url: String, username: String, password: String, network: String = "direct") {
        operation?.cancel(); generation++
        closeSession(); navigation.clear()
        val expected = generation
        mutable.value = ClientState(busy = true, stage = "正在登录服务器")
        operation = viewModelScope.launch {
            var opened = ""
            try {
                if (network == "tailnet") {
                    val status = embeddedNetwork.status(); networkMutable.value = status
                    if (!status.connected) throw IllegalStateException("请先连接并登录 Tailscale")
                }
                opened = NativeTransport.call(JSONObject().put("op", "open").put("baseUrl", url).put("network", network)) as String
                val response = NativeTransport.call(JSONObject().put("op", "login").put("session", opened).put("username", username).put("password", password)) as JSONObject
                if (response.getInt("status") != 200) throw IllegalStateException(if (response.getInt("status") == 401) "账号或密码不正确" else "登录失败，请检查服务器和账号")
                if (generation != expected) return@launch
                session = opened
                mutable.value = ClientState(connected = true, serverLabel = android.net.Uri.parse(url).host.orEmpty(), busy = true, stage = "正在读取目录")
                loadDirectory("/", "/", expected)
            } catch (error: Exception) {
                if (error !is CancellationException && generation == expected) mutable.value = ClientState(error = error.message ?: "无法连接服务器")
            } finally {
                if (opened.isNotEmpty() && opened != session) cleanup.launch { NativeTransport.call(JSONObject().put("op", "close_session").put("session", opened)) }
            }
        }
    }

    private suspend fun request(method: String, endpoint: String, body: JSONObject? = null): JSONObject {
        val cmd = JSONObject().put("op", "request").put("session", session).put("method", method).put("endpoint", endpoint)
        if (body != null) cmd.put("body", body)
        val response = NativeTransport.call(cmd) as JSONObject
        when (response.getInt("status")) {
            200 -> return JSONObject(response.getString("body"))
            401 -> throw IllegalStateException("登录已过期，请重新连接")
            403 -> throw IllegalStateException("当前账号没有访问权限")
            404 -> throw IllegalStateException("文件或目录不存在")
            else -> throw IllegalStateException("服务器暂时无法完成请求")
        }
    }

    private suspend fun loadDirectory(path: String, wire: String, expected: Int) {
        val encoded = wire.ifEmpty { path.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") } }
        val data = request("GET", "/api/resources$encoded")
        val items = data.optJSONArray("items")
        val files = if (items == null) emptyList() else (0 until items.length()).map { index ->
            val item = items.getJSONObject(index)
            ResourceRef(item.optString("path"), item.optString("wirePath"), item.optString("name"), item.optBoolean("isDir"), item.optString("type"), item.optLong("size"))
        }.sortedWith(compareByDescending<ResourceRef> { it.directory }.thenBy { it.name.lowercase() })
        if (generation == expected) mutable.value = mutable.value.copy(busy = false, stage = "", path = path, wirePath = wire, files = files, error = null)
    }

    fun open(file: ResourceRef) {
        if (file.directory) {
            navigation.addLast(mutable.value.path to mutable.value.wirePath)
            browse(file.path, file.wirePath)
        } else if (file.type == "video" || file.name.substringAfterLast('.').lowercase() in setOf("mkv", "mp4", "webm", "avi", "mov", "m2ts", "ts")) {
            operation?.cancel(); val expected = generation
            mutable.value = mutable.value.copy(busy = true, stage = "正在打开视频", error = null)
            operation = viewModelScope.launch {
                try {
                    val url = NativeTransport.call(JSONObject().put("op", "lease").put("session", session).put("path", file.path).put("wirePath", file.wirePath)) as String
                    if (generation != expected) { cleanup.launch { NativeTransport.call(JSONObject().put("op", "revoke").put("url", url)) }; return@launch }
                    revokeLease(); lease = url
                    mutable.value = mutable.value.copy(selected = file, busy = false, stage = "")
                    player.open(url)
                } catch (error: Exception) {
                    if (error !is CancellationException && generation == expected) mutable.value = mutable.value.copy(busy = false, error = "无法打开视频，请重试")
                }
            }
        } else mutable.value = mutable.value.copy(error = "当前版本先支持视频播放")
    }
    private fun browse(path: String, wire: String) {
        operation?.cancel(); val expected = generation
        mutable.value = mutable.value.copy(busy = true, stage = "正在读取目录", error = null)
        operation = viewModelScope.launch {
            try { loadDirectory(path, wire, expected) } catch (error: Exception) {
                if (error !is CancellationException && generation == expected) mutable.value = mutable.value.copy(busy = false, error = error.message ?: "目录读取失败")
            }
        }
    }
    fun retry() { browse(mutable.value.path, mutable.value.wirePath) }
    fun cancel() { operation?.cancel(); mutable.value = mutable.value.copy(busy = false, stage = "") }
    fun back(): Boolean {
        if (mutable.value.selected != null) { leavePlayer(); return true }
        if (navigation.isNotEmpty()) { val previous = navigation.removeLast(); browse(previous.first, previous.second); return true }
        return false
    }
    fun leavePlayer() { player.stop(); revokeLease(); mutable.value = mutable.value.copy(selected = null) }
    private fun revokeLease() { val old = lease; lease = ""; if (old.isNotEmpty()) cleanup.launch { NativeTransport.call(JSONObject().put("op", "revoke").put("url", old)) } }
    private fun closeSession() {
        player.stop(); revokeLease(); val old = session; session = ""
        if (old.isNotEmpty()) cleanup.launch { NativeTransport.call(JSONObject().put("op", "close_session").put("session", old)) }
    }
    fun disconnect() { operation?.cancel(); generation++; closeSession(); navigation.clear(); mutable.value = ClientState() }
    fun connectNetwork() {
        networkActivated = true
        networkJob?.cancel()
        networkPollJob?.cancel()
        networkJob = viewModelScope.launch {
            try {
                networkMutable.value = NetworkState(state = "Starting")
                networkMutable.value = embeddedNetwork.start()
                observeNetwork()
            } catch (error: Exception) {
                if (error !is CancellationException) networkMutable.value = networkMutable.value.copy(state = "Error", error = "内嵌网络连接失败，请重试")
            }
        }
    }
    private fun observeNetwork() {
        networkPollJob?.cancel()
        if (!foreground) return
        networkPollJob = viewModelScope.launch {
            try {
                networkMutable.value = embeddedNetwork.status()
                while (foreground) { delay(if (networkMutable.value.connected) 5000 else 1500); networkMutable.value = embeddedNetwork.status() }
            }
            catch (error: Exception) { if (error !is CancellationException) networkMutable.value = networkMutable.value.copy(state = "Error", error = "无法确认内嵌网络状态，请重试") }
        }
    }
    fun foreground(active: Boolean) {
        foreground = active
        if (!active) { networkPollJob?.cancel(); return }
        if (networkActivated && networkJob?.isActive != true) observeNetwork()
    }
    fun stopNetwork(logout: Boolean = false) {
        networkJob?.cancel(); networkPollJob?.cancel(); disconnect()
        networkJob = viewModelScope.launch { try { if (logout) embeddedNetwork.logout() else embeddedNetwork.stop(); networkMutable.value = embeddedNetwork.status(); observeNetwork() } catch (error: Exception) { if (error !is CancellationException) networkMutable.value = networkMutable.value.copy(state = "Error", error = "无法断开内嵌网络，请重试") } }
    }
    override fun onCleared() { operation?.cancel(); networkJob?.cancel(); networkPollJob?.cancel(); closeSession(); player.release(); super.onCleared() }
}
