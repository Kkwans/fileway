package io.github.kkwans.nasfilebrowser.app

import android.content.Context
import io.github.kkwans.nasfilebrowser.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class DocumentCreateDraft(val parent: DirectoryCrumb, val name: String = "", val content: String = "", val target: DirectoryCrumb? = null)
data class DocumentEditState(val scope: String = "", val file: ResourceRef? = null, val document: DocumentText? = null,
    val draft: String = "", val baseline: String = "", val creation: DocumentCreateDraft? = null, val loading: Boolean = false, val saving: Boolean = false,
    val conflict: Boolean = false, val unknownWrite: Boolean = false, val acknowledged: Boolean = false,
    val error: String? = null, val notice: String? = null) {
    val dirty get() = document != null && draft != baseline
}

class DocumentEditController internal constructor(private val context: Context, private val scope: CoroutineScope,
    private val isCurrent: (SessionContext) -> Boolean,
    private val onSaved: (SessionContext, ResourceRef) -> Unit = { _, _ -> },
    private val onCreated: (SessionContext, ResourceRef) -> Unit = { _, _ -> },
    private val reader: DocumentPreviewReader = NativeDocumentReader) {
    private val mutable = MutableStateFlow(DocumentEditState())
    val state = mutable.asStateFlow()
    private var bound: SessionContext? = null
    private var work: Job? = null
    private var revision = 0L
    fun bind(value: SessionContext?) {
        if (bound === value) return
        work?.cancel(); revision++; bound = value
        mutable.value = DocumentEditState(scope = value?.owner.orEmpty())
    }
    private fun current(owner: SessionContext, epoch: Long) = bound === owner && isCurrent(owner) && revision == epoch
    fun open(file: ResourceRef, document: DocumentText, sourceScope: String) {
        val owner = bound ?: return
        if (!isCurrent(owner) || owner.api.id != sourceScope || mutable.value.saving || mutable.value.creation != null) return
        documentWirePath(file)
        work?.cancel(); revision++
        val text = documentEditorText(document)
        mutable.value = DocumentEditState(scope = owner.owner, file = file, document = document, draft = text, baseline = text)
    }
    fun close(discard: Boolean = false) {
        if (mutable.value.saving || mutable.value.loading || mutable.value.dirty && !discard) return
        work?.cancel(); revision++
        mutable.value = DocumentEditState(scope = bound?.owner.orEmpty())
    }
    fun edit(value: String) {
        val before = mutable.value
        if (before.file == null || before.saving || before.loading || before.unknownWrite) return
        mutable.value = before.copy(draft = value, error = if (before.conflict) before.error else null, notice = null)
    }
    fun startCreate(parent: DirectoryCrumb, sourceScope: String) {
        val owner = bound ?: return
        if (!isCurrent(owner) || owner.api.id != sourceScope || mutable.value.file != null || mutable.value.saving || mutable.value.creation != null) return
        resourceWireBytes(requireNotNull(parent.wirePath))
        work?.cancel(); revision++
        mutable.value = DocumentEditState(scope = owner.owner, creation = DocumentCreateDraft(parent))
    }
    fun createName(value: String) {
        val before = mutable.value; val draft = before.creation ?: return
        if (!before.saving && !before.loading && !before.unknownWrite) mutable.value = before.copy(creation = draft.copy(name = value, target = null), error = null, notice = null)
    }
    fun createContent(value: String) {
        val before = mutable.value; val draft = before.creation ?: return
        if (!before.saving && !before.loading && !before.unknownWrite) mutable.value = before.copy(creation = draft.copy(content = value), error = null, notice = null)
    }
    fun closeCreation(verifyUnknown: Boolean = false) {
        if (mutable.value.saving || mutable.value.loading || mutable.value.unknownWrite && !verifyUnknown) return
        work?.cancel(); revision++
        mutable.value = DocumentEditState(scope = bound?.owner.orEmpty())
    }
    private suspend fun metadata(owner: SessionContext, file: ResourceRef): ResourceRef {
        val wire = documentWirePath(file)
        val row = owner.api.request("GET", "/api/resources$wire?metadata=1")
        check(!row.getBoolean("isDir") && resourceWireBytes(row.optString("wirePath").ifEmpty { SearchResult.encodePath(row.getString("path")) })
            .contentEquals(resourceWireBytes(wire))) { "文件来源已变化，请返回文件列表重新选择" }
        return file.copy(path = row.getString("path"), name = row.getString("name"), wirePath = wire, size = row.getLong("size"),
            modified = row.optString("modified"), type = row.optString("type"))
    }
    private suspend fun read(owner: SessionContext, file: ResourceRef): Pair<ResourceRef, ByteArray> {
        val actual = metadata(owner, file)
        check(owner.api.permissions().download) { "当前账号没有读取内容权限，无法核对结果" }
        require(actual.size in 0..DOCUMENT_TEXT_LIMIT) { "文件超过 10 MiB，无法在编辑器读取" }
        val lease = owner.api.image(actual.path, actual.wirePath, ImageQuality.ORIGINAL)
        try { return actual to reader.text(lease, actual.size) }
        finally { withContext(NonCancellable) { runCatching { lease.release() } } }
    }
    fun save() {
        val owner = bound ?: return
        val before = mutable.value; val file = before.file ?: return; val original = before.document ?: return
        if (!isCurrent(owner) || before.saving || before.loading || before.unknownWrite || before.conflict || !before.dirty) return
        val epoch = ++revision
        mutable.value = before.copy(saving = true, error = null, notice = null, acknowledged = false)
        work = scope.launch {
            var sending = false; var acknowledged = false
            try {
                check(owner.api.permissions().modify) { "当前账号没有修改权限" }
                val supported = try { owner.api.request("GET", "/api/client-capabilities").opt("conditionalTextSave") == true }
                    catch (error: ServiceException) { if (error.status == 404) false else throw error }
                check(supported) { "服务器不支持安全文本保存，请升级服务器；草稿已保留" }
                val bytes = withContext(Dispatchers.Default) { encodeEditedDocument(before.draft, original) }
                check(file.modified.isNotBlank() && file.size >= 0) { "缺少原文件版本，请重新读取后保存" }
                check(current(owner, epoch)) { "连接已切换" }
                sending = true
                owner.api.rawResource("PUT", documentWirePath(file), bytes, file.modified, file.size)
                acknowledged = true
                if (!current(owner, epoch)) return@launch
                val (actual, content) = read(owner, file)
                if (!current(owner, epoch)) return@launch
                if (!content.contentEquals(bytes)) {
                    mutable.value = before.copy(saving = false, conflict = true, acknowledged = true, error = "保存已获确认，但服务器内容随后变化；当前草稿已保留，请重新读取")
                    return@launch
                }
                val decoded = withContext(Dispatchers.Default) { decodeDocumentText(content) }
                if (current(owner, epoch)) {
                    val text = documentEditorText(decoded)
                    mutable.value = before.copy(file = actual, document = decoded, draft = text, baseline = text, saving = false, acknowledged = true, notice = "已保存并核对服务器内容")
                    runCatching { onSaved(owner, actual) }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (current(owner, epoch)) {
                    val unknown = sending && (acknowledged || error !is ServiceException || error.status !in setOf(400, 401, 403, 404, 409, 413))
                    mutable.value = before.copy(saving = false, conflict = error is ServiceException && error.status == 409,
                        unknownWrite = unknown, acknowledged = acknowledged,
                        error = if (unknown) { if (acknowledged) "保存已获确认，但无法读取新版本；请核对后继续" else "保存结果尚未确认；请先核对服务器内容，避免重复提交" }
                        else if (error is ServiceException && error.status == 409) "服务器文件已变化，草稿已保留；请重新读取后编辑"
                        else error.message ?: "保存失败，草稿已保留")
                }
            }
        }
    }
    fun reload() {
        val owner = bound ?: return
        val before = mutable.value; val file = before.file ?: return
        if (!isCurrent(owner) || before.loading || before.saving || before.unknownWrite) return
        val epoch = ++revision
        mutable.value = before.copy(loading = true, error = null, notice = null)
        work = scope.launch {
            try {
                val (actual, bytes) = read(owner, file)
                val decoded = withContext(Dispatchers.Default) { decodeDocumentText(bytes) }
                if (current(owner, epoch)) {
                    val text = documentEditorText(decoded)
                    mutable.value = before.copy(file = actual, document = decoded, draft = text, baseline = text, loading = false, conflict = false, notice = "已重新读取服务器版本")
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (current(owner, epoch)) mutable.value = before.copy(loading = false, error = error.message ?: "读取失败，原草稿仍保留")
            }
        }
    }
    fun verifySave() {
        val owner = bound ?: return
        val before = mutable.value; val file = before.file ?: return; val original = before.document ?: return
        if (!isCurrent(owner) || !before.unknownWrite || before.loading || before.saving) return
        val epoch = ++revision
        mutable.value = before.copy(loading = true, error = null)
        work = scope.launch {
            try {
                val expected = withContext(Dispatchers.Default) { encodeEditedDocument(before.draft, original) }
                val (actual, bytes) = read(owner, file)
                if (!current(owner, epoch)) return@launch
                if (bytes.contentEquals(expected)) {
                    val decoded = withContext(Dispatchers.Default) { decodeDocumentText(bytes) }
                    if (current(owner, epoch)) {
                        val text = documentEditorText(decoded)
                        mutable.value = before.copy(file = actual, document = decoded, draft = text, baseline = text, loading = false,
                            unknownWrite = false, conflict = false, acknowledged = true, error = null, notice = "已核对：服务器内容与当前草稿一致")
                        runCatching { onSaved(owner, actual) }
                    }
                } else mutable.value = before.copy(loading = false, unknownWrite = false, conflict = true, error = "服务器内容与草稿不同；草稿已保留，请重新读取后编辑")
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (current(owner, epoch)) mutable.value = before.copy(loading = false, error = error.message ?: "仍无法核对，请恢复连接后重试")
            }
        }
    }
    fun create() {
        val owner = bound ?: return
        val before = mutable.value; val draft = before.creation ?: return
        if (!isCurrent(owner) || before.saving || before.loading || before.unknownWrite) return
        val epoch = ++revision
        mutable.value = before.copy(saving = true, error = null, notice = null)
        work = scope.launch {
            var sending = false; var acknowledged = false; var target: DirectoryCrumb? = null
            try {
                check(owner.api.permissions().create) { "当前账号没有创建文件权限" }
                target = createdDocumentTarget(draft.parent, draft.name)
                val bytes = withContext(Dispatchers.Default) { encodeCreatedDocument(draft.content) }
                val parent = owner.api.request("GET", "/api/resources${draft.parent.wirePath}?metadata=1")
                check(parent.getBoolean("isDir") && resourceWireBytes(parent.optString("wirePath").ifEmpty { SearchResult.encodePath(parent.getString("path")) })
                    .contentEquals(resourceWireBytes(draft.parent.wirePath!!))) { "原目录已变化，请返回并刷新" }
                check(current(owner, epoch)) { "连接已切换" }; sending = true
                owner.api.rawResource("POST", target.wirePath!!, bytes)
                acknowledged = true
                if (!current(owner, epoch)) return@launch
                val file = metadata(owner, ResourceRef(target.path, target.wirePath!!, target.label, false, "text", bytes.size.toLong()))
                check(file.size == bytes.size.toLong()) { "创建已确认，但文件大小随后变化，请核对原目录" }
                if (current(owner, epoch)) {
                    mutable.value = DocumentEditState(scope = owner.owner, notice = "已创建文件：${file.name}")
                    runCatching { onCreated(owner, file) }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (current(owner, epoch)) {
                    val unknown = sending && (acknowledged || error !is ServiceException || error.status !in setOf(400, 401, 403, 404, 409, 413))
                    mutable.value = before.copy(saving = false, creation = draft.copy(target = target), unknownWrite = unknown, acknowledged = acknowledged,
                        error = if (unknown) "创建结果待核对，请勿再次提交同名文件"
                        else if (error is ServiceException && error.status == 409) "同名文件或文件夹已存在，未覆盖；请修改名称"
                        else error.message ?: "创建失败，输入已保留")
                }
            }
        }
    }
    fun verifyCreation() {
        val owner = bound ?: return
        val before = mutable.value; val draft = before.creation ?: return; val target = draft.target ?: return
        if (!isCurrent(owner) || !before.unknownWrite || before.loading || before.saving) return
        val epoch = ++revision
        mutable.value = before.copy(loading = true, error = null)
        work = scope.launch {
            try {
                val expected = withContext(Dispatchers.Default) { encodeCreatedDocument(draft.content) }
                val (actual, bytes) = read(owner, ResourceRef(target.path, target.wirePath!!, target.label, false, "text", expected.size.toLong()))
                check(bytes.contentEquals(expected)) { "服务器已有不同内容，请返回原目录核对并使用其他名称" }
                if (current(owner, epoch)) {
                    mutable.value = DocumentEditState(scope = owner.owner, notice = "已核对服务器文件内容一致")
                    runCatching { onCreated(owner, actual) }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (current(owner, epoch)) mutable.value = before.copy(loading = false, error = error.message ?: "仍无法核对，请返回原目录查看")
            }
        }
    }
}
