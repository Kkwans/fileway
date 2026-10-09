package io.github.kkwans.nasfilebrowser.app

import android.app.ActivityManager
import android.content.Context
import io.github.kkwans.nasfilebrowser.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class DocumentPreviewState(val scope: String = "", val file: ResourceRef? = null, val kind: DocumentPreviewKind = DocumentPreviewKind.OTHER,
    val loading: Boolean = false, val error: String? = null, val text: DocumentText? = null, val query: String = "",
    val search: DocumentTextSearch = DocumentTextSearch(emptyList(), false), val matchIndex: Int = -1, val searching: Boolean = false, val searchError: String? = null,
    val page: Int = 0, val pageCount: Int = 0, val zoom: Int = 100, val rendering: Boolean = false,
    val pageImage: DocumentPageBitmap? = null, val pageError: String? = null)

class DocumentPreviewController internal constructor(private val context: Context, private val scope: CoroutineScope,
    private val isCurrent: (SessionContext) -> Boolean, private val onOpened: (SessionContext, ResourceRef) -> Unit = { _, _ -> },
    private val reader: DocumentPreviewReader = NativeDocumentReader) {
    private val mutable = MutableStateFlow(DocumentPreviewState())
    val state = mutable.asStateFlow()
    private var bound: SessionContext? = null
    private var loading: Job? = null
    private var rendering: Job? = null
    private var searching: Job? = null
    private var revision = 0L
    private var renderRevision = 0L
    private var searchRevision = 0L
    private var pdf: DocumentPdfSession? = null
    private var bitmap: DocumentPageBitmap? = null
    private var width = 1080
    private var opened = false
    private data class BorrowedAsset(val owner: SessionContext, val lease: PreviewLease)
    private var borrowedAsset: BorrowedAsset? = null
    private val cleanup = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val memoryClass = context.getSystemService(ActivityManager::class.java).memoryClass
    private fun current(owner: SessionContext, epoch: Long) = bound === owner && isCurrent(owner) && revision == epoch

    fun bind(value: SessionContext?) {
        if (bound === value) return
        close(); bound = value
        mutable.value = DocumentPreviewState(scope = value?.owner.orEmpty())
    }
    fun close() {
        revision++; renderRevision++; searchRevision++
        loading?.cancel(); rendering?.cancel(); searching?.cancel()
        bitmap?.close(); bitmap = null
        val old = pdf; pdf = null
        if (old != null) cleanup.launch { old.close() }
        opened = false
        borrowedAsset = null
        mutable.value = DocumentPreviewState(scope = bound?.owner.orEmpty())
    }
    fun open(file: ResourceRef, sourceScope: String) {
        val owner = bound ?: return
        if (!isCurrent(owner) || sourceScope != owner.api.id) return
        close()
        mutable.value = DocumentPreviewState(scope = owner.owner, file = file, kind = documentPreviewKind(file))
        retry()
    }
    /** Root owns/revokes this capability. This viewer owns only its existing
     * reader work/PDF resources and never records an asset as an ordinary path. */
    fun openAsset(file: ResourceRef, lease: PreviewLease, sourceScope: String) {
        val owner = bound ?: return
        if (!isCurrent(owner) || sourceScope != owner.api.id || lease.scope != owner.api.id) return
        close()
        borrowedAsset = BorrowedAsset(owner, lease)
        mutable.value = DocumentPreviewState(scope = owner.owner, file = file, kind = documentPreviewKind(file))
        retry()
    }
    fun retry() {
        val owner = bound ?: return
        val before = mutable.value
        val file = before.file ?: return
        val asset = borrowedAsset
        if (!isCurrent(owner) || before.loading) return
        loading?.cancel(); rendering?.cancel(); renderRevision++
        bitmap?.close(); bitmap = null
        val old = pdf; pdf = null
        if (old != null) cleanup.launch { old.close() }
        val epoch = ++revision
        opened = false
        mutable.value = before.copy(loading = true, error = null, text = null, pageImage = null, pageError = null, rendering = false, pageCount = 0)
        loading = scope.launch {
            var lease: PreviewLease? = null
            var working: DocumentWorkingFile? = null
            var prepared: DocumentPdfSession? = null
            try {
                val wire: String
                val actual = if (asset == null) {
                    wire = documentWirePath(file)
                    val metadata = owner.api.request("GET", "/api/resources$wire?metadata=1")
                    check(!metadata.getBoolean("isDir") && resourceWireBytes(metadata.optString("wirePath").ifEmpty { SearchResult.encodePath(metadata.getString("path")) })
                        .contentEquals(resourceWireBytes(wire))) { "文件来源已变化，请从文件列表重新选择" }
                    file.copy(path = metadata.getString("path"), wirePath = wire, name = metadata.getString("name"),
                        size = metadata.getLong("size"), modified = metadata.optString("modified"), type = metadata.optString("type"))
                } else {
                    wire = ""
                    check(asset.owner === owner && asset.lease.scope == owner.api.id && before.scope == owner.owner) { "包内文件来源已切换" }
                    require(!file.directory && file.size >= 0) { "包内文件元数据无效" }
                    val uri = try { java.net.URI(asset.lease.url) } catch (_: java.net.URISyntaxException) { error("包内文件能力地址无效") }
                    require(uri.scheme == "http" && uri.host == "127.0.0.1" && uri.port > 0 && uri.rawUserInfo == null &&
                        uri.rawQuery == null && uri.rawFragment == null && uri.path?.startsWith("/stream/") == true) { "包内文件来源不是有效的本机能力地址" }
                    file
                }
                val kind = documentPreviewKind(actual)
                check(current(owner, epoch)) { "连接已切换" }
                mutable.value = mutable.value.copy(file = actual, kind = kind)
                if (kind == DocumentPreviewKind.OTHER) {
                    if (asset != null) check(owner.api.permissions().download) { "当前账号没有读取文件内容的权限" }
                    mutable.value = mutable.value.copy(loading = false); return@launch
                }
                check(owner.api.permissions().download) { "当前账号没有读取文件内容的权限" }
                val limit = if (kind == DocumentPreviewKind.TEXT) DOCUMENT_TEXT_LIMIT else DOCUMENT_PDF_LIMIT
                check(actual.size in 0..limit) { if (kind == DocumentPreviewKind.TEXT) "文本超过 10 MiB 阅读上限，请下载后打开" else "PDF 超过 64 MiB 查看上限，请下载后打开" }
                // ORIGINAL is the existing raw lease wrapper, with no image conversion.
                lease = if (asset == null) owner.api.image(actual.path, wire, ImageQuality.ORIGINAL)
                    else PreviewLease(asset.lease.url, asset.lease.scope) { /* Borrowed: root retains revocation ownership. */ }
                currentCoroutineContext().ensureActive()
                if (kind == DocumentPreviewKind.TEXT) {
                    val bytes = reader.text(lease, actual.size)
                    val text = withContext(Dispatchers.Default) { decodeDocumentText(bytes) }
                    if (current(owner, epoch)) {
                        mutable.value = mutable.value.copy(loading = false, text = text)
                        search(mutable.value.query)
                        acknowledge(owner, actual)
                    }
                } else {
                    working = reader.pdf(context, lease, actual.size)
                    withContext(Dispatchers.IO) { prepared = DocumentPdfSession(working!!) }
                    currentCoroutineContext().ensureActive()
                    check(prepared!!.pageCount > 0) { "PDF 没有可显示的页面" }
                    if (current(owner, epoch)) {
                        pdf = prepared; prepared = null; working = null
                        mutable.value = mutable.value.copy(loading = false, page = 0, pageCount = pdf!!.pageCount, zoom = 100)
                        renderPage()
                    }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (current(owner, epoch)) mutable.value = mutable.value.copy(loading = false,
                    error = if (error is SecurityException) "PDF 受密码保护或无法读取，请下载后使用合适的应用打开" else error.message ?: "文档无法读取，请重试或下载后打开")
            } finally {
                withContext(NonCancellable + Dispatchers.IO) {
                    runCatching { prepared?.close() }; runCatching { working?.close() }; runCatching { lease?.release() }
                }
            }
        }
    }
    private fun acknowledge(owner: SessionContext, file: ResourceRef) {
        if (opened || borrowedAsset != null) return
        opened = true
        try { onOpened(owner, file) } catch (_: Exception) { /* Recording cannot make a readable document fail. */ }
    }
    fun search(query: String) {
        searching?.cancel()
        val epoch = ++searchRevision
        val document = mutable.value.text
        mutable.value = mutable.value.copy(query = query, search = DocumentTextSearch(emptyList(), false), matchIndex = -1, searching = document != null && query.isNotEmpty(), searchError = null)
        if (document == null || query.isEmpty()) return
        val owner = bound ?: return
        val documentEpoch = revision
        searching = scope.launch {
            delay(150)
            try {
                val matches = withContext(Dispatchers.Default) { searchDocumentText(document.text, query) }
                if (current(owner, documentEpoch) && searchRevision == epoch) mutable.value = mutable.value.copy(search = matches,
                    matchIndex = if (matches.matches.isEmpty()) -1 else 0, searching = false)
            } catch (error: IllegalArgumentException) {
                if (current(owner, documentEpoch) && searchRevision == epoch) mutable.value = mutable.value.copy(searching = false, searchError = error.message)
            }
        }
    }
    fun nextMatch(direction: Int) {
        val before = mutable.value
        if (before.search.matches.isEmpty()) return
        mutable.value = before.copy(matchIndex = Math.floorMod(before.matchIndex + direction, before.search.matches.size))
    }
    fun viewport(pixels: Int) {
        val value = pixels.coerceIn(320, 2048)
        if (value == width) return
        width = value
        if (pdf != null) renderPage()
    }
    fun selectPage(index: Int) {
        if (index !in 0 until mutable.value.pageCount || pdf == null || index == mutable.value.page) return
        mutable.value = mutable.value.copy(page = index)
        renderPage()
    }
    fun zoom(percent: Int) {
        val value = percent.coerceIn(50, 200)
        if (value == mutable.value.zoom || pdf == null) return
        mutable.value = mutable.value.copy(zoom = value)
        renderPage()
    }
    fun retryPage() = renderPage()
    private fun renderPage() {
        val owner = bound ?: return
        val document = pdf ?: return
        val documentEpoch = revision
        val epoch = ++renderRevision
        val before = mutable.value
        rendering?.cancel(); bitmap?.close(); bitmap = null
        mutable.value = before.copy(pageImage = null, rendering = true, pageError = null)
        rendering = scope.launch {
            var result: DocumentPageBitmap? = null
            try {
                withContext(Dispatchers.IO) { result = document.render(before.page, width * before.zoom / 100, memoryClass) }
                currentCoroutineContext().ensureActive()
                if (current(owner, documentEpoch) && renderRevision == epoch && pdf === document) {
                    bitmap = result; result = null
                    mutable.value = mutable.value.copy(pageImage = bitmap, rendering = false)
                    mutable.value.file?.let { acknowledge(owner, it) }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (current(owner, documentEpoch) && renderRevision == epoch) mutable.value = mutable.value.copy(rendering = false, pageError = "这一页无法显示，请重试或下载后打开")
            } catch (_: OutOfMemoryError) {
                if (current(owner, documentEpoch) && renderRevision == epoch) mutable.value = mutable.value.copy(rendering = false, pageError = "本机内存不足，请缩小页面或下载后打开")
            } finally { result?.close() }
        }
    }
}
