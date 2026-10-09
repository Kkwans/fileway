package io.github.kkwans.nasfilebrowser.app

import io.github.kkwans.nasfilebrowser.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class FileOperationsState(val scope: String = "", val changing: Boolean = false,
    val error: String? = null, val notice: String? = null, val transfer: FileTransferDraft? = null,
    val lastTask: ServerTask? = null, val lastDestination: DirectoryCrumb? = null, val lastSources: List<ResourceRef> = emptyList(), val taskError: String? = null,
    val creation: DirectoryCreateDraft? = null, val batchRename: BatchRenameDraft? = null,
    val batchCompletion: Long = 0, val lastBatchSources: List<ResourceRef> = emptyList())
data class BatchRenameDraft(val files: List<ResourceRef>, val parent: DirectoryCrumb, val options: BatchRenameOptions = BatchRenameOptions(),
    val overrides: Map<String, String> = emptyMap(), val reviewed: Boolean = false, val legacy: Boolean = false,
    val serverErrors: Map<String, String> = emptyMap(), val error: String? = null, val unknownExecution: Boolean = false,
    val reviewedChanges: List<BatchRenameChange> = emptyList()) {
    val rows get() = batchRenameRows(files, options, overrides)
}
data class DirectoryCreateDraft(val parent: DirectoryCrumb, val name: String = "", val error: String? = null,
    val existing: DirectoryCrumb? = null, val unknownTarget: DirectoryCrumb? = null)
data class FileTransferDraft(val files: List<ResourceRef>, val action: FileTransferAction, val directory: DirectoryCrumb,
    val directories: List<ResourceRef> = emptyList(), val loading: Boolean = false, val error: String? = null,
    val reviewed: Boolean = false, val conflicts: Set<String> = emptySet(), val choices: Map<String, FileConflictChoice> = emptyMap(),
    val unknownSubmission: Boolean = false, val visible: Boolean = true, val wireOperations: Boolean = false)
private data class PendingFileTransfer(val task: ServerTask, val sources: List<ResourceRef>)

/** Existing resource mutations stay bound to their original server/account. */
class FileOperationsController(private val scope: CoroutineScope, private val isCurrent: (SessionContext) -> Boolean,
    private val onRenamed: (SessionContext, ResourceRef, RenameTarget) -> Unit,
    private val onTransferFinished: (SessionContext, ServerTask, List<ResourceRef>) -> Unit = { _, _, _ -> },
    private val onDirectoryReady: (SessionContext, DirectoryCrumb) -> Unit = { _, _ -> },
    private val onBatchRenamed: (SessionContext, List<BatchRenameChange>) -> Unit = { _, _ -> }) {
    private val mutable = MutableStateFlow(FileOperationsState())
    val state = mutable.asStateFlow()
    private var bound: SessionContext? = null
    private var write: Job? = null
    private var directoryRead: Job? = null
    private var directoryEpoch = 0L
    private var batchEpoch = 0L
    private var poll: Job? = null
    private var visible = false
    private val pendingTasks = linkedMapOf<String, PendingFileTransfer>()
    fun bind(context: SessionContext?) {
        write?.cancel(); directoryRead?.cancel(); directoryEpoch++; batchEpoch++; poll?.cancel(); visible = false
        pendingTasks.clear(); bound = context
        mutable.value = FileOperationsState(scope = context?.owner.orEmpty())
    }
    private fun current(context: SessionContext) = bound === context && isCurrent(context)
    fun startTransfer(files: List<ResourceRef>, action: FileTransferAction, directory: DirectoryCrumb) {
        val context = bound ?: return
        if (!current(context) || mutable.value.changing || mutable.value.transfer != null || mutable.value.creation != null || mutable.value.batchRename != null) return
        directoryRead?.cancel(); directoryEpoch++
        mutable.value = mutable.value.copy(error = null, notice = null, transfer = FileTransferDraft(files.toList(), action, directory))
        readTransferDirectory(directory)
    }
    fun closeTransfer() {
        if (mutable.value.changing) return
        directoryRead?.cancel(); directoryEpoch++
        mutable.value = mutable.value.copy(transfer = null)
    }
    fun showTransfer(value: Boolean) {
        val draft = mutable.value.transfer ?: return
        if (!mutable.value.changing) mutable.value = mutable.value.copy(transfer = draft.copy(visible = value))
    }
    fun readTransferDirectory(directory: DirectoryCrumb) {
        val context = bound ?: return
        val draft = mutable.value.transfer ?: return
        if (!current(context) || mutable.value.changing || draft.unknownSubmission) return
        directoryRead?.cancel(); val epoch = ++directoryEpoch
        mutable.value = mutable.value.copy(transfer = draft.copy(directory = directory, directories = if (directory == draft.directory) draft.directories else emptyList(),
            loading = true, error = null, reviewed = false, conflicts = emptySet(), choices = emptyMap()))
        directoryRead = scope.launch {
            try {
                val wire = taskResourceTarget(directory.path, requireNotNull(directory.wirePath) { "目标目录无法安全访问" }, allowOpaque = true).wirePath
                val data = context.api.request("GET", "/api/resources$wire?metadata=1")
                check(data.optBoolean("isDir") && taskResourceAcknowledged(directory.path, wire, data.getString("path"), data.optString("wirePath"),
                    data.opt("pathVerified") != false)) { "目标目录原始路径无法确认，请刷新或升级服务器" }
                val rows = context.api.request("GET", "/api/resources$wire").getJSONArray("items")
                val folders = (0 until rows.length()).map { rows.getJSONObject(it) }.filter { it.optBoolean("isDir") }.map {
                    ResourceRef(it.getString("path"), it.optString("wirePath").ifEmpty { SearchResult.encodePath(it.getString("path")) },
                        it.getString("name"), true, "", it.optLong("size"), it.optString("modified"))
                }.sortedBy { it.name.lowercase() }
                if (current(context) && directoryEpoch == epoch) mutable.value = mutable.value.copy(transfer = mutable.value.transfer?.copy(directories = folders, loading = false))
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (current(context) && directoryEpoch == epoch) mutable.value = mutable.value.copy(transfer = mutable.value.transfer?.copy(loading = false, error = error.message ?: "目录读取失败，请重试"))
            }
        }
    }
    fun chooseConflict(sourceKey: String, value: FileConflictChoice) {
        val draft = mutable.value.transfer ?: return
        if (mutable.value.changing || sourceKey !in draft.conflicts) return
        mutable.value = mutable.value.copy(transfer = draft.copy(choices = draft.choices + (sourceKey to value)))
    }
    fun acknowledgeUnknownSubmission() {
        val draft = mutable.value.transfer ?: return
        mutable.value = mutable.value.copy(transfer = draft.copy(unknownSubmission = false, reviewed = false, error = null))
    }
    fun submitTransfer(replaceConfirmed: Boolean = false) {
        val context = bound ?: return
        val draft = mutable.value.transfer ?: return
        if (!current(context) || mutable.value.changing || draft.loading || draft.unknownSubmission) return
        mutable.value = mutable.value.copy(changing = true, transfer = draft.copy(error = null))
        write = scope.launch {
            var sending = false
            var accepted: ServerTask? = null
            try {
                val permissions = context.api.permissions()
                check(permissions.create && (draft.action == FileTransferAction.COPY || permissions.rename)) { "当前账号没有${draft.action.label}权限" }
                val entries = fileTransferEntries(draft.files, draft.directory, draft.action, allowOpaque = true)
                val wireOperations = if (entries.any { !it.legacyCompatible }) {
                    if (draft.reviewed) check(draft.wireOperations) { "服务器原始路径操作能力未确认，请重新检查" }
                    else {
                        val supported = try { context.api.clientCapabilities().resourceWireOperations }
                        catch (error: ServiceException) {
                            if (error.status != 404) throw error
                            throw IllegalStateException("此服务器未提供原始路径操作能力，请升级服务器；未提交操作", error)
                        }
                        check(supported) { "此服务器不支持原始路径复制／移动，请升级服务器；未提交操作" }
                    }
                    true
                } else draft.wireOperations
                currentCoroutineContext().ensureActive()
                check(current(context)) { "连接已切换" }
                if (!draft.reviewed) {
                    val paths = (entries.map { it.file.path to it.sourceWire } + entries.map { it.targetPath to it.targetWire }).distinctBy { it.second }
                    val metadata = linkedMapOf<String, JSONObject>()
                    for (chunk in paths.chunked(500)) {
                        check(current(context)) { "连接已切换" }
                        val rows = context.api.resourceBatch(chunk.map { it.first }, chunk.map { it.second })
                        check(rows.length() == chunk.size) { "资源检查结果不完整，请重试" }
                        for (i in chunk.indices) {
                            val row = rows.getJSONObject(i); val (path, wire) = chunk[i]
                            check(taskResourceAcknowledged(path, wire, row.getString("path"), row.optString("wirePath"), row.opt("pathVerified") != false)) {
                                "资源检查原始路径不匹配，请刷新或升级服务器"
                            }
                            metadata[wire] = row
                        }
                    }
                    for (entry in entries) {
                        val row = metadata.getValue(entry.sourceWire)
                        check(row.getInt("status") == 200) { "${entry.file.name} 已不可用，请刷新后重新选择" }
                        val item = row.getJSONObject("item")
                        check(item.getBoolean("isDir") == entry.file.directory && (entry.file.directory || item.getLong("size") == entry.file.size) &&
                            (entry.file.modified.isEmpty() || item.optString("modified") == entry.file.modified)) { "${entry.file.name} 已变化，请刷新后重新选择" }
                        check(taskResourceAcknowledged(entry.file.path, entry.sourceWire, item.getString("path"), item.optString("wirePath"),
                            item.opt("pathVerified") != false)) { "文件原始路径已变化，请刷新或升级服务器" }
                    }
                    val conflicts = entries.filter { entry ->
                        val row = metadata.getValue(entry.targetWire)
                        check(row.getInt("status") in setOf(200, 404)) { "目标路径无法访问，请选择其他目录" }
                        if (row.getInt("status") == 200) {
                            val item = row.getJSONObject("item")
                            check(taskResourceAcknowledged(entry.targetPath, entry.targetWire, item.getString("path"), item.optString("wirePath"),
                                item.opt("pathVerified") != false)) { "同名目标原始路径不匹配，请刷新或升级服务器" }
                        }
                        row.getInt("status") == 200
                    }.map { it.file.mediaKey }.toSet()
                    if (!current(context)) return@launch
                    mutable.value = mutable.value.copy(changing = false, transfer = draft.copy(reviewed = true, conflicts = conflicts,
                        choices = conflicts.associateWith { FileConflictChoice.KEEP_BOTH }, wireOperations = wireOperations))
                    return@launch
                }
                val selected = entries.filter { draft.choices[it.file.mediaKey] != FileConflictChoice.SKIP }
                require(selected.isNotEmpty()) { "所有项目都已跳过，请调整选择" }
                check(selected.none { draft.choices[it.file.mediaKey] == FileConflictChoice.REPLACE } || permissions.modify) { "替换现有内容需要修改权限" }
                check(selected.none { draft.choices[it.file.mediaKey] == FileConflictChoice.REPLACE } || replaceConfirmed) { "请先确认替换同名目标内容" }
                check(selected.none { entry -> draft.choices[entry.file.mediaKey] == FileConflictChoice.REPLACE && entries.any {
                    resourceWireContains(entry.targetWire, it.sourceWire)
                } }) { "目标包含本次源项目，不能替换，请保留两份或跳过" }
                check(selected.none { it.sourceWire == it.targetWire && draft.choices[it.file.mediaKey] != FileConflictChoice.KEEP_BOTH }) { "源和目标相同，只能保留两份或跳过" }
                val body = JSONObject().put("action", draft.action.wire).put("items", JSONArray(selected.map { entry ->
                    JSONObject().apply {
                        if (wireOperations) put("fromWirePath", entry.sourceWire).put("toWirePath", entry.targetWire)
                        if (entry.legacyCompatible) put("from", "/files" + entry.sourceWire).put("to", "/files" + entry.targetWire)
                    }.put("name", entry.file.name).put("size", entry.file.size)
                        .put("modified", entry.file.modified).put("isDir", entry.file.directory)
                        .put("overwrite", draft.choices[entry.file.mediaKey] == FileConflictChoice.REPLACE)
                        .put("rename", draft.choices[entry.file.mediaKey] == FileConflictChoice.KEEP_BOTH)
                }))
                currentCoroutineContext().ensureActive()
                check(current(context)) { "连接已切换" }; sending = true
                val task = ServerTask.from(context.api.action("POST", "/api/resources/transfer", body))
                check(task.userId == context.account.userId && task.type == "file.${draft.action.wire}") { "服务器返回的任务来源不匹配" }
                accepted = task
                if (!current(context)) return@launch
                val sources = selected.map { it.file }
                mutable.value = mutable.value.copy(changing = false, transfer = null, lastTask = task, lastDestination = draft.directory, lastSources = sources,
                    taskError = null, notice = "${draft.action.label}任务已提交，将在服务器上继续执行")
                if (task.active) pendingTasks[task.id] = PendingFileTransfer(task, sources) else onTransferFinished(context, task, sources)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (current(context)) {
                    if (accepted != null) {
                        mutable.value = mutable.value.copy(changing = false, transfer = null, error = "任务已提交，请到任务中心查看实际状态")
                        return@launch
                    }
                    val unknown = sending && (error !is ServiceException || error.status >= 500)
                    mutable.value = mutable.value.copy(changing = false, transfer = draft.copy(
                        reviewed = draft.reviewed && (error !is ServiceException || error.status != 409), unknownSubmission = unknown,
                        error = if (unknown) "未能确认任务是否已创建，请先到任务中心核对，避免重复提交" else error.message ?: "操作失败，请重试"))
                }
            }
        }
    }
    fun setVisible(value: Boolean) {
        if (visible == value) return
        visible = value; poll?.cancel()
        if (!value) return
        poll = scope.launch {
            while (isActive) {
                var failed = false
                val context = bound
                if (context != null && current(context)) for (old in pendingTasks.values.toList()) {
                    try {
                        val task = ServerTask.from(context.api.request("GET", "/api/tasks/${android.net.Uri.encode(old.task.id)}"))
                        check(task.id == old.task.id && task.userId == context.account.userId && task.type == old.task.type) { "任务来源不匹配" }
                        if (!current(context)) break
                        if (mutable.value.lastTask?.id == task.id) mutable.value = mutable.value.copy(lastTask = task, taskError = null)
                        if (task.active) pendingTasks[task.id] = old.copy(task = task) else { pendingTasks.remove(task.id); onTransferFinished(context, task, old.sources) }
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        if (current(context) && mutable.value.lastTask?.id == old.task.id) mutable.value = mutable.value.copy(taskError = "任务状态暂时不可用，已保留提交记录，请到任务中心查看")
                        failed = true
                    }
                }
                delay(if (failed) 10_000 else 2000)
            }
        }
    }
    fun dismissTaskNotice() {
        mutable.value = mutable.value.copy(lastTask = null, lastDestination = null, lastSources = emptyList(), taskError = null, notice = null)
    }
    fun startDirectoryCreation(parent: DirectoryCrumb) {
        val context = bound ?: return
        if (!current(context) || mutable.value.changing || mutable.value.transfer != null || mutable.value.creation != null || mutable.value.batchRename != null) return
        mutable.value = mutable.value.copy(creation = DirectoryCreateDraft(parent), error = null, notice = null)
    }
    fun directoryName(value: String) {
        val draft = mutable.value.creation ?: return
        if (!mutable.value.changing && draft.unknownTarget == null)
            mutable.value = mutable.value.copy(creation = draft.copy(name = value, error = null, existing = null))
    }
    fun closeDirectoryCreation() {
        if (!mutable.value.changing) mutable.value = mutable.value.copy(creation = null)
    }
    private suspend fun readCreationTarget(context: SessionContext, target: DirectoryCrumb): org.json.JSONObject? {
        return try {
            val item = context.api.request("GET", "/api/resources${target.wirePath}?metadata=1")
            check(item.getString("path") == target.path && resourceWireBytes(item.optString("wirePath").ifEmpty { SearchResult.encodePath(target.path) })
                .contentEquals(resourceWireBytes(target.wirePath!!))) { "目录来源已变化，请刷新核对" }
            item
        } catch (error: ServiceException) { if (error.status == 404) null else throw error }
    }
    private fun creationReady(context: SessionContext, target: DirectoryCrumb) {
        if (!current(context)) return
        mutable.value = mutable.value.copy(changing = false, creation = null, error = null, notice = "文件夹已就绪：${target.label}")
        onDirectoryReady(context, target)
    }
    fun createDirectory() {
        val context = bound ?: return
        val draft = mutable.value.creation ?: return
        if (!current(context) || mutable.value.changing || draft.unknownTarget != null) return
        mutable.value = mutable.value.copy(changing = true, creation = draft.copy(error = null, existing = null))
        write = scope.launch {
            var target: DirectoryCrumb? = null
            var sending = false
            var acknowledged = false
            try {
                target = directoryCreationTarget(draft.parent, draft.name)
                check(context.api.permissions().create) { "当前账号没有创建权限" }
                val parent = readCreationTarget(context, draft.parent)
                check(parent?.getBoolean("isDir") == true) { "父目录已不可用，请退出并刷新后再试" }
                val existing = readCreationTarget(context, target)
                if (existing != null) {
                    if (current(context)) mutable.value = mutable.value.copy(changing = false, creation = draft.copy(
                        error = if (existing.getBoolean("isDir")) "同名文件夹已存在，可修改名称或直接打开" else "已有同名文件，请修改名称",
                        existing = target.takeIf { existing.getBoolean("isDir") }))
                    return@launch
                }
                check(current(context)) { "连接已切换" }; sending = true
                context.api.action("POST", "/api/resources${target.wirePath}/?override=false")
                acknowledged = true
                check(readCreationTarget(context, target)?.getBoolean("isDir") == true) { "创建请求已受理，暂时无法确认目录，请核对" }
                creationReady(context, target)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (!current(context)) return@launch
                val uncertain = sending && (acknowledged || error !is ServiceException || error.status >= 500)
                if (uncertain && target != null) {
                    try {
                        val actual = readCreationTarget(context, target)
                        if (!current(context)) return@launch
                        if (actual?.getBoolean("isDir") == true) { creationReady(context, target); return@launch }
                        mutable.value = mutable.value.copy(changing = false, creation = draft.copy(error =
                            if (actual != null) "目标已有同名文件，请修改名称" else "未找到目标文件夹，输入已保留，可重试"))
                        return@launch
                    } catch (readError: Exception) { if (readError is CancellationException) throw readError }
                }
                if (current(context)) mutable.value = mutable.value.copy(changing = false, creation = draft.copy(
                    unknownTarget = target.takeIf { uncertain }, error = if (uncertain) "无法确认创建结果，请先核对目标文件夹" else error.message ?: "创建失败，输入已保留"))
            }
        }
    }
    fun checkDirectoryCreation() {
        val context = bound ?: return
        val draft = mutable.value.creation ?: return
        val target = draft.unknownTarget ?: return
        if (!current(context) || mutable.value.changing) return
        mutable.value = mutable.value.copy(changing = true)
        write = scope.launch {
            try {
                val actual = readCreationTarget(context, target)
                if (!current(context)) return@launch
                if (actual?.getBoolean("isDir") == true) creationReady(context, target)
                else mutable.value = mutable.value.copy(changing = false, creation = draft.copy(unknownTarget = null,
                    error = if (actual == null) "未找到目标文件夹，可重试" else "目标已有同名文件，请修改名称"))
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (current(context)) mutable.value = mutable.value.copy(changing = false, creation = draft.copy(error = "仍无法核对创建结果，请恢复连接后重试"))
            }
        }
    }
    fun rename(file: ResourceRef, name: String, done: () -> Unit = {}) {
        val context = bound ?: return
        if (!current(context) || mutable.value.changing || mutable.value.creation != null || mutable.value.transfer != null || mutable.value.batchRename != null) return
        mutable.value = mutable.value.copy(changing = true, error = null, notice = null)
        write = scope.launch {
            var applied = false
            try {
                val target = renameTarget(file, name)
                check(context.api.permissions().rename) { "当前账号没有重命名权限" }
                check(current(context)) { "连接已切换，操作已停止" }
                val wire = file.wirePath.ifEmpty { io.github.kkwans.nasfilebrowser.data.SearchResult.encodePath(file.path) }
                context.api.action("PATCH", "/api/resources$wire?action=rename&destination=${target.destinationQuery}&override=false&rename=false")
                applied = true
                if (!current(context)) return@launch
                onRenamed(context, file, target)
                if (!current(context)) return@launch
                mutable.value = mutable.value.copy(changing = false, notice = "已重命名为 ${target.name}")
                done()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (current(context)) {
                    mutable.value = mutable.value.copy(changing = false,
                        error = if (applied) "重命名已保存，请刷新查看实际状态" else error.message ?: "重命名失败，请重试")
                    if (applied) done()
                }
            }
        }
    }

    fun startBatchRename(files: List<ResourceRef>, parent: DirectoryCrumb, sourceScope: String) {
        val context = bound ?: return
        val before = mutable.value
        if (!current(context) || context.api.id != sourceScope || before.changing || before.transfer != null || before.creation != null || before.batchRename != null) return
        try {
            val snapshot = files.toList()
            batchRenameRows(snapshot, BatchRenameOptions())
            val selectedParent = batchRenameParent(snapshot.first())
            require(parent.path == selectedParent.path && resourceWireBytes(requireNotNull(parent.wirePath))
                .contentEquals(resourceWireBytes(selectedParent.wirePath!!))) { "所选目录已变化，请刷新后重新选择" }
            batchEpoch++
            mutable.value = before.copy(batchRename = BatchRenameDraft(snapshot, parent), error = null, notice = null)
        } catch (error: Exception) {
            mutable.value = before.copy(error = error.message ?: "无法开始批量重命名，请刷新后重新选择")
        }
    }
    fun closeBatchRename(verifyUnknown: Boolean = false) {
        val draft = mutable.value.batchRename ?: return
        if (mutable.value.changing || draft.unknownExecution && !verifyUnknown) return
        batchEpoch++
        mutable.value = mutable.value.copy(batchRename = null)
    }
    fun batchRenameOptions(value: BatchRenameOptions) {
        val draft = mutable.value.batchRename ?: return
        if (mutable.value.changing || draft.unknownExecution || draft.options == value) return
        batchEpoch++
        mutable.value = mutable.value.copy(batchRename = draft.copy(options = value, reviewed = false, legacy = false,
            reviewedChanges = emptyList(), serverErrors = emptyMap(), error = null))
    }
    fun batchRenameName(wire: String, name: String) {
        val draft = mutable.value.batchRename ?: return
        if (mutable.value.changing || draft.unknownExecution || draft.files.none { batchRenameSourceWire(it) == wire }) return
        batchEpoch++
        mutable.value = mutable.value.copy(batchRename = draft.copy(overrides = draft.overrides + (wire to name),
            reviewed = false, legacy = false, reviewedChanges = emptyList(), serverErrors = emptyMap(), error = null))
    }
    fun resetBatchRenameNames() {
        val draft = mutable.value.batchRename ?: return
        if (mutable.value.changing || draft.unknownExecution) return
        batchEpoch++
        mutable.value = mutable.value.copy(batchRename = draft.copy(overrides = emptyMap(), reviewed = false, legacy = false,
            reviewedChanges = emptyList(), serverErrors = emptyMap(), error = null))
    }
    private fun batchBody(changes: List<BatchRenameChange>, legacy: Boolean, dryRun: Boolean): JSONObject = JSONObject()
        .put("dryRun", dryRun).put("items", JSONArray(changes.map { change ->
            if (legacy) JSONObject().put("from", change.file.path).put("to", change.target.path)
            else JSONObject().put("fromWirePath", batchRenameSourceWire(change.file)).put("toWirePath", change.target.wirePath)
        }))
    private data class BatchReply(val changes: List<BatchRenameChange>, val errors: Map<String, String>, val error: String?)
    private class BatchExecutionReported : IllegalStateException("服务器在检查时报告了执行，请刷新原目录核对")
    private fun batchReply(response: JSONObject, changes: List<BatchRenameChange>, legacy: Boolean, execute: Boolean): BatchReply {
        val valid = response.get("valid") as? Boolean ?: error("服务器返回了无效检查结果")
        val executed = response.get("executed") as? Boolean ?: error("服务器未确认执行状态")
        if (!execute && executed) throw BatchExecutionReported()
        check(executed == execute) { "服务器返回的执行状态不匹配，请核对原目录" }
        val items = response.getJSONArray("items")
        check(items.length() == changes.size) { "服务器返回的变更项目不完整，请核对原目录" }
        val errors = linkedMapOf<String, String>()
        val acknowledged = changes.mapIndexed { index, change ->
            val row = items.getJSONObject(index)
            val from = row.get("from") as? String ?: error("服务器返回的源路径无效")
            val to = row.get("to") as? String ?: error("服务器返回的目标路径无效")
            val wire = batchRenameSourceWire(change.file)
            if (legacy) check(from == change.file.path && to == change.target.path) { "服务器返回的变更来源不匹配" }
            else {
                check(resourceWireBytes(row.getString("fromWirePath")).contentEquals(resourceWireBytes(wire)) &&
                    resourceWireBytes(row.getString("toWirePath")).contentEquals(resourceWireBytes(change.target.wirePath))) { "服务器返回的原始路径不匹配" }
                check(from == change.file.path && to.startsWith('/') && to.substringBeforeLast('/') == change.target.path.substringBeforeLast('/')) { "服务器返回的显示路径不匹配" }
                check(renameNameError(to.substringAfterLast('/')) == null) { "服务器返回的新名称无效" }
            }
            val status = row.getString("status")
            check(status == (if (execute) "completed" else "ready") || !execute && status == "error") { "服务器返回的项目状态不匹配" }
            val message = row.optString("error")
            if (status == "error") errors[wire] = message.ifEmpty { "这个项目未通过检查" }
            else check(message.isEmpty()) { "服务器返回的项目确认包含错误" }
            change.copy(target = change.target.copy(path = to, name = to.substringAfterLast('/')))
        }
        val message = response.optString("error").takeIf { it.isNotEmpty() }
        check(valid == (errors.isEmpty() && message == null)) { "服务器返回的检查状态不一致" }
        if (execute) check(valid && errors.isEmpty()) { "服务器未确认所有项目已重命名" }
        return BatchReply(acknowledged, errors, message)
    }
    fun checkBatchRename() {
        val context = bound ?: return
        val draft = mutable.value.batchRename ?: return
        if (!current(context) || mutable.value.changing || draft.unknownExecution) return
        val epoch = ++batchEpoch
        mutable.value = mutable.value.copy(changing = true, batchRename = draft.copy(reviewed = false, reviewedChanges = emptyList(), serverErrors = emptyMap(), error = null))
        write = scope.launch {
            try {
                check(context.api.permissions().rename) { "当前账号没有重命名权限" }
                val rows = draft.rows
                require(rows.none { it.error != null }) { "请先修正预览中的名称错误" }
                val changes = rows.filter { it.changed }.map { BatchRenameChange(it.file, it.target!!) }
                require(changes.isNotEmpty()) { "名称没有变化，请先设置命名规则或调整新名称" }
                check(current(context) && batchEpoch == epoch) { "连接已切换" }
                var legacy = false
                val response = try { context.api.request("POST", "/api/resources/batch-rename", batchBody(changes, false, true)) }
                catch (error: ServiceException) {
                    if (error.status != 400) throw error
                    check(batchRenameLegacySafe(changes)) { "此服务器不支持原始路径批量重命名，请升级服务器后重试；未提交执行" }
                    check(current(context) && batchEpoch == epoch) { "连接已切换" }
                    legacy = true
                    context.api.request("POST", "/api/resources/batch-rename", batchBody(changes, true, true))
                }
                val reply = batchReply(response, changes, legacy, false)
                if (current(context) && batchEpoch == epoch) mutable.value = mutable.value.copy(changing = false,
                    batchRename = draft.copy(reviewed = reply.errors.isEmpty() && reply.error == null, legacy = legacy, reviewedChanges = reply.changes,
                        serverErrors = reply.errors, error = reply.error ?: if (reply.errors.isEmpty()) null else "存在冲突，请调整名称后重新检查"))
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (current(context) && batchEpoch == epoch) mutable.value = mutable.value.copy(changing = false,
                    batchRename = draft.copy(reviewed = false, reviewedChanges = emptyList(), unknownExecution = error is BatchExecutionReported,
                        error = error.message ?: "无法完成检查，输入已保留，请重试"))
            }
        }
    }
    fun executeBatchRename() {
        val context = bound ?: return
        val draft = mutable.value.batchRename ?: return
        if (!current(context) || mutable.value.changing || !draft.reviewed || draft.unknownExecution || draft.reviewedChanges.isEmpty()) return
        val epoch = ++batchEpoch
        mutable.value = mutable.value.copy(changing = true, batchRename = draft.copy(error = null))
        write = scope.launch {
            var sending = false
            try {
                check(context.api.permissions().rename) { "当前账号没有重命名权限" }
                check(current(context) && batchEpoch == epoch) { "连接已切换" }
                sending = true
                val response = context.api.request("POST", "/api/resources/batch-rename", batchBody(draft.reviewedChanges, draft.legacy, false))
                val reply = batchReply(response, draft.reviewedChanges, draft.legacy, true)
                if (!current(context) || batchEpoch != epoch) return@launch
                mutable.value = mutable.value.copy(changing = false, batchRename = null, batchCompletion = mutable.value.batchCompletion + 1,
                    lastBatchSources = reply.changes.map { it.file }, error = null, notice = "已重命名 ${reply.changes.size} 项")
                // One atomic callback is essential for swaps; sequential rewrites corrupt references.
                try { onBatchRenamed(context, reply.changes) } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    if (current(context)) mutable.value = mutable.value.copy(error = "重命名已完成，本地关联刷新失败，请刷新原目录核对")
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (current(context) && batchEpoch == epoch) {
                    val unknown = sending && (error !is ServiceException || error.status !in setOf(400, 401, 403, 404, 409))
                    mutable.value = mutable.value.copy(changing = false, batchRename = draft.copy(reviewed = false, reviewedChanges = emptyList(),
                        unknownExecution = unknown, error = if (unknown) "无法确认哪些名称已生效。请刷新原目录核对，避免重复执行。"
                        else if (error is ServiceException && error.status == 409) "名称或磁盘状态已变化，请重新检查后确认"
                        else error.message ?: "执行被拒绝，输入已保留，请重新检查"))
                }
            }
        }
    }
}
