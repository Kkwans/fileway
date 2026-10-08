package io.github.kkwans.nasfilebrowser.app

import io.github.kkwans.nasfilebrowser.data.RenameTarget
import io.github.kkwans.nasfilebrowser.data.renameTarget
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class FileOperationsState(val scope: String = "", val changing: Boolean = false,
    val error: String? = null, val notice: String? = null)

/** Existing resource mutations stay bound to their original server/account. */
class FileOperationsController(private val scope: CoroutineScope, private val isCurrent: (SessionContext) -> Boolean,
    private val onRenamed: (SessionContext, ResourceRef, RenameTarget) -> Unit) {
    private val mutable = MutableStateFlow(FileOperationsState())
    val state = mutable.asStateFlow()
    private var bound: SessionContext? = null
    private var write: Job? = null
    fun bind(context: SessionContext?) {
        write?.cancel(); bound = context
        mutable.value = FileOperationsState(scope = context?.owner.orEmpty())
    }
    private fun current(context: SessionContext) = bound === context && isCurrent(context)
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
