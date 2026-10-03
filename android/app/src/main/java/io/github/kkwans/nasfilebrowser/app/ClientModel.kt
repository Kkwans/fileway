package io.github.kkwans.nasfilebrowser.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import coil3.ImageLoader
import coil3.memory.MemoryCache
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import org.json.JSONObject
import java.net.URLEncoder

data class ResourceRef(val path: String, val wirePath: String, val name: String, val directory: Boolean, val type: String, val size: Long, val modified: String = "")
data class ClientState(
    val connected: Boolean = false, val busy: Boolean = false, val stage: String = "",
    val serverLabel: String = "", val path: String = "/", val wirePath: String = "/", val files: List<ResourceRef> = emptyList(),
    val error: String? = null, val selected: ResourceRef? = null,
    val profile: ServerProfile? = null, val accounts: List<AccountRecord> = emptyList(), val editorVersion: Int = 0,
    val notice: String? = null,
    val progressStatus: String? = null, val tab: String = "files", val previewScope: String = "",
)
data class SessionContext(val profile: ServerProfile, val account: AccountRecord, val api: NasSession, val generation: Int)
private data class PlaybackBinding(val context: SessionContext, val file: ResourceRef, val identity: String, val writer: PlaybackWriter)

class ClientModel(application: Application) : AndroidViewModel(application) {
    private val mutable = MutableStateFlow(ClientState())
    val state = mutable.asStateFlow()
    val player = NativePlayer(application)
    val previewImageLoader = ImageLoader.Builder(application)
        .memoryCache { MemoryCache.Builder().maxSizeBytes(16L * 1024 * 1024).build() }
        .diskCache(null).build()
    private val store = ProfileStore(ClientDatabase.get(application), CredentialVault(application))
    private val history = PlaybackHistory(ClientDatabase.get(application))
    private val recentMutable = MutableStateFlow<List<PlaybackSnapshot>>(emptyList())
    val recent = recentMutable.asStateFlow()
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
    private var playback: PlaybackBinding? = null
    private var saveTimer: Job? = null
    private var resumeOperation: Job? = null
    private var recentJob: Job? = null
    private var closing: Job? = null
    private var mediaClosing: Job? = null
    private var lastSaved: Pair<Long, Long>? = null
    val search = SearchController(viewModelScope, { context == it && generation == it.generation }, ::open)
    init { player.checkpoint = { saveProgress() } }

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
        mutable.value = mutable.value.copy(busy = true, stage = if (restored == null) "正在登录服务器" else "正在恢复登录", error = null, selected = null, previewScope = "")
        operation = viewModelScope.launch {
            var opened: NasSession? = null
            try {
                closing?.join()
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
                recentJob?.cancel()
                recentJob = viewModelScope.launch { history.recent(account).collect { entries -> if (context == bound) recentMutable.value = entries.distinctBy { it.resourceKey } } }
                mutable.value = mutable.value.copy(connected = true, profile = profile, accounts = accounts, serverLabel = "${profile.name} · ${account.username}", busy = true, stage = "正在读取目录", notice = null, previewScope = opened.id)
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
            ResourceRef(item.optString("path"), item.optString("wirePath"), item.optString("name"), item.optBoolean("isDir"), item.optString("type"), item.optLong("size"), item.optString("modified"))
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
            search.cancel()
            operation?.cancel(); val expected = generation
            mutable.value = mutable.value.copy(busy = true, stage = "正在打开视频", error = null)
            operation = viewModelScope.launch {
                try {
                    endPlayback()
                    mediaClosing?.join()
                    val remote = history.remote(bound.api, file.path, file.wirePath)
                    val key = file.wirePath.ifEmpty { file.path }
                    val local = history.local(bound.account, key, remote.identity)
                    val resume = PlaybackHistory.resume(local, remote)
                    val url = bound.api.lease(file.path, file.wirePath)
                    if (generation != expected) { cleanup.launch { NativeTransport.call(JSONObject().put("op", "revoke").put("url", url)) }; return@launch }
                    revokeLease(); lease = url
                    val writer = PlaybackWriter(history, bound.api) { snapshot, failure -> viewModelScope.launch {
                        val current = playback
                        if (current?.context == bound && current.file.wirePath.ifEmpty { current.file.path } == key && current.identity == remote.identity &&
                            (snapshot == null || lastSaved == (snapshot.positionMs to snapshot.durationMs))) {
                            val message = when (snapshot?.sync) {
                                ProgressSync.SYNCED -> "续播已同步"
                                ProgressSync.IDENTITY_CHANGED -> "文件已变化，旧进度仅保留本机"
                                ProgressSync.UNSUPPORTED -> "续播已保存本机，此路径无法远端同步"
                                ProgressSync.PENDING -> "续播已保存本机，待同步"
                                null -> if (failure != null) "续播保存失败，请重试" else null
                            }
                            if (snapshot == null && failure != null) lastSaved = null
                            mutable.value = mutable.value.copy(progressStatus = message)
                        }
                    } }
                    playback = PlaybackBinding(bound, file, remote.identity, writer)
                    lastSaved = null
                    mutable.value = mutable.value.copy(selected = file, busy = false, stage = "", progressStatus = null)
                    player.open(url, resume, autoplay = foreground)
                    saveTimer?.cancel()
                    saveTimer = viewModelScope.launch { while (true) { delay(10_000); if (player.state.value.playing) saveProgress() } }
                } catch (error: Exception) {
                    if (error !is CancellationException && generation == expected) mutable.value = mutable.value.copy(busy = false, error = "无法打开视频，请重试")
                }
            }
        } else mutable.value = mutable.value.copy(error = "当前版本先支持视频播放")
    }
    private fun browse(path: String, wire: String) {
        val bound = context ?: return
        search.close()
        operation?.cancel(); val expected = generation
        mutable.value = mutable.value.copy(busy = true, stage = "正在读取目录", error = null)
        operation = viewModelScope.launch {
            try { loadDirectory(path, wire, bound) } catch (error: Exception) {
                if (error !is CancellationException && generation == expected) mutable.value = mutable.value.copy(busy = false, error = error.message ?: "目录读取失败")
            }
        }
    }
    fun retry() { browse(mutable.value.path, mutable.value.wirePath) }
    suspend fun preview(file: ResourceRef): PreviewLease {
        val bound = context ?: error("服务器尚未连接")
        val asset = bound.api.preview(file.path, file.wirePath)
        try {
            currentCoroutineContext().ensureActive()
            check(context == bound && generation == bound.generation) { "服务器来源已切换" }
            return asset
        } catch (error: Throwable) {
            runCatching { asset.release() }.onFailure { error.addSuppressed(it) }
            throw error
        }
    }
    fun openSearch() {
        val bound = context ?: return
        if (!mutable.value.busy && mutable.value.selected == null) search.open(bound, mutable.value.path, mutable.value.wirePath)
    }
    fun cancel() {
        operation?.cancel()
        resumeOperation?.cancel()
        mutable.value = mutable.value.copy(busy = false, stage = "")
    }
    fun back(): Boolean {
        if (mutable.value.selected != null) { leavePlayer(); return true }
        if (search.state.value.open) { cancel(); search.close(); return true }
        if (mutable.value.tab != "files") { tab("files"); return true }
        if (navigation.isNotEmpty()) { val previous = navigation.removeLast(); browse(previous.first, previous.second); return true }
        if (mutable.value.path != "/") {
            fun parent(value: String) = value.trimEnd('/').substringBeforeLast('/', "").ifBlank { "/" }
            browse(parent(mutable.value.path), parent(mutable.value.wirePath)); return true
        }
        return false
    }
    fun tab(value: String) { if (value != "files") search.close(); mutable.value = mutable.value.copy(tab = value) }
    fun openRecent(snapshot: PlaybackSnapshot) {
        val bound = context ?: return
        if (snapshot.accountKey != bound.account.key) return
        open(ResourceRef(snapshot.path, snapshot.wirePath, snapshot.name, false, "video", 0))
    }
    fun togglePlayback() {
        if (player.state.value.playing) { pausePlayback(); return }
        val binding = playback ?: return
        if (!foreground) return
        resumeOperation?.cancel()
        mutable.value = mutable.value.copy(busy = true, stage = "正在确认播放来源", error = null)
        resumeOperation = viewModelScope.launch {
            try {
                val remote = history.remote(binding.context.api, binding.file.path, binding.file.wirePath)
                if (playback != binding || context != binding.context) return@launch
                if (remote.identity != binding.identity) {
                    endPlayback()
                    mutable.value = mutable.value.copy(busy = false, stage = "", selected = null, error = "文件已变化，请重新打开；旧进度不会用于新文件。")
                } else {
                    mutable.value = mutable.value.copy(busy = false, stage = "")
                    player.toggle()
                }
            } catch (error: Exception) {
                if (error !is CancellationException && playback == binding) mutable.value = mutable.value.copy(busy = false, stage = "", error = "无法确认网络和文件，请重试播放。")
            }
        }
    }
    fun pausePlayback() {
        if (resumeOperation?.isActive == true) {
            resumeOperation?.cancel()
            mutable.value = mutable.value.copy(busy = false, stage = "")
        }
        player.pause(); saveProgress()
    }
    fun retryProgress() { lastSaved = null; saveProgress() }
    private fun snapshot(binding: PlaybackBinding): PlaybackSnapshot? {
        val value = player.state.value
        if (value.durationMs <= 0 && value.positionMs <= 0) return null
        return PlaybackSnapshot(binding.context.account.key, binding.file.wirePath.ifEmpty { binding.file.path }, binding.identity,
            binding.file.path, binding.file.wirePath, binding.file.name, value.positionMs, value.durationMs, System.currentTimeMillis(), ProgressSync.PENDING)
    }
    private fun saveProgress() {
        val binding = playback ?: return
        val value = snapshot(binding) ?: return
        val fingerprint = value.positionMs to value.durationMs
        if (lastSaved == fingerprint) return
        lastSaved = fingerprint
        mutable.value = mutable.value.copy(progressStatus = "正在保存续播")
        binding.writer.submit(value)
    }
    private fun endPlayback() {
        resumeOperation?.cancel()
        saveTimer?.cancel()
        val old = playback; val value = old?.let(::snapshot)
        playback = null; lastSaved = null
        player.pause()
        val stored = if (old != null && value != null) old.writer.submit(value) else null
        val oldLease = lease; lease = ""
        val previous = mediaClosing
        if (old != null || oldLease.isNotEmpty()) mediaClosing = cleanup.launch {
            previous?.join()
            try { stored?.await() }
            catch (error: Exception) {
                if (error is CancellationException) throw error
                withContext(Dispatchers.Main) { mutable.value = mutable.value.copy(notice = "上次的续播未能保存，请检查本机存储。") }
            } finally {
                withContext(Dispatchers.Main) { player.stop() }
                try { if (oldLease.isNotEmpty()) NativeTransport.call(JSONObject().put("op", "revoke").put("url", oldLease)) }
                finally { old?.writer?.close() }
            }
        } else player.stop()
    }
    fun leavePlayer() { operation?.cancel(); endPlayback(); mutable.value = mutable.value.copy(selected = null, busy = false, stage = "") }
    private fun revokeLease() { val old = lease; lease = ""; if (old.isNotEmpty()) cleanup.launch { NativeTransport.call(JSONObject().put("op", "revoke").put("url", old)) } }
    private fun closeSession() {
        previewImageLoader.memoryCache?.clear()
        search.close()
        endPlayback(); val old = context; context = null
        recentJob?.cancel(); recentMutable.value = emptyList()
        val previous = closing; val media = mediaClosing
        if (old != null) closing = cleanup.launch { previous?.join(); media?.join(); old.api.close() }
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
        if (!active) { networkPollJob?.cancel(); search.cancel(); return }
        if (networkActivated && networkJob?.isActive != true) observeNetwork()
    }
    fun stopNetwork(logout: Boolean = false) {
        networkJob?.cancel(); networkPollJob?.cancel(); disconnect()
        networkJob = viewModelScope.launch { try { closing?.join(); mediaClosing?.join(); if (logout) embeddedNetwork.logout() else embeddedNetwork.stop(); networkMutable.value = embeddedNetwork.status(); observeNetwork() } catch (error: Exception) { if (error !is CancellationException) networkMutable.value = networkMutable.value.copy(state = "Error", error = "无法断开内嵌网络，请重试") } }
    }
    override fun onCleared() { operation?.cancel(); networkJob?.cancel(); networkPollJob?.cancel(); closeSession(); previewImageLoader.shutdown(); player.checkpoint = null; player.release(); super.onCleared() }
}
