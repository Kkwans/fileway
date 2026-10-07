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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.net.URLEncoder

typealias FileLayout = io.github.kkwans.nasfilebrowser.data.FileLayout

data class ResourceRef(val path: String, val wirePath: String, val name: String, val directory: Boolean, val type: String, val size: Long, val modified: String = "")
data class ClientState(
    val startupPending: Boolean = false,
    val connected: Boolean = false, val busy: Boolean = false, val stage: String = "",
    val serverLabel: String = "", val accountName: String = "", val path: String = "/", val wirePath: String = "/", val files: List<ResourceRef> = emptyList(),
    val error: String? = null, val selected: ResourceRef? = null, val image: ResourceRef? = null,
    val mediaQueue: MediaQueue? = null,
    val fileCategory: FileCategory = FileCategory.ALL, val fileOrder: FileOrder = FileOrder.NAME,
    val downloadBytesPerSecond: Long? = null,
    val profile: ServerProfile? = null, val accounts: List<AccountRecord> = emptyList(), val editorVersion: Int = 0,
    val notice: String? = null,
    val progressStatus: String? = null, val tab: String = "files", val previewScope: String = "", val fileLayout: FileLayout = FileLayout.COVER,
)
data class SessionContext(val profile: ServerProfile, val account: AccountRecord, val api: NasSession, val generation: Int, val owner: String = java.util.UUID.randomUUID().toString())
private data class PlaybackBinding(val context: SessionContext, val file: ResourceRef, val identity: String, val writer: PlaybackWriter)

class ClientModel(application: Application) : AndroidViewModel(application) {
    private val mutable = MutableStateFlow(ClientState(startupPending = true))
    val state = mutable.asStateFlow()
    val player = NativePlayer(application)
    val playbackPreferences = PlaybackPreferences(application)
    val cache = CacheController(application, viewModelScope)
    val previewImageLoader get() = cache.thumbnailLoader.value
    fun cacheAccount(): String = context?.account?.key.orEmpty()
    fun thumbnailKey(file: ResourceRef): String = cache.key(cacheAccount(), file.wirePath.ifEmpty { file.path }, "${file.size}/${file.modified}")
    private val store = ProfileStore(ClientDatabase.get(application), CredentialVault(application))
    private val appearanceStore = AppearanceStore(ClientDatabase.get(application))
    val appearance = AppearanceController(viewModelScope, { appearanceStore.theme.first() }, appearanceStore::save)
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
    private val subtitleLeases = mutableListOf<PreviewLease>()
    private var generation = 0
    private var mediaRequest = 0L
    private var queueSequence = 0L
    private var pendingMediaOpen: Long? = null
    private var pendingOpenFromPlayer = false
    private var operation: Job? = null
    private var startupJob: Job? = null
    private val cleanup = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val navigation = ArrayDeque<Pair<String, String>>()
    private var playback: PlaybackBinding? = null
    private var saveTimer: Job? = null
    private var transferTimer: Job? = null
    private var resumeOperation: Job? = null
    private var recentJob: Job? = null
    private var closing: Job? = null
    private var mediaClosing: Job? = null
    private var lastSaved: Pair<Long, Long>? = null
    private val layoutWrites = Mutex()
    private var layoutRequest = 0L
    val search: SearchController = SearchController(viewModelScope, { context == it && generation == it.generation }, ::openSearchResult)
    init {
        player.checkpoint = { saveProgress() }
        val expected = generation
        startupJob = viewModelScope.launch {
            try {
                val active = store.active()
                if (generation != expected) return@launch
                if (active == null) { mutable.value = mutable.value.copy(startupPending = false); return@launch }
                val (profile, account) = active
                val accounts = store.accounts(profile)
                if (generation != expected) return@launch
                mutable.value = mutable.value.copy(profile = profile, accounts = accounts, accountName = account.username,
                    editorVersion = mutable.value.editorVersion + 1)
                connectTo(profile, account.username, "", account, automatic = true)
            } catch (error: Exception) {
                if (error !is CancellationException && generation == expected)
                    mutable.value = mutable.value.copy(startupPending = false, error = "无法恢复本机登录记录，请重试。")
            }
        }
    }

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
    private fun connectTo(draft: ServerProfile, username: String, password: String, restored: AccountRecord?, automatic: Boolean = false) {
        if (!automatic) startupJob?.cancel()
        operation?.cancel(); generation++
        closeSession(); navigation.clear()
        val expected = generation
        mutable.value = mutable.value.copy(startupPending = automatic, busy = true, stage = if (restored == null) "正在登录服务器" else "正在恢复登录", error = null, selected = null, image = null, mediaQueue = null, previewScope = "", fileLayout = FileLayout.COVER)
        operation = viewModelScope.launch {
            var opened: NasSession? = null
            try {
                closing?.join()
                val profile = store.save(draft)
                require(profile.backend == BackendKind.NAS) { "Windows 服务适配尚未完成，暂不支持连接" }
                if (profile.network == ConnectionMode.TAILNET) {
                    var status = if (restored == null) embeddedNetwork.status() else embeddedNetwork.start()
                    if (restored != null) {
                        networkActivated = true
                        try {
                            kotlinx.coroutines.withTimeout(20_000) {
                                while (!status.connected && status.state !in setOf("NeedsLogin", "NeedsMachineAuth", "Error")) {
                                    delay(250); status = embeddedNetwork.status()
                                }
                            }
                        } catch (_: kotlinx.coroutines.TimeoutCancellationException) { throw IllegalStateException("内嵌节点尚未连接，请检查网络并重试。") }
                        observeNetwork()
                    }
                    networkMutable.value = status
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
                store.activate(profile, account, bound.owner)
                if (generation != expected) return@launch
                context = bound
                recentJob?.cancel()
                recentJob = viewModelScope.launch { history.recent(account).collect { entries -> if (context == bound) recentMutable.value = entries.distinctBy { it.resourceKey } } }
                mutable.value = mutable.value.copy(connected = true, profile = profile, accounts = accounts, serverLabel = "${profile.name} · ${account.username}", busy = true, stage = "正在读取目录", notice = null, previewScope = opened.id, accountName = account.username, fileLayout = directory?.fileLayout ?: FileLayout.COVER)
                if (directory == null || directory.path == "/") applyDirectory(root, "/", "/", bound)
                else try { loadDirectory(directory.path, directory.wirePath, bound) } catch (error: Exception) {
                    if (error !is ServiceException || error.status !in setOf(403, 404)) throw error
                    applyDirectory(root, "/", "/", bound)
                    if (generation == expected) mutable.value = mutable.value.copy(notice = "上次的目录已不可用，已打开根目录")
                }
                if (generation == expected && context == bound) {
                    mutable.value = mutable.value.copy(startupPending = false)
                }
            } catch (error: Exception) {
                if (error !is CancellationException && generation == expected) {
                    closeSession()
                    mutable.value = mutable.value.copy(startupPending = false, connected = false, busy = false, stage = "", error = error.message ?: "无法连接服务器")
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

    fun directoryItems(): List<ResourceRef> = mutable.value.let { presentFiles(it.files, it.fileCategory, it.fileOrder) }
    fun fileCategory(value: FileCategory) { mutable.value = mutable.value.copy(fileCategory = value) }
    fun fileOrder(value: FileOrder) { mutable.value = mutable.value.copy(fileOrder = value) }
    fun open(file: ResourceRef) = openFrom(file, directoryItems(), MediaQueueSource.DIRECTORY)
    private fun openSearchResult(file: ResourceRef) = openFrom(file, search.mediaSnapshot(), MediaQueueSource.SEARCH)
    private fun openFrom(file: ResourceRef, candidates: List<ResourceRef>, source: MediaQueueSource) {
        val bound = context ?: return
        openQueued(file, MediaQueue.snapshot(++queueSequence, bound.account.key, file, candidates, source))
    }
    fun navigateMedia(index: Int) {
        val bound = context ?: return
        val queue = mutable.value.mediaQueue?.takeIf { it.owner == bound.account.key }?.select(index) ?: return
        if (queue.index == mutable.value.mediaQueue?.index) return
        if (queue.kind == MediaKind.IMAGE) mutable.value = mutable.value.copy(image = queue.current, mediaQueue = queue, error = null)
        else openQueued(queue.current, queue)
    }
    fun previousMedia() { mutable.value.mediaQueue?.let { navigateMedia(it.index - 1) } }
    fun nextMedia() { mutable.value.mediaQueue?.let { navigateMedia(it.index + 1) } }
    fun retryPlayback() {
        val file = mutable.value.selected ?: return
        openQueued(file, mutable.value.mediaQueue)
    }
    private fun openQueued(file: ResourceRef, queue: MediaQueue?) {
        if (file.directory) {
            navigation.addLast(mutable.value.path to mutable.value.wirePath)
            browse(file.path, file.wirePath)
        } else if (file.mediaKind() == MediaKind.VIDEO) {
            val bound = context ?: return
            search.cancel()
            operation?.cancel(); val expected = generation; val request = ++mediaRequest
            // Preserve the entry context when a queued selection supersedes an
            // initial open that has not completed yet.
            pendingOpenFromPlayer = if (pendingMediaOpen != null) pendingOpenFromPlayer else mutable.value.selected != null
            pendingMediaOpen = request
            mutable.value = mutable.value.copy(selected = file, image = null, mediaQueue = queue, busy = true, stage = "正在打开视频", error = null)
            operation = viewModelScope.launch {
                try {
                    endPlayback()
                    mediaClosing?.join()
                    currentCoroutineContext().ensureActive()
                    if (generation != expected || mediaRequest != request || context != bound) return@launch
                    val remote = history.remote(bound.api, file.path, file.wirePath)
                    val key = file.wirePath.ifEmpty { file.path }
                    val local = history.local(bound.account, key, remote.identity)
                    val resume = PlaybackHistory.resume(local, remote)
                    cache.awaitReady()
                    val url = bound.api.lease(file.path, file.wirePath, bound.account.key + "/" + remote.identity)
                    if (generation != expected || mediaRequest != request || context != bound) { cleanup.launch { NativeTransport.call(JSONObject().put("op", "revoke").put("url", url)) }; return@launch }
                    revokeLease(); lease = url
                    val writer = PlaybackWriter(history, bound.api) { snapshot, failure -> viewModelScope.launch {
                        val current = playback
                        if (mediaRequest == request && current?.context == bound && current.file.wirePath.ifEmpty { current.file.path } == key && current.identity == remote.identity &&
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
                    pendingMediaOpen = null
                    pendingOpenFromPlayer = false
                    lastSaved = null
                    mutable.value = mutable.value.copy(selected = file, busy = false, stage = "", progressStatus = null)
                    player.open(url, resume, autoplay = foreground)
                    observeTransfer(bound, url)
                    saveTimer?.cancel()
                    saveTimer = viewModelScope.launch { while (true) { delay(10_000); if (player.state.value.playing) saveProgress() } }
                } catch (error: Exception) {
                    if (error !is CancellationException && generation == expected && mediaRequest == request) {
                        pendingMediaOpen = null
                        pendingOpenFromPlayer = false
                        mutable.value = mutable.value.copy(busy = false, stage = "", error = "无法打开视频，请重试或选择其他视频")
                    }
                }
            }
        } else if (file.mediaKind() == MediaKind.IMAGE) {
            search.cancel(); operation?.cancel(); mediaRequest++; pendingMediaOpen = null; pendingOpenFromPlayer = false; endPlayback()
            mutable.value = mutable.value.copy(selected = null, image = file, mediaQueue = queue, busy = false, stage = "", error = null)
        } else mutable.value = mutable.value.copy(error = "这个文件类型暂不支持打开")
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
    fun jumpDirectory(crumb: DirectoryCrumb) {
        if (mutable.value.busy || crumb.path == mutable.value.path || crumb.wirePath == null) return
        if (directoryTrail(mutable.value.path, mutable.value.wirePath).none { it == crumb }) return
        navigation.clear()
        browse(crumb.path, crumb.wirePath)
    }
    suspend fun preview(file: ResourceRef, contain: Boolean = false): PreviewLease {
        val bound = context ?: error("服务器尚未连接")
        val asset = bound.api.preview(file.path, file.wirePath, contain)
        try {
            currentCoroutineContext().ensureActive()
            check(context == bound && generation == bound.generation) { "服务器来源已切换" }
            return asset
        } catch (error: Throwable) {
            runCatching { asset.release() }.onFailure { error.addSuppressed(it) }
            throw error
        }
    }
    suspend fun subtitleFiles(path: String, wirePath: String): List<ResourceRef> {
        val binding = playback ?: error("视频尚未打开")
        val wire = wirePath.ifEmpty { SearchResult.encodePath(path) }
        val data = binding.context.api.request("GET", "/api/resources$wire")
        check(playback === binding && context == binding.context) { "播放来源已切换" }
        val items = data.optJSONArray("items") ?: error("不是可读取的目录")
        return (0 until items.length()).map { i -> items.getJSONObject(i).let {
            ResourceRef(it.optString("path"), it.optString("wirePath"), it.optString("name"), it.optBoolean("isDir"), it.optString("type"), it.optLong("size"), it.optString("modified"))
        } }.filter { it.directory || isExternalSubtitle(it.name) }
            .sortedWith(compareByDescending<ResourceRef> { it.directory }.thenBy { it.name.lowercase() })
    }
    suspend fun addExternalSubtitle(file: ResourceRef) {
        require(!file.directory && isExternalSubtitle(file.name)) { "请选择支持的字幕文件" }
        val binding = playback ?: error("视频尚未打开")
        check(player.canAddExternalSubtitle) { "视频仍在加载，请稍后重试添加字幕" }
        val url = binding.context.api.lease(file.path, file.wirePath)
        val asset = PreviewLease(url, binding.context.api.id) { NativeTransport.call(JSONObject().put("op", "revoke").put("url", url)); Unit }
        try {
            currentCoroutineContext().ensureActive()
            check(playback === binding && context == binding.context) { "播放来源已切换" }
            check(player.addSubtitle(url, file.name)) { "播放器未能加载字幕，请确认文件格式后重试" }
            subtitleLeases.add(asset)
        } catch (error: Throwable) { asset.release(); throw error }
    }
    fun closeImage() { mutable.value = mutable.value.copy(image = null, mediaQueue = null) }
    suspend fun image(file: ResourceRef, quality: ImageQuality): Pair<PreviewLease, ResourceRef> {
        val bound = context ?: error("服务器尚未连接")
        cache.awaitReady()
        val wire = file.wirePath.ifEmpty { file.path.split('/').joinToString("/") { android.net.Uri.encode(it) } }
        val data = bound.api.request("GET", "/api/resources$wire")
        require(!data.optBoolean("isDir")) { "该文件已变化" }
        val current = file.copy(size = data.optLong("size", file.size), modified = data.optString("modified", file.modified))
        val asset = bound.api.image(current.path, current.wirePath, quality)
        try { currentCoroutineContext().ensureActive(); check(context == bound && generation == bound.generation) { "服务器来源已切换" }; return asset to current }
        catch (error: Throwable) { asset.release(); throw error }
    }
    fun openSearch() {
        val bound = context ?: return
        if (!mutable.value.busy && mutable.value.selected == null) search.open(bound, mutable.value.path, mutable.value.wirePath)
    }
    fun cancel() {
        startupJob?.cancel()
        operation?.cancel()
        resumeOperation?.cancel()
        val opening = pendingMediaOpen != null
        val returnToSource = opening && !pendingOpenFromPlayer
        if (opening) { mediaRequest++; pendingMediaOpen = null; pendingOpenFromPlayer = false }
        if (returnToSource) endPlayback()
        val startup = mutable.value.startupPending
        if (startup) { generation++; closeSession() }
        mutable.value = mutable.value.copy(startupPending = false, busy = false, stage = "",
            connected = if (startup) false else mutable.value.connected,
            previewScope = if (startup) "" else mutable.value.previewScope,
            selected = if (returnToSource) null else mutable.value.selected,
            mediaQueue = if (returnToSource) null else mutable.value.mediaQueue,
            error = if (returnToSource) null else if (opening) "已取消打开，可重试或选择其他视频" else mutable.value.error)
    }
    fun back(): Boolean {
        if (mutable.value.image != null) { closeImage(); return true }
        if (mutable.value.selected != null) { leavePlayer(); return true }
        if (search.state.value.open) { cancel(); search.close(); return true }
        if (mutable.value.tab != "files") { tab("files"); return true }
        if (navigation.isNotEmpty()) { val previous = navigation.removeLast(); browse(previous.first, previous.second); return true }
        if (mutable.value.path != "/") {
            val trail = directoryTrail(mutable.value.path, mutable.value.wirePath)
            val parent = trail.dropLast(1).lastOrNull { it.wirePath != null } ?: trail.first()
            browse(parent.path, parent.wirePath ?: "/"); return true
        }
        return false
    }
    fun clearPreviewCache() { previewImageLoader.memoryCache?.clear() }
    fun fileLayout(value: FileLayout) {
        val bound = context ?: return
        val request = ++layoutRequest
        viewModelScope.launch {
            try {
                // Preserve write order for rapid selections. Each write keeps
                // its original account; a late result never changes a new session.
                layoutWrites.withLock { store.saveFileLayout(bound.account, value) }
                if (context == bound && generation == bound.generation && layoutRequest == request)
                    mutable.value = mutable.value.copy(fileLayout = value,
                        notice = mutable.value.notice.takeUnless { it == "布局未能保存，请重新选择并重试" })
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (context == bound && generation == bound.generation && layoutRequest == request)
                    mutable.value = mutable.value.copy(notice = "布局未能保存，请重新选择并重试")
            }
        }
    }
    fun tab(value: String) { if (value != "files") search.close(); mutable.value = mutable.value.copy(tab = value) }
    fun openRecent(snapshot: PlaybackSnapshot) {
        val bound = context ?: return
        if (snapshot.accountKey != bound.account.key) return
        val file = ResourceRef(snapshot.path, snapshot.wirePath, snapshot.name, false, "video", 0)
        openFrom(file, listOf(file), MediaQueueSource.SINGLE)
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
        transferTimer?.cancel(); transferTimer = null
        mutable.value = mutable.value.copy(downloadBytesPerSecond = null)
        resumeOperation?.cancel()
        saveTimer?.cancel()
        val old = playback; val value = old?.let(::snapshot)
        playback = null; lastSaved = null
        player.pause()
        val stored = if (old != null && value != null) old.writer.submit(value) else null
        val subtitles = subtitleLeases.toList(); subtitleLeases.clear()
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
                finally { subtitles.forEach { runCatching { it.release() } }; old?.writer?.close() }
            }
        } else player.stop()
    }
    fun leavePlayer() {
        val returnToFiles = pendingMediaOpen != null && !pendingOpenFromPlayer && mutable.value.mediaQueue?.source == MediaQueueSource.SEARCH
        operation?.cancel(); mediaRequest++; pendingMediaOpen = null; pendingOpenFromPlayer = false
        endPlayback()
        if (returnToFiles) search.close()
        mutable.value = mutable.value.copy(selected = null, mediaQueue = null, busy = false, stage = "", error = null)
    }
    private fun revokeLease() { val old = lease; lease = ""; if (old.isNotEmpty()) cleanup.launch { NativeTransport.call(JSONObject().put("op", "revoke").put("url", old)) } }
    private fun observeTransfer(bound: SessionContext, url: String) {
        transferTimer?.cancel()
        mutable.value = mutable.value.copy(downloadBytesPerSecond = null)
        transferTimer = viewModelScope.launch {
            var previousBytes = 0L
            var previousMillis = -1L
            val samples = ArrayDeque<Long>()
            while (context == bound && lease == url) {
                try {
                    val result = NativeTransport.call(JSONObject().put("op", "lease_stats").put("session", bound.api.id).put("url", url)) as JSONObject
                    if (context != bound || lease != url) break
                    val bytes = result.getLong("upstreamBytes")
                    val millis = result.getLong("elapsedMillis")
                    if (previousMillis >= 0 && millis > previousMillis) {
                        samples.addLast(((bytes - previousBytes).coerceAtLeast(0) * 1000 / (millis - previousMillis)))
                        while (samples.size > 3) samples.removeFirst()
                        mutable.value = mutable.value.copy(downloadBytesPerSecond = samples.average().toLong())
                    }
                    previousBytes = bytes; previousMillis = millis
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    if (context == bound && lease == url) mutable.value = mutable.value.copy(downloadBytesPerSecond = null)
                    previousMillis = -1; samples.clear()
                }
                delay(500)
            }
        }
    }
    private fun closeSession() {
        mediaRequest++; pendingMediaOpen = null; pendingOpenFromPlayer = false
        previewImageLoader.memoryCache?.clear()
        search.close()
        endPlayback(); val old = context; context = null
        recentJob?.cancel(); recentMutable.value = emptyList()
        val previous = closing; val media = mediaClosing
        if (old != null) closing = cleanup.launch { previous?.join(); media?.join(); old.api.close() }
    }
    fun disconnect() { startupJob?.cancel(); operation?.cancel(); generation++; closeSession(); navigation.clear(); mutable.value = ClientState(editorVersion = mutable.value.editorVersion) }
    fun connectNetwork() {
        networkActivated = true
        networkJob?.cancel()
        networkPollJob?.cancel()
        networkJob = viewModelScope.launch {
            try {
                networkMutable.value = networkMutable.value.copy(state = "Starting", authUrl = "", error = null)
                networkMutable.value = embeddedNetwork.start()
                observeNetwork()
            } catch (error: Exception) {
                if (error !is CancellationException) networkMutable.value = networkMutable.value.copy(state = "Error", authUrl = "", error = networkError(error))
            }
        }
    }
    private fun networkError(error: Exception): String = when (error.message) {
        "android network snapshot unavailable", "invalid android network snapshot", "invalid android network interface", "invalid android interface address" -> "无法读取系统网络信息，请重试。"
        "cannot start embedded network" -> "内嵌节点启动失败，请重试（TS_START）。"
        "cannot control embedded network" -> "无法控制内嵌节点，请重试（TS_CONTROL）。"
        "cannot enable approved subnet routes" -> "无法设置子网路由，请重试（TS_ROUTES）。"
        "cannot open embedded login flow" -> "无法发起官方登录，请重试（TS_LOGIN）。"
        "cannot prepare embedded log storage", "cannot configure embedded log storage" -> "无法准备内嵌节点存储，请检查可用空间后重试（TS_STORAGE）。"
        "cannot unlock node state; original identity preserved" -> "无法解锁保存的节点，原有身份已保留。"
        else -> "内嵌网络暂不可用，请检查网络后重试（TS_NETWORK）。"
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
        networkJob?.cancel(); networkPollJob?.cancel()
        val old = context?.takeIf { it.profile.network == ConnectionMode.TAILNET }
        if (old != null) disconnect()
        networkJob = viewModelScope.launch { try { closing?.join(); mediaClosing?.join(); old?.let { store.deactivate(it.account, it.owner) }; if (logout) embeddedNetwork.logout() else embeddedNetwork.stop(); networkMutable.value = embeddedNetwork.status(); observeNetwork() } catch (error: Exception) { if (error !is CancellationException) networkMutable.value = networkMutable.value.copy(state = "Error", error = "无法断开内嵌网络，请重试") } }
    }
    override fun onCleared() { operation?.cancel(); networkJob?.cancel(); networkPollJob?.cancel(); closeSession(); cache.close(); player.checkpoint = null; player.release(); super.onCleared() }
}
