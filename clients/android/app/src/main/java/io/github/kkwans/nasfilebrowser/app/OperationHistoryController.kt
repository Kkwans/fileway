package io.github.kkwans.nasfilebrowser.app

import io.github.kkwans.nasfilebrowser.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class OperationHistoryState(val scope: String = "", val filter: OperationHistoryFilter = OperationHistoryFilter(),
    val itemsFilter: OperationHistoryFilter = OperationHistoryFilter(), val items: List<OperationHistoryEntry> = emptyList(),
    val loaded: Boolean = false, val loading: Boolean = false, val paging: Boolean = false, val clearing: Boolean = false,
    val nextCursor: String = "", val total: Int = 0, val error: String? = null, val notice: String? = null,
    val retryMore: Boolean = false, val clearConfirmation: Boolean = false) {
    val showingPreviousFilter get() = loaded && filter != itemsFilter
}

/** Every completion is scoped to the immutable login context and request revision. */
class OperationHistoryController(private val scope: CoroutineScope, private val isCurrent: (SessionContext) -> Boolean) {
    private val mutable = MutableStateFlow(OperationHistoryState())
    val state = mutable.asStateFlow()
    private var bound: SessionContext? = null
    private var read: Job? = null
    private var change: Job? = null
    private var revision = 0L
    private var visible = false

    fun bind(context: SessionContext?) {
        if (bound === context) return
        read?.cancel(); change?.cancel(); revision++
        bound = context; visible = false
        mutable.value = OperationHistoryState(scope = context?.owner.orEmpty())
    }
    private fun current(context: SessionContext, epoch: Long) = bound === context && isCurrent(context) && revision == epoch
    fun setVisible(value: Boolean) {
        if (visible == value) return
        visible = value
        if (value) refresh()
    }
    fun filter(value: OperationHistoryFilter) {
        val before = mutable.value
        if (value == before.filter || before.clearing) return
        if (value.from < 0 || value.to < 0 || value.from > 0 && value.to > 0 && value.from > value.to) return
        read?.cancel(); revision++
        mutable.value = before.copy(filter = value, loading = false, paging = false, error = null, notice = null, retryMore = false, clearConfirmation = false)
        refresh()
    }
    fun refresh(more: Boolean = false) {
        val context = bound ?: return
        val before = mutable.value
        if (!isCurrent(context) || before.loading || before.paging || before.clearing ||
            more && (before.nextCursor.isEmpty() || before.showingPreviousFilter)) return
        val epoch = ++revision
        val cursor = if (more) before.nextCursor else ""
        mutable.value = before.copy(loading = !more, paging = more, error = null, notice = null, retryMore = more)
        read = scope.launch {
            try {
                val page = OperationHistoryPage.from(context.api.request("GET", before.filter.query(cursor)))
                check(page.nextCursor.isEmpty() || page.nextCursor != cursor) { "服务器返回了重复分页位置，请刷新后重试" }
                val rows = linkedMapOf<String, OperationHistoryEntry>()
                if (more) before.items.forEach { rows[it.id] = it }
                page.items.forEach { rows[it.id] = it }
                if (current(context, epoch)) mutable.value = mutable.value.copy(items = rows.values.toList(), itemsFilter = before.filter,
                    nextCursor = page.nextCursor, total = page.total, loaded = true, loading = false, paging = false, error = null, retryMore = false)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (current(context, epoch)) mutable.value = mutable.value.copy(
                    loading = false, paging = false, error = error.message ?: "操作历史读取失败，请重试", retryMore = more)
            }
        }
    }
    fun retry() = refresh(more = mutable.value.retryMore)
    fun requestClear() {
        val context = bound ?: return
        val before = mutable.value
        if (isCurrent(context) && !before.loading && !before.paging && !before.clearing)
            mutable.value = before.copy(clearConfirmation = true)
    }
    fun cancelClear() { mutable.value = mutable.value.copy(clearConfirmation = false) }
    fun confirmClear() {
        val context = bound ?: return
        val before = mutable.value
        if (!before.clearConfirmation || !isCurrent(context) || before.loading || before.paging || before.clearing) return
        read?.cancel(); val epoch = ++revision
        mutable.value = before.copy(clearConfirmation = false, clearing = true, error = null, notice = null, retryMore = false)
        change = scope.launch {
            try {
                // Require the documented acknowledgement; an empty or malformed response is never success.
                val deleted = context.api.request("DELETE", "/api/history").getInt("deleted")
                check(deleted >= 0) { "服务器未确认清空结果，请刷新核对" }
                if (current(context, epoch)) mutable.value = mutable.value.copy(items = emptyList(), itemsFilter = before.filter,
                    nextCursor = "", total = 0, loaded = true, clearing = false, notice = "已清空当前账号的 $deleted 条操作记录")
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (current(context, epoch)) mutable.value = mutable.value.copy(
                    clearing = false, error = "清空未获确认：${error.message ?: "请刷新核对后重试"}")
            }
        }
    }
}
