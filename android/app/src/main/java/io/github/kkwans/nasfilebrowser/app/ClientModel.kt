package io.github.kkwans.nasfilebrowser.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.kkwans.nasfilebrowser.core.NativeTransport
import io.github.kkwans.nasfilebrowser.core.EmbeddedNetwork
import io.github.kkwans.nasfilebrowser.core.NetworkState
import io.github.kkwans.nasfilebrowser.player.NativePlayer
import io.github.kkwans.nasfilebrowser.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.net.URLEncoder

data class ResourceRef(val path: String, val wirePath: String, val name: String, val directory: Boolean, val type: String, val size: Long)
data class ClientState(
    val connected: Boolean = false, val busy: Boolean = false, val stage: String = "",
    val serverLabel: String = "", val path: String = "/", val wirePath: String = "/", val files: List<ResourceRef> = emptyList(),
    val error: String? = null, val selected: ResourceRef? = null,
    val profile: ServerProfile? = null, val accounts: List<AccountRecord> = emptyList(), val editorVersion: Int = 0,
    val notice: String? = null,
)
data class SessionContext(val profile: ServerProfile, val account: AccountRecord, val api: NasSession, val generation: Int)

class ClientModel(application: Application) : AndroidViewModel(application) {
    private val mutable = MutableStateFlow(ClientState())
    val state = mutable.asStateFlow()
    val player = NativePlayer(application)
    private val store = ProfileStore(ClientDatabase.get(application), CredentialVault(application))
    val profiles = store.profiles.catch { mutable.value = mutable.value.copy(error = "无法读取服务器档案，请重试") }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val embeddedNetwork = EmbeddedNetwork(application)
    private val networkMutable = MutableStateFlow(NetworkState())
    val networkState = networkMutable.asStateFlow()
    private var networkJob: Job? = null
    private var networkPollJob: Job? = null
    private var networkActivated = false
    private var foreground = false
    private var context: SessionContext? = null
    private var lease = ""
    private var generation = 0
    private var operation: Job? = null
    private val cleanup = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val navigation = ArrayDeque<Pair<String, String>>()

    fun connect(url: String, username: String, password: String, network: String = "direct") {
        val profile = mutable.value.profile
        connectDraft(profile?.name ?: android.net.Uri.parse(url).host.orEmpty(), url, profile?.backend ?: BackendKind.NAS, username, password, network)
    }
    fun connectDraft(name: String, url: String, backend: BackendKind, username: String, password: String, network: String) {
        val draft = (mutable.value.profile ?: ServerProfile(name = name, address = url)).copy(name = name, address = url, backend = backend,
            network = if (network == "tailnet") ConnectionMode.TAILNET else ConnectionMode.DIRECT)
        connectTo(draft, username, password, null)
    }
    fun restore(account: AccountRecord) { mutable.value.profile?.let { connectTo(it, account.username, "", account) } }
    private fun connectTo(draft: ServerProfile, username: String, password: String, restored: AccountRecord?) {
        operation?.cancel(); generation++
        closeSession(); navigation.clear()
        val expected = generation
        mutable.value = mutable.value.copy(busy = true, stage = if (restored == null) "正在登录服务器" else "正在恢复登录", error = null, selected = null)
        operation = viewModelScope.launch {
            var opened: NasSession? = null
            try {
                val profile = store.save(draft)
                require(profile.backend == BackendKind.NAS) { "Windows 服务适配尚未完成，暂不支持连接" }
                if (profile.network == ConnectionMode.TAILNET) {
                    val status = embeddedNetwork.status(); networkMutable.value = status
                    if (!status.connected) throw IllegalStateException("请先连接并登录 Tailscale")
                }
                opened = if (restored == null) NasSession.login(profile, username, password)
                    else NasSession.restore(profile, store.token(profile, restored) ?: error("保存的登录已失效，请重新输入密码"), restored.userId)
                if (generation != expected) return@launch
                // A restored token is accepted only after an authenticated read.
                val root = opened.request("GET", "/api/resources/")
                val account = store.saveLogin(profile, opened.identity.id, opened.identity.username, opened.token())
                val bound = SessionContext(profile, account, opened, expected)
                val accounts = store.accounts(profile)
                val directory = store.directory(account)
                if (generation != expected) return@launch
                context = bound
                mutable.value = mutable.value.copy(connected = true, profile = profile, accounts = accounts, serverLabel = "${profile.name} · ${account.username}", busy = true, stage = "正在读取目录", notice = null)
                if (directory == null || directory.path == "/") applyDirectory(root, "/", "/", bound)
                else try { loadDirectory(directory.path, directory.wirePath, bound) } catch (error: Exception) {
                    if (error !is ServiceException || error.status !in setOf(403, 404)) throw error
                    applyDirectory(root, "/", "/", bound)
                    if (generation == expected) mutable.value = mutable.value.copy(notice = "上次的目录已不可用，已打开根目录")
                }
            } catch (error: Exception) {
                if (error !is CancellationException && generation == expected) {
                    closeSession()
                    mutable.value = mutable.value.copy(connected = false, busy = false, stage = "", error = error.message ?: "无法连接服务器")
                }
            } finally {
                val release = opened
                if (release != null && release != context?.api) cleanup.launch { release.close() }
            }
        }
    }
    fun selectProfile(profile: ServerProfile?) {
        disconnect()
        val expected = generation
        mutable.value = mutable.value.copy(profile = profile, editorVersion = mutable.value.editorVersion + 1, busy = profile != null, stage = if (profile != null) "正在读取档案" else "")
        if (profile != null) operation = viewModelScope.launch {
            try { val accounts = store.accounts(profile); if (generation == expected) mutable.value = mutable.value.copy(accounts = accounts, busy = false, stage = "") }
            catch (error: Exception) { if (error !is CancellationException && generation == expected) mutable.value = mutable.value.copy(busy = false, error = "无法读取档案账号，请重试") }
        }
    }
    fun saveDraft(name: String, address: String, backend: BackendKind, network: String) {
        operation?.cancel(); val expected = ++generation
        val draft = (mutable.value.profile ?: ServerProfile(name = name, address = address)).copy(name = name, address = address, backend = backend,
            network = if (network == "tailnet") ConnectionMode.TAILNET else ConnectionMode.DIRECT)
        mutable.value = mutable.value.copy(busy = true, stage = "正在保存档案", error = null)
        operation = viewModelScope.launch {
            try { val profile = store.save(draft); val accounts = store.accounts(profile); if (expected == generation) mutable.value = mutable.value.copy(profile = profile, accounts = accounts, busy = false, stage = "") }
            catch (error: Exception) { if (error !is CancellationException && expected == generation) mutable.value = mutable.value.copy(busy = false, stage = "", error = error.message ?: "无法保存档案") }
        }
    }
    fun removeProfile(profile: ServerProfile) {
        selectProfile(null); val expected = generation
        operation = viewModelScope.launch { try { store.remove(profile) } catch (error: Exception) { if (error !is CancellationException && generation == expected) mutable.value = mutable.value.copy(error = "无法移除档案，请重试") } }
    }
    private suspend fun loadDirectory(path: String, wire: String, bound: SessionContext) {
        val encoded = wire.ifEmpty { path.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") } }
        applyDirectory(bound.api.request("GET", "/api/resources$encoded"), path, wire, bound)
    }
    private suspend fun applyDirectory(data: JSONObject, path: String, wire: String, bound: SessionContext) {
        val items = data.optJSONArray("items") ?: error("服务器返回了不支持的目录格式")
        val files = (0 until items.length()).map { index ->
            val item = items.getJSONObject(index)
            ResourceRef(item.optString("path"), item.optString("wirePath"), item.optString("name"), item.optBoolean("isDir"), item.optString("type"), item.optLong("size"))
        }.sortedWith(compareByDescending<ResourceRef> { it.directory }.thenBy { it.name.lowercase() })
        if (generation == bound.generation && context == bound) {
            store.saveDirectory(bound.account, path, wire)
            if (generation == bound.generation && context == bound) mutable.value = mutable.value.copy(busy = false, stage = "", path = path, wirePath = wire, files = files, error = null)
        }
    }

    fun open(file: ResourceRef) {
        if (file.directory) {
            navigation.addLast(mutable.value.path to mutable.value.wirePath)
            browse(file.path, file.wirePath)
        } else if (file.type == "video" || file.name.substringAfterLast('.').lowercase() in setOf("mkv", "mp4", "webm", "avi", "mov", "m2ts", "ts")) {
            val bound = context ?: return
            operation?.cancel(); val expected = generation
            mutable.value = mutable.value.copy(busy = true, stage = "正在打开视频", error = null)
            operation = viewModelScope.launch {
                try {
                    val url = bound.api.lease(file.path, file.wirePath)
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
        val bound = context ?: return
        operation?.cancel(); val expected = generation
        mutable.value = mutable.value.copy(busy = true, stage = "正在读取目录", error = null)
        operation = viewModelScope.launch {
            try { loadDirectory(path, wire, bound) } catch (error: Exception) {
                if (error !is CancellationException && generation == expected) mutable.value = mutable.value.copy(busy = false, error = error.message ?: "目录读取失败")
            }
        }
    }
    fun retry() { browse(mutable.value.path, mutable.value.wirePath) }
    fun cancel() { operation?.cancel(); mutable.value = mutable.value.copy(busy = false, stage = "") }
    fun back(): Boolean {
        if (mutable.value.selected != null) { leavePlayer(); return true }
        if (navigation.isNotEmpty()) { val previous = navigation.removeLast(); browse(previous.first, previous.second); return true }
        if (mutable.value.path != "/") {
            fun parent(value: String) = value.trimEnd('/').substringBeforeLast('/', "").ifBlank { "/" }
            browse(parent(mutable.value.path), parent(mutable.value.wirePath)); return true
        }
        return false
    }
    fun leavePlayer() { player.stop(); revokeLease(); mutable.value = mutable.value.copy(selected = null) }
    private fun revokeLease() { val old = lease; lease = ""; if (old.isNotEmpty()) cleanup.launch { NativeTransport.call(JSONObject().put("op", "revoke").put("url", old)) } }
    private fun closeSession() {
        player.stop(); revokeLease(); val old = context; context = null
        if (old != null) cleanup.launch { old.api.close() }
    }
    fun disconnect() { operation?.cancel(); generation++; closeSession(); navigation.clear(); mutable.value = ClientState(editorVersion = mutable.value.editorVersion) }
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
