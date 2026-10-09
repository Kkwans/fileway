package io.github.kkwans.nasfilebrowser.app

import io.github.kkwans.nasfilebrowser.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class FileChecksumState(val scope: String = "", val file: ResourceRef? = null,
    val algorithm: ChecksumAlgorithm = ChecksumAlgorithm.SHA256, val expected: String = "",
    val busy: Boolean = false, val result: String? = null, val error: String? = null) {
    val expectedInvalid get() = expected.isNotBlank() && normalizedChecksum(expected, algorithm) == null
    val matches: Boolean? get() = result?.let { digest -> normalizedChecksum(expected, algorithm)?.let { it == digest } }
}

class FileChecksumController(private val scope: CoroutineScope, private val isCurrent: (SessionContext) -> Boolean) {
    private val mutable = MutableStateFlow(FileChecksumState())
    val state = mutable.asStateFlow()
    private var bound: SessionContext? = null
    private var request: Job? = null
    private var revision = 0L

    fun bind(context: SessionContext?) {
        if (bound === context) return
        request?.cancel(); revision++; bound = context
        mutable.value = FileChecksumState(scope = context?.owner.orEmpty())
    }
    fun open(file: ResourceRef, sourceScope: String) {
        val context = bound ?: return
        if (!isCurrent(context) || sourceScope != context.api.id || file.directory || file.downloadId.isNotEmpty()) return
        request?.cancel(); revision++
        mutable.value = FileChecksumState(scope = context.owner, file = file)
    }
    fun close() {
        request?.cancel(); revision++
        mutable.value = FileChecksumState(scope = bound?.owner.orEmpty())
    }
    fun cancel() {
        request?.cancel(); revision++
        mutable.value = mutable.value.copy(busy = false, error = "已取消计算，可重新开始")
    }
    fun algorithm(value: ChecksumAlgorithm) {
        if (mutable.value.busy || mutable.value.algorithm == value) return
        revision++; mutable.value = mutable.value.copy(algorithm = value, result = null, error = null)
    }
    fun expected(value: String) { mutable.value = mutable.value.copy(expected = value) }
    fun calculate() {
        val context = bound ?: return
        val before = mutable.value; val file = before.file ?: return
        if (!isCurrent(context) || before.busy) return
        val epoch = ++revision
        mutable.value = before.copy(busy = true, result = null, error = null)
        request = scope.launch {
            try {
                check(context.api.permissions().download) { "当前账号没有读取文件内容的权限" }
                val wire = batchRenameSourceWire(file)
                val response = context.api.request("GET", "/api/resources$wire?checksum=${before.algorithm.query}")
                check(!response.getBoolean("isDir")) { "文件类型已变化，请刷新原目录" }
                val returned = response.optString("wirePath")
                if (returned.isNotEmpty()) check(resourceWireBytes(returned).contentEquals(resourceWireBytes(wire))) { "校验结果的原始路径不匹配" }
                else check(resourceWireBytes(wire).toString(Charsets.UTF_8) == file.path && response.getString("path") == file.path) { "服务器未确认原始文件路径，请升级服务器" }
                check(response.getLong("size") == file.size && (file.modified.isEmpty() || response.getString("modified") == file.modified)) {
                    "文件已变化，请刷新原目录后重新计算"
                }
                val digest = normalizedChecksum(response.getJSONObject("checksums").getString(before.algorithm.query), before.algorithm)
                    ?: error("服务器返回的校验值不完整，请重试")
                if (bound === context && isCurrent(context) && revision == epoch) mutable.value = mutable.value.copy(busy = false, result = digest)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (bound === context && isCurrent(context) && revision == epoch) mutable.value = mutable.value.copy(busy = false,
                    error = error.message ?: "计算失败，请重试")
            }
        }
    }
}
