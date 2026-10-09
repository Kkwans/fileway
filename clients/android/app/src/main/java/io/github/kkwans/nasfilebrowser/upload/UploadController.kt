package io.github.kkwans.nasfilebrowser.upload

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import io.github.kkwans.nasfilebrowser.app.SessionContext
import io.github.kkwans.nasfilebrowser.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.UUID

enum class UploadConflict(val label: String) { KEEP_BOTH("保留两份"), SKIP("跳过"), REPLACE("覆盖") }
data class UploadDraft(val source: LocalUploadSource, val targetPath: String, val targetWire: String,
    val existingIdentity: String? = null, val choice: UploadConflict = UploadConflict.KEEP_BOTH)
data class UploadsState(val items: List<UploadRecord> = emptyList(), val busy: Boolean = false, val selecting: Boolean = false,
    val targetLabel: String = "", val scanned: Int = 0, val drafts: List<UploadDraft> = emptyList(),
    val folder: Boolean = false, val error: String? = null, val notice: String? = null, val canReplace: Boolean = false,
    val speeds: Map<String, Long?> = emptyMap(), val sent: Map<String, Long> = emptyMap(), val completedRevision: Long = 0)
private data class UploadSelection(val binding: SessionContext, val directory: DirectoryCrumb)

class UploadController(private val context: Context, private val scope: CoroutineScope, private val isCurrent: (SessionContext) -> Boolean) {
    private val database = ClientDatabase.get(context); private val dao = database.uploads()
    private val mutable = MutableStateFlow(UploadsState()); val state = mutable.asStateFlow()
    private var selection: UploadSelection? = null
    private var action: Job? = null; private var sampling: Job? = null
    private fun current(selected: UploadSelection) = selection === selected && isCurrent(selected.binding)
    init { scope.launch {
        val scheduler = context.getSystemService(android.app.job.JobScheduler::class.java)
        dao.observe().first().filter { it.active }.forEach { row ->
            if (!UploadRuntime.get(context).isRunning(row.id) && scheduler.getPendingJob(row.jobId) == null) dao.command(row.id, "interrupted", System.currentTimeMillis())
        }
        dao.observe().first().filter { it.status == "canceling" && !UploadRuntime.get(context).isCanceling(it.id) }.forEach { row ->
            dao.canceled(row.id, row.generation, "cancel_failed", row.uploaded, "上次清理未得到确认，请重试核对原服务器", System.currentTimeMillis())
        }
        var completed = emptySet<String>()
        dao.observe().collect { rows ->
            val done = rows.filter { it.complete }.map { it.id }.toSet()
            val newlyCompleted = (done - completed).isNotEmpty()
            val before = mutable.value
            mutable.value = before.copy(items = rows, completedRevision = before.completedRevision + if (newlyCompleted) 1 else 0,
                notice = if (newlyCompleted && !before.busy && !before.selecting) "上传已完成，本机原文件保留" else before.notice)
            completed = done
        }
    } }
    fun visible(active: Boolean) {
        if (active == (sampling?.isActive == true)) return
        sampling?.cancel(); sampling = null
        if (!active) return
        sampling = scope.launch {
            val previous = mutableMapOf<String, UploadTransfer>()
            while (isActive) {
                val samples = mutableMapOf<String, UploadTransfer>(); val speeds = mutableMapOf<String, Long?>(); val sent = mutableMapOf<String, Long>()
                for (row in mutable.value.items.filter { it.active }) {
                    try {
                        val sample = UploadRuntime.get(context).transfer(row.id)
                        if (sample != null) {
                            samples[row.id] = sample; sent[row.id] = sample.progress
                            val old = previous[row.id]
                            speeds[row.id] = if (old != null && sample.elapsedMillis > old.elapsedMillis && sample.bytes >= old.bytes)
                                ((sample.bytes - old.bytes).toDouble() * 1000 / (sample.elapsedMillis - old.elapsedMillis)).toLong() else null
                        } else speeds[row.id] = 0
                    } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { speeds[row.id] = null }
                }
                previous.clear(); previous.putAll(samples)
                mutable.value = mutable.value.copy(speeds = speeds, sent = sent); delay(1000)
            }
        }
    }
    fun begin(binding: SessionContext, directory: DirectoryCrumb): Boolean {
        if (!isCurrent(binding) || mutable.value.busy || !binding.api.identity.permissions.create) return false
        cancelSelection(quiet = true)
        selection = UploadSelection(binding, directory)
        mutable.value = mutable.value.copy(selecting = true, targetLabel = binding.profile.name + " · " + directory.path,
            scanned = 0, drafts = emptyList(), error = null, notice = null, canReplace = binding.api.identity.permissions.modify)
        return true
    }
    fun cancelSelection(quiet: Boolean = false) {
        if (selection == null) return
        action?.cancel(); action = null; selection = null
        mutable.value = mutable.value.copy(busy = false, selecting = false, drafts = emptyList(), error = null, scanned = 0,
            notice = if (quiet) null else "上传准备已取消，已经建立的任务保留")
    }
    fun prepare(files: List<Uri> = emptyList(), tree: Uri? = null) {
        val selected = selection ?: return
        if (!current(selected) || mutable.value.busy) return
        mutable.value = mutable.value.copy(busy = true, folder = tree != null, error = null, scanned = 0)
        action = scope.launch {
            try {
                val permissions = selected.binding.api.permissions(); check(permissions.create) { "当前账号没有上传权限" }
                val wire = requireNotNull(selected.directory.wirePath)
                resourceWireBytes(wire)
                check(selected.binding.api.request("GET", "/api/resources$wire?metadata=1").getBoolean("isDir")) { "原上传目录已不可用" }
                val sources = withContext(Dispatchers.IO) {
                    val reader = UploadSources(context)
                    if (tree != null) reader.folder(tree, { current(selected) && isActive }) { count ->
                        if (current(selected)) mutable.value = mutable.value.copy(scanned = count)
                    } else {
                        require(files.isNotEmpty() && files.size <= 1000) { "请选择1到1000个本机文件" }
                        files.distinct().map { uri -> reader.retain(uri); reader.read(uri) }
                    }
                }
                currentCoroutineContext().ensureActive(); check(current(selected)) { "上传目标已切换" }
                check(sources.isNotEmpty()) { "所选文件夹没有可上传的文件" }
                val drafts = arrayListOf<UploadDraft>(); val seen = hashSetOf<String>()
                val parents = sources.map { it.relativeDirectory }.filter { it.isNotEmpty() }.flatMap { relative ->
                    val parts = relative.split('/'); (1..parts.size).map { parts.take(it).joinToString("/") }
                }.distinct()
                for (parent in parents) {
                    check(current(selected)) { "上传目标已切换" }
                    val parentWire = wire.trimEnd('/') + "/" + SearchResult.encodePath(parent)
                    val info = existing(selected.binding.api, parentWire)
                    check(info == null || info.getBoolean("isDir")) { "目标的 $parent 已有同名文件，请换一个上传目录" }
                }
                for (source in sources) {
                    check(current(selected)) { "上传目标已切换" }
                    val relative = listOf(source.relativeDirectory, source.name).filter { it.isNotEmpty() }.joinToString("/")
                    val targetPath = selected.directory.path.trimEnd('/') + "/" + relative
                    val targetWire = wire.trimEnd('/') + "/" + SearchResult.encodePath(relative)
                    check(seen.add(targetWire)) { "所选来源有重复目标名称，请分批选择" }
                    val info = existing(selected.binding.api, targetWire)
                    val identity = info?.let { if (it.getBoolean("isDir")) "directory" else "${it.getLong("size")}/${it.optString("modified")}" }
                    drafts.add(UploadDraft(source, targetPath, targetWire, identity))
                }
                sources.fold(0L) { total, item -> Math.addExact(total, item.size) }
                check(current(selected)) { "上传目标已切换" }
                mutable.value = mutable.value.copy(busy = false, scanned = sources.size, drafts = drafts, canReplace = permissions.modify)
            } catch (failure: Exception) {
                if (failure !is CancellationException && current(selected)) mutable.value = mutable.value.copy(busy = false, error = failure.message ?: "无法读取本机文件，请重新选择")
            }
        }
    }
    private suspend fun existing(api: NasSession, wire: String): org.json.JSONObject? = try { api.request("GET", "/api/resources$wire?metadata=1") }
        catch (failure: ServiceException) { if (failure.status == 404) null else throw failure }
    fun conflict(uri: String, choice: UploadConflict) {
        if (mutable.value.busy || choice == UploadConflict.REPLACE && !mutable.value.canReplace) return
        mutable.value = mutable.value.copy(drafts = mutable.value.drafts.map { if (it.source.uri == uri) it.copy(choice = choice) else it })
    }
    fun submit() {
        val selected = selection ?: return
        val before = mutable.value
        if (!current(selected) || before.busy || before.drafts.isEmpty()) return
        mutable.value = before.copy(busy = true, error = null, notice = null)
        action = scope.launch {
            var created = 0
            try {
                check(selected.binding.api.permissions().create) { "当前账号没有上传权限" }
                val batch = UUID.randomUUID().toString(); val bytes = before.drafts.fold(0L) { n, item -> Math.addExact(n, item.source.size) }
                for (draft in before.drafts) {
                    check(current(selected)) { "上传目标已切换，已经建立的任务保留" }
                    if (draft.existingIdentity != null && draft.choice == UploadConflict.SKIP) {
                        mutable.value = mutable.value.copy(drafts = mutable.value.drafts.filterNot { it === draft }); continue
                    }
                    var target = draft.targetPath; var wire = draft.targetWire
                    if (draft.existingIdentity != null && draft.choice == UploadConflict.KEEP_BOTH) {
                        val name = draft.source.name; val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
                        var suffix = 2
                        do {
                            require(suffix <= 1000) { "同名文件太多，请换一个目录或重命名本机来源" }
                            val candidate = name.substring(0, dot) + "（${suffix++}）" + name.substring(dot)
                            target = draft.targetPath.substringBeforeLast('/') + "/" + candidate
                            wire = draft.targetWire.substringBeforeLast('/') + "/" + SearchResult.encodePath(candidate)
                        } while (existing(selected.binding.api, wire) != null)
                    }
                    val overwrite = draft.existingIdentity != null && draft.choice == UploadConflict.REPLACE
                    check(!overwrite || draft.existingIdentity != "directory" && selected.binding.api.permissions().modify) { "不能覆盖文件夹，或没有覆盖权限" }
                    val now = System.currentTimeMillis()
                    val row = database.withTransaction {
                        check(current(selected)) { "上传目标已切换" }
                        val job = dao.lastJobId() + 1; require(job in 7900001..8500000) { "上传任务编号已用完" }
                        UploadRecord(UUID.randomUUID().toString(), job, selected.binding.account.key, selected.binding.profile.id,
                            selected.binding.profile.sourceRevision, draft.source.uri, draft.source.name, draft.source.mime, draft.source.size,
                            draft.source.modified, target, wire, selected.directory.wirePath!!, selected.binding.profile.name + " · " + selected.binding.account.username,
                            overwrite, if (overwrite) draft.existingIdentity!! else "", if (draft.source.size == 0L) "resources" else "tus",
                            createdAt = now, updatedAt = now, batchId = batch, batchName = draft.source.relativeDirectory.substringBefore('/').ifEmpty { "本机文件" },
                            batchItems = before.drafts.size, batchBytes = bytes, folderUpload = before.folder).also { dao.insert(it) }
                    }
                    created++
                    if (current(selected)) mutable.value = mutable.value.copy(drafts = mutable.value.drafts.filterNot { it === draft }, notice = "已建立 $created 项上传任务")
                    try { UploadScheduler.start(context, row) }
                    catch (failure: Exception) { dao.command(row.id, "failed", now); throw failure }
                }
                check(current(selected)) { "上传目标已切换，已建立任务保留" }
                selection = null; mutable.value = mutable.value.copy(busy = false, selecting = false, drafts = emptyList(), notice = "已添加 $created 项上传任务，可从上传页查看")
            } catch (failure: Exception) {
                if (failure !is CancellationException && current(selected)) mutable.value = mutable.value.copy(busy = false,
                    error = "已建立 $created 项任务；${failure.message ?: "上传准备失败"}。已有任务保留，可添加剩余项。")
            }
        }
    }
    fun pause(row: UploadRecord) = change { UploadScheduler.pause(context, row.id); "上传已暂停，本机原文件保留" }
    fun resume(row: UploadRecord) = change {
        val item = dao.get(row.id) ?: error("上传记录不存在")
        check(item.canResume && !item.active) { "上传正在进行、已经结束或正在清理" }
        withTimeout(30_000) { UploadRuntime.get(context).awaitStopped(item.id) }
        check(dao.command(item.id, "queued", System.currentTimeMillis()) == 1)
        try { UploadScheduler.start(context, dao.get(item.id)!!) }
        catch (failure: Exception) { dao.command(item.id, "failed", System.currentTimeMillis()); throw failure }
        "正在核对服务器进度并继续上传"
    }
    fun remove(row: UploadRecord) = change {
        val item = dao.get(row.id) ?: error("上传记录不存在")
        check(!item.active && (item.complete || item.status == "canceled")) { "未完成清理请先保留记录并核对服务器片段" }
        check(dao.removeRecord(item.id) == 1)
        "上传记录已移除，本机原文件和服务器文件保留"
    }
    fun cancel(row: UploadRecord) = change { UploadRuntime.get(context).cancelTask(row.id) }
    fun reselectSource(id: String, generation: Long, uri: Uri) = change {
        val item = dao.get(id) ?: error("上传记录不存在")
        check(item.canReselectSource && item.generation == generation) { "上传任务状态已变化，请重新选择原文件" }
        withTimeout(30_000) { UploadRuntime.get(context).awaitStopped(item.id) }
        val source = withContext(Dispatchers.IO) { UploadSources(context).reauthorize(item, uri) }
        check(dao.reauthorize(item.id, generation, source.uri, System.currentTimeMillis()) == 1) { "上传任务已变化，原进度保留，请重试" }
        if (item.status == "expired") "原文件读取授权已恢复；服务器片段已过期，请重新开始上传"
        else "原文件读取授权已恢复，已有进度保留，可继续上传"
    }
    fun sourceSelectionCanceled() { mutable.value = mutable.value.copy(notice = "已取消重新选择，原任务和进度保留") }
    private fun change(block: suspend () -> String) {
        if (mutable.value.busy) return
        mutable.value = mutable.value.copy(busy = true, error = null, notice = null)
        scope.launch {
            try { val notice = block(); mutable.value = mutable.value.copy(busy = false, notice = notice) }
            catch (failure: Exception) { if (failure !is CancellationException) mutable.value = mutable.value.copy(busy = false, error = failure.message ?: "上传操作失败") }
        }
    }
    fun reportError(message: String) { mutable.value = mutable.value.copy(error = message) }
}
