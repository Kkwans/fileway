package io.github.kkwans.nasfilebrowser.app

import io.github.kkwans.nasfilebrowser.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

data class ArchiveEntryOpenState(val scope: String = "", val archive: ResourceRef? = null, val entry: ArchiveEntry? = null,
    val id: String? = null, val preparing: Boolean = false, val canceling: Boolean = false, val phase: String = "",
    val stage: String = "", val size: Long = 0, val processedBytes: Long = 0, val error: String? = null) {
    val progress: Float? get() = if (phase == "copying" && size > 0) (processedBytes.toDouble() / size).toFloat().coerceIn(0f, 1f) else null
}
class ArchiveEntryController(private val scope: CoroutineScope, private val isCurrent: (SessionContext) -> Boolean,
    private val onReady: (SessionContext, ResourceRef, PreviewLease, ArchiveEntryKind) -> Unit) {
    private val mutable = MutableStateFlow(ArchiveEntryOpenState())
    val state = mutable.asStateFlow()
    private var bound: SessionContext? = null
    private var operation: Job? = null
    private var epoch = 0L
    private fun current(owner: SessionContext, revision: Long) = bound === owner && isCurrent(owner) && epoch == revision
    private fun cancelRemote(owner: SessionContext?, id: String?) {
        if (owner == null || id == null) return
        scope.launch { try { owner.api.action("DELETE", "/api/archives/open/$id") } catch (error: Exception) { if (error is CancellationException) throw error } }
    }
    fun bind(owner: SessionContext?) {
        if (bound === owner) return
        if (mutable.value.preparing) cancelRemote(bound, mutable.value.id)
        operation?.cancel(); epoch++; bound = owner
        mutable.value = ArchiveEntryOpenState(scope = owner?.owner.orEmpty())
    }
    fun open(archive: ResourceRef, listing: ArchiveListing, selected: ArchiveEntry, sourceScope: String) {
        val owner = bound ?: return
        if (!isCurrent(owner) || owner.api.id != sourceScope) return
        if (mutable.value.preparing) cancelRemote(owner, mutable.value.id)
        operation?.cancel(); val revision = ++epoch
        val entry = try {
            require(!archive.directory && archive.downloadId.isEmpty() && !listing.truncated && !selected.isDir)
            require(resourceWireBytes(archiveAbsoluteWire(archive.path, archive.wirePath)).contentEquals(resourceWireBytes(listing.archiveWirePath)))
            listing.entries.single { archiveEntryWireBytes(it.wirePath).contentEquals(archiveEntryWireBytes(selected.wirePath)) }.also { require(!it.isDir) }
        } catch (_: Exception) {
            mutable.value = ArchiveEntryOpenState(scope = owner.owner, archive = archive, error = "包内文件身份无法确认，请重新读取压缩包"); return
        }
        mutable.value = ArchiveEntryOpenState(scope = owner.owner, archive = archive, entry = entry, preparing = true, phase = "queued", stage = "正在请求准备包内文件")
        operation = scope.launch {
            var lease: PreviewLease? = null
            var preparedID: String? = null
            var transferred = false
            try {
                withTimeout(11 * 60_000L) {
                    val request = JSONObject().put("archiveWirePath", listing.archiveWirePath).put("entryWirePath", entry.wirePath)
                        .put("sourceSize", listing.sourceSize).put("sourceModified", listing.sourceModified)
                    var status = ArchiveEntryOpenStatus.from(owner.api.action("POST", "/api/archives/open", request), listing, entry)
                    preparedID = status.id
                    while (current(owner, revision)) {
                        mutable.value = mutable.value.copy(id = status.id, phase = status.state, stage = status.stage, size = status.size, processedBytes = status.processedBytes)
                        when (status.state) {
                            "ready" -> {
                                lease = owner.api.assetLease(status.asset)
                                if (!current(owner, revision)) return@withTimeout
                                onReady(owner, entry.openResource(), lease!!, archiveEntryKind(entry.name))
                                transferred = true
                                lease = null
                                if (current(owner, revision)) mutable.value = mutable.value.copy(preparing = false, phase = "ready", stage = "包内文件已就绪")
                                return@withTimeout
                            }
                            "failed" -> error(status.error ?: "所选条目无法安全准备")
                            "canceled", "canceling" -> error("包内文件准备已取消")
                        }
                        delay(750)
                        status = ArchiveEntryOpenStatus.from(owner.api.request("GET", "/api/archives/open/${status.id}"), listing, entry)
                        require(status.id == preparedID) { "服务器返回了不同准备任务" }
                    }
                }
            } catch (error: Exception) {
                if (error is CancellationException && error !is TimeoutCancellationException) throw error
                if (current(owner, revision)) mutable.value = mutable.value.copy(preparing = false, phase = "failed",
                    error = if (error is ServiceException && error.status == 409) "压缩包已变化，请重新读取后再打开"
                        else if (error is TimeoutCancellationException) "包内文件准备超时，已请求停止"
                        else "包内文件未能打开：${error.message ?: "请重试"}")
                if (error is TimeoutCancellationException) cancelRemote(owner, preparedID)
            } finally {
                lease?.release()
                if (!current(owner, revision) && !transferred) cancelRemote(owner, preparedID)
            }
        }
    }
    fun cancel() {
        val owner = bound ?: return; val before = mutable.value
        if (!isCurrent(owner) || !before.preparing) return
        operation?.cancel(); val revision = ++epoch
        mutable.value = before.copy(preparing = false, canceling = before.id != null, phase = "canceling", stage = "正在请求取消准备", error = null)
        val id = before.id ?: run { mutable.value = mutable.value.copy(canceling = false, stage = "已取消本机等待；未确认的服务器准备会按超时回收"); return }
        scope.launch {
            try {
                owner.api.action("DELETE", "/api/archives/open/$id")
                if (current(owner, revision)) mutable.value = mutable.value.copy(canceling = false, phase = "canceled", stage = "已请求取消准备")
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (current(owner, revision)) mutable.value = mutable.value.copy(canceling = false, error = "取消未获确认，服务器准备会按超时回收")
            }
        }
    }
    fun dismissError() { if (!mutable.value.preparing && !mutable.value.canceling) mutable.value = mutable.value.copy(error = null) }
}
