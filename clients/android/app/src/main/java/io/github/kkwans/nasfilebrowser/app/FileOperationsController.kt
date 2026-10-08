package io.github.kkwans.nasfilebrowser.app

import io.github.kkwans.nasfilebrowser.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class FileOperationsState(val scope: String = "", val changing: Boolean = false,
    val error: String? = null, val notice: String? = null, val transfer: FileTransferDraft? = null,
    val lastTask: ServerTask? = null, val lastDestination: DirectoryCrumb? = null, val lastSources: List<ResourceRef> = emptyList(), val taskError: String? = null)
data class FileTransferDraft(val files: List<ResourceRef>, val action: FileTransferAction, val directory: DirectoryCrumb,
    val directories: List<ResourceRef> = emptyList(), val loading: Boolean = false, val error: String? = null,
    val reviewed: Boolean = false, val conflicts: Set<String> = emptySet(), val choices: Map<String, FileConflictChoice> = emptyMap(),
    val unknownSubmission: Boolean = false, val visible: Boolean = true)
private data class PendingFileTransfer(val task: ServerTask, val sources: List<ResourceRef>)

/** Existing resource mutations stay bound to their original server/account. */
class FileOperationsController(private val scope: CoroutineScope, private val isCurrent: (SessionContext) -> Boolean,
    private val onRenamed: (SessionContext, ResourceRef, RenameTarget) -> Unit,
    private val onTransferFinished: (SessionContext, ServerTask, List<ResourceRef>) -> Unit = { _, _, _ -> }) {
    private val mutable = MutableStateFlow(FileOperationsState())
    val state = mutable.asStateFlow()
    private var bound: SessionContext? = null
    private var write: Job? = null
    private var directoryRead: Job? = null
    private var directoryEpoch = 0L
    private var poll: Job? = null
    private var visible = false
    private val pendingTasks = linkedMapOf<String, PendingFileTransfer>()
    fun bind(context: SessionContext?) {
        write?.cancel(); directoryRead?.cancel(); directoryEpoch++; poll?.cancel(); visible = false
        pendingTasks.clear(); bound = context
        mutable.value = FileOperationsState(scope = context?.owner.orEmpty())
    }
    private fun current(context: SessionContext) = bound === context && isCurrent(context)
    fun startTransfer(files: List<ResourceRef>, action: FileTransferAction, directory: DirectoryCrumb) {
        val context = bound ?: return
        if (!current(context) || mutable.value.changing || mutable.value.transfer != null) return
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
                val wire = requireNotNull(directory.wirePath) { "目标目录无法安全访问" }
                taskResourcePath(directory.path, wire)
                val data = context.api.request("GET", "/api/resources$wire?metadata=1")
                check(data.optBoolean("isDir") && taskResourcePath(data.getString("path"), data.optString("wirePath")) == directory.path) { "目标目录已变化，请选择其他目录" }
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
    fun chooseConflict(path: String, value: FileConflictChoice) {
        val draft = mutable.value.transfer ?: return
        if (mutable.value.changing || path !in draft.conflicts) return
        mutable.value = mutable.value.copy(transfer = draft.copy(choices = draft.choices + (path to value)))
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
                val entries = fileTransferEntries(draft.files, draft.directory, draft.action)
                if (!draft.reviewed) {
                    val paths = (entries.map { it.file.path } + entries.map { it.targetPath }).distinct()
                    val metadata = linkedMapOf<String, JSONObject>()
                    for (chunk in paths.chunked(500)) {
                        check(current(context)) { "连接已切换" }
                        val rows = context.api.resourceBatch(chunk)
                        check(rows.length() == chunk.size) { "资源检查结果不完整，请重试" }
                        for (i in chunk.indices) { val row = rows.getJSONObject(i); check(row.getString("path") == chunk[i]); metadata[chunk[i]] = row }
                    }
                    for (entry in entries) {
                        val row = metadata.getValue(entry.file.path)
                        check(row.getInt("status") == 200) { "${entry.file.name} 已不可用，请刷新后重新选择" }
                        val item = row.getJSONObject("item")
                        check(item.getBoolean("isDir") == entry.file.directory && (entry.file.directory || item.getLong("size") == entry.file.size) &&
                            (entry.file.modified.isEmpty() || item.optString("modified") == entry.file.modified)) { "${entry.file.name} 已变化，请刷新后重新选择" }
                        check(taskResourcePath(item.getString("path"), item.optString("wirePath")) == entry.file.path) { "文件来源已变化，请刷新" }
                    }
                    val conflicts = entries.filter { entry ->
                        val row = metadata.getValue(entry.targetPath)
                        check(row.getInt("status") in setOf(200, 404)) { "目标路径无法访问，请选择其他目录" }
                        row.getInt("status") == 200
                    }.map { it.file.path }.toSet()
                    if (!current(context)) return@launch
                    mutable.value = mutable.value.copy(changing = false, transfer = draft.copy(reviewed = true, conflicts = conflicts,
                        choices = conflicts.associateWith { FileConflictChoice.KEEP_BOTH }))
                    return@launch
                }
                val selected = entries.filter { draft.choices[it.file.path] != FileConflictChoice.SKIP }
                require(selected.isNotEmpty()) { "所有项目都已跳过，请调整选择" }
                check(selected.none { draft.choices[it.file.path] == FileConflictChoice.REPLACE } || permissions.modify) { "替换现有内容需要修改权限" }
                check(selected.none { draft.choices[it.file.path] == FileConflictChoice.REPLACE } || replaceConfirmed) { "请先确认替换同名目标内容" }
                check(selected.none { entry -> draft.choices[entry.file.path] == FileConflictChoice.REPLACE && entries.any {
                    it.file.path == entry.targetPath || it.file.path.startsWith(entry.targetPath.trimEnd('/') + "/")
                } }) { "目标包含本次源项目，不能替换，请保留两份或跳过" }
                check(selected.none { it.file.path == it.targetPath && draft.choices[it.file.path] != FileConflictChoice.KEEP_BOTH }) { "源和目标相同，只能保留两份或跳过" }
                val body = JSONObject().put("action", draft.action.wire).put("items", JSONArray(selected.map { entry ->
                    JSONObject().put("from", "/files" + entry.file.wirePath.ifEmpty { SearchResult.encodePath(entry.file.path) })
                        .put("to", "/files" + entry.targetWire).put("name", entry.file.name).put("size", entry.file.size)
                        .put("modified", entry.file.modified).put("isDir", entry.file.directory)
                        .put("overwrite", draft.choices[entry.file.path] == FileConflictChoice.REPLACE)
                        .put("rename", draft.choices[entry.file.path] == FileConflictChoice.KEEP_BOTH)
                }))
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
    fun rename(file: ResourceRef, name: String, done: () -> Unit = {}) {
        val context = bound ?: return
        if (!current(context) || mutable.value.changing) return
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
}
