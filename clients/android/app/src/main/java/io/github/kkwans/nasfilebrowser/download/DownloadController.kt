package io.github.kkwans.nasfilebrowser.download

import android.content.Context
import androidx.room.withTransaction
import io.github.kkwans.nasfilebrowser.app.ResourceRef
import io.github.kkwans.nasfilebrowser.app.SessionContext
import io.github.kkwans.nasfilebrowser.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.UUID

data class DownloadsState(val items: List<DownloadRecord> = emptyList(), val tree: String = "", val busy: Boolean = false, val error: String? = null, val notice: String? = null,
    val speeds: Map<String, Long?> = emptyMap())

class DownloadController(private val context: Context, private val scope: CoroutineScope) {
    private val database = ClientDatabase.get(context); private val dao = database.downloads()
    val target = DownloadTarget(context)
    private val mutable = MutableStateFlow(DownloadsState(tree = target.selectedTree()))
    val state = mutable.asStateFlow()
    private var sampling: Job? = null
    fun visible(active: Boolean) {
        if (active == (sampling?.isActive == true)) return
        sampling?.cancel(); sampling = null
        if (!active) return
        sampling = scope.launch {
            val previous = mutableMapOf<String, DownloadTransfer>()
            while (isActive) {
                val samples = mutableMapOf<String, DownloadTransfer>()
                val rates = mutableMapOf<String, Long?>()
                for (item in mutable.value.items) {
                    val current = DownloadRuntime.get(context).transfers(item.id)
                    var rate = 0L; var known = current.isEmpty()
                    for (sample in current) {
                        samples[sample.key] = sample
                        val old = previous[sample.key]
                        if (old != null && sample.elapsedMillis > old.elapsedMillis) {
                            rate += ((sample.bytes - old.bytes).coerceAtLeast(0).toDouble() * 1000 / (sample.elapsedMillis - old.elapsedMillis)).toLong()
                            known = true
                        }
                    }
                    rates[item.id] = if (known) rate else null
                }
                previous.clear(); previous.putAll(samples)
                mutable.value = mutable.value.copy(speeds = rates)
                delay(1000)
            }
        }
    }
    init { scope.launch {
        // System Task Manager may kill without onStopJob. Reconcile durable
        // rows against our own live workers/jobs, leaving local files intact.
        val scheduler = context.getSystemService(android.app.job.JobScheduler::class.java)
        dao.observe().first().filter { it.active }.forEach { item ->
            if (!DownloadRuntime.get(context).isRunning(item.id) && scheduler.getPendingJob(item.jobId) == null)
                dao.command(item.id, "interrupted", System.currentTimeMillis())
        }
        dao.observe().collect { mutable.value = mutable.value.copy(items = it) }
    } }
    fun selectDirectory(uri: android.net.Uri?) = perform {
        withContext(Dispatchers.IO) { target.selectTree(uri) }
        mutable.value = mutable.value.copy(tree = target.selectedTree())
        "下载目录已保存"
    }
    fun reauthorizeDirectory(id: String, uri: android.net.Uri) = perform {
        val record = dao.get(id) ?: error("下载记录不存在")
        check(record.treeUri.isNotEmpty()) { "默认下载目录无需重新授权" }
        withContext(Dispatchers.IO) { target.reauthorizeTree(record.treeUri, uri) }
        "原目录授权已恢复；文件和默认目录保持不变，可重新打开或继续下载"
    }
    fun enqueue(binding: SessionContext, file: ResourceRef, current: () -> Boolean) = enqueueAll(binding, listOf(file), current)
    fun enqueueAll(binding: SessionContext, files: List<ResourceRef>, current: () -> Boolean, onCreated: (ResourceRef) -> Unit = {}) = perform {
        val snapshot = files.distinctBy { it.wirePath.ifEmpty { it.path } }
        require(snapshot.isNotEmpty() && snapshot.all { !it.directory && it.downloadId.isEmpty() }) { "请选择服务器上的文件，暂不支持文件夹批量下载" }
        check(current()) { "下载来源已切换" }
        check(binding.api.permissions().download) { "当前账号没有下载权限" }
        var created = 0
        for (file in snapshot) {
            try {
                addDownload(binding, file, current) {
                    created++
                    if (current()) onCreated(file)
                    mutable.value = mutable.value.copy(notice = "已建立 $created / ${snapshot.size} 项下载任务")
                }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                throw IllegalStateException("已建立 $created / ${snapshot.size} 项任务；${file.name}：${failure.message ?: "添加失败"}。已有任务可在下载页查看或继续。", failure)
            }
        }
        "已添加 $created 项下载，可在本机下载中查看"
    }
    private suspend fun addDownload(binding: SessionContext, file: ResourceRef, current: () -> Boolean, created: () -> Unit) {
        check(current()) { "下载来源已切换" }
        val wire = file.wirePath.ifEmpty { SearchResult.encodePath(file.path) }
        val info = binding.api.request("GET", "/api/resources$wire?metadata=1")
        check(current() && !info.getBoolean("isDir")) { "下载来源已切换或文件已变化" }
        val size = info.getLong("size"); val modified = info.optString("modified")
        check(size >= 0) { "源文件长度无法确认" }
        val now = System.currentTimeMillis()
        val record = database.withTransaction {
            check(current()) { "下载来源已切换" }
            val next = dao.lastJobId() + 1
            check(next in 7300001..7900000) { "下载任务编号已用完，请整理历史记录" }
            DownloadRecord(UUID.randomUUID().toString(), next, binding.account.key, binding.profile.id, binding.profile.sourceRevision,
                file.path, wire, file.name, file.type, size, modified, "$size/$modified", binding.profile.name + " · " + binding.account.username,
                target.selectedTree(), createdAt = now, updatedAt = now).also { dao.insert(it) }
        }
        try { DownloadScheduler.start(context, record) }
        catch (failure: Exception) { dao.command(record.id, "failed", now); throw failure }
        finally { created() }
    }
    fun resume(record: DownloadRecord) = perform {
        val current = dao.get(record.id) ?: error("下载记录不存在")
        check(!current.complete && !current.active) { "下载已经开始或完成" }
        check(withContext(Dispatchers.IO) { target.hasAccess(current.treeUri) }) { "原下载目录授权已失效，请先在更多菜单中重新授权目录" }
        withTimeout(30_000) { DownloadRuntime.get(context).awaitStopped(record.id) }
        check(dao.command(record.id, "queued", System.currentTimeMillis()) == 1)
        try { DownloadScheduler.start(context, dao.get(record.id) ?: error("下载记录不存在")) }
        catch (failure: Exception) { dao.command(record.id, "failed", System.currentTimeMillis()); throw failure }
        "正在继续下载"
    }
    fun pause(record: DownloadRecord) = perform { DownloadScheduler.pause(context, record.id); "下载已暂停，已保存的部分保留" }
    fun remove(record: DownloadRecord, file: Boolean) = perform {
        val current = dao.get(record.id) ?: error("下载记录不存在")
        check(!current.active) { "请先暂停下载" }
        check(file || current.complete) { "未完成文件请使用删除文件与记录，避免留下无法恢复的片段" }
        withTimeout(30_000) { DownloadRuntime.get(context).awaitStopped(record.id) }
        if (file && current.localUri.isNotEmpty()) check(target.delete(current)) { "无法删除本机文件，请检查目录授权" }
        check(dao.removeRecord(record.id) == 1)
        if (file) "文件与记录已删除" else "记录已移除，本机文件保留"
    }
    private fun perform(action: suspend () -> String) {
        if (mutable.value.busy) return
        mutable.value = mutable.value.copy(busy = true, error = null, notice = null)
        scope.launch {
            try { val message = action(); mutable.value = mutable.value.copy(busy = false, notice = message) }
            catch (failure: Exception) { if (failure is CancellationException) throw failure; mutable.value = mutable.value.copy(busy = false, error = failure.message ?: "下载操作失败，请重试") }
        }
    }
    fun reportError(message: String) { mutable.value = mutable.value.copy(error = message, notice = null) }
    fun reportNotice(message: String) { mutable.value = mutable.value.copy(notice = message, error = null) }
}
