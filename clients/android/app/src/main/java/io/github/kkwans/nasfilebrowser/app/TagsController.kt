package io.github.kkwans.nasfilebrowser.app

import io.github.kkwans.nasfilebrowser.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

data class ServerTag(val id: String, val name: String, val color: String, val paths: List<String>)
data class TaggedResource(val path: String, val file: ResourceRef? = null, val error: String? = null)
data class TagsState(val scope: String = "", val items: List<ServerTag> = emptyList(), val loaded: Boolean = false,
    val loading: Boolean = false, val changing: Boolean = false, val error: String? = null, val notice: String? = null,
    val filterId: String? = null, val globalFilter: Boolean = false,
    val pathTag: String? = null, val pathParent: String? = null, val paths: List<TaggedResource> = emptyList(), val pathTotal: Int = 0,
    val pathsLoading: Boolean = false, val pathsError: String? = null)

class TagsController(private val scope: CoroutineScope, private val isCurrent: (SessionContext) -> Boolean) {
    private val mutable = MutableStateFlow(TagsState())
    val state = mutable.asStateFlow()
    private var bound: SessionContext? = null
    private var readJob: Job? = null
    private var writeJob: Job? = null
    private var pathJob: Job? = null
    private var pathEpoch = 0L
    private var readEpoch = 0L
    fun bind(context: SessionContext?) {
        readJob?.cancel(); writeJob?.cancel(); pathJob?.cancel(); pathEpoch++; readEpoch++
        bound = context; mutable.value = TagsState(scope = context?.owner.orEmpty())
    }
    private fun current(context: SessionContext) = bound === context && isCurrent(context)
    private suspend fun read(context: SessionContext): List<ServerTag> {
        val rows = context.api.array("/api/tags")
        return (0 until rows.length()).map { rows.getJSONObject(it).let { row ->
            val paths = row.optJSONArray("paths")
            ServerTag(row.getString("id"), row.getString("name"), row.getString("color"),
                if (paths == null) emptyList() else (0 until paths.length()).map { collectionPath(paths.getString(it)) }.distinct())
        } }
    }
    fun refresh() {
        val context = bound ?: return
        if (!current(context) || mutable.value.loading || mutable.value.changing) return
        val epoch = ++readEpoch
        mutable.value = mutable.value.copy(loading = true, error = null, notice = null)
        readJob = scope.launch {
            try { val rows = read(context); if (current(context) && readEpoch == epoch) apply(rows) }
            catch (error: Exception) { if (error !is CancellationException && current(context) && readEpoch == epoch) mutable.value = mutable.value.copy(loading = false, error = error.message ?: "标签读取失败，请重试") }
        }
    }
    private fun apply(items: List<ServerTag>) {
        mutable.value = mutable.value.copy(items = items, loaded = true, loading = false, changing = false,
            filterId = mutable.value.filterId?.takeIf { id -> items.any { it.id == id } })
    }
    fun filter(id: String?, global: Boolean = mutable.value.globalFilter) { mutable.value = mutable.value.copy(filterId = id, globalFilter = global) }
    fun matches(path: String): Boolean = mutable.value.let { value ->
        val tag = value.items.firstOrNull { it.id == value.filterId } ?: return true
        return taggedPathMatches(path, tag.paths, value.globalFilter)
    }
    fun colorAvailable(color: String, except: String? = null) = mutable.value.items.none { it.id != except && it.color.trim().equals(color.trim(), true) }
    private fun change(notice: String, onSaved: () -> Unit = {}, action: suspend (SessionContext) -> Unit) {
        val context = bound ?: return
        if (!current(context) || mutable.value.changing) return
        readJob?.cancel(); pathJob?.cancel(); pathEpoch++; readEpoch++
        mutable.value = mutable.value.copy(changing = true, loading = false, pathsLoading = false, error = null, notice = null)
        writeJob = scope.launch {
            var applied = false
            try {
                action(context); applied = true
                val rows = read(context)
                if (current(context)) { apply(rows); mutable.value = mutable.value.copy(notice = notice) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                // A multi-label save can partly succeed. Re-read the authority
                // before exposing another edit, rather than restoring a local fiction.
                val refreshed = try { read(context) } catch (e: Exception) { if (e is CancellationException) throw e; null }
                if (current(context)) {
                    if (refreshed != null) apply(refreshed)
                    mutable.value = mutable.value.copy(changing = false,
                        notice = notice.takeIf { applied },
                        error = if (applied) {
                            if (refreshed == null) "操作已保存，列表刷新失败，请刷新后查看" else null
                        } else error.message ?: "标签操作未完成，请确认当前标记后重试")
                }
            }
            if (applied && current(context)) onSaved()
        }
    }
    fun save(tag: ServerTag?, name: String, color: String, onSaved: () -> Unit = {}) {
        require(name.isNotBlank())
        if ((tag == null || !color.equals(tag.color, true)) && !colorAvailable(color, tag?.id)) {
            mutable.value = mutable.value.copy(error = "这个颜色已有标签使用，请选择其他颜色"); return
        }
        change("标签已保存", onSaved) { context ->
            val body = JSONObject().put("name", name.trim())
            if (tag == null || !color.equals(tag.color, true)) body.put("color", color.uppercase(java.util.Locale.ROOT))
            context.api.action(if (tag == null) "POST" else "PUT", "/api/tags" + (tag?.let { "/${android.net.Uri.encode(it.id)}" } ?: ""), body)
        }
    }
    fun remove(tag: ServerTag) = change("标签已删除，文件保持原位") { it.api.action("DELETE", "/api/tags/${android.net.Uri.encode(tag.id)}") }
    fun removePath(tag: ServerTag, path: String) = change("已取消此路径的标签") {
        it.api.action("DELETE", "/api/tags/${android.net.Uri.encode(tag.id)}/paths", JSONObject().put("path", path))
    }
    fun assign(file: ResourceRef, baseline: Set<String>, desired: Set<String>, onSaved: () -> Unit = {}) = change("文件标签已更新", onSaved) { context ->
        require(!file.path.contains('\uFFFD') || (file.wirePath.isNotEmpty() && file.wirePath == SearchResult.encodePath(file.path))) {
            "这个文件名的原始字节无法通过当前标签接口表达，请使用其他文件"
        }
        val additions = desired - baseline; val removals = baseline - desired
        val fresh = read(context).map { it.id }.toSet()
        check(additions.all { it in fresh }) { "所选标签已被删除，请刷新后重新选择" }
        for (id in additions + removals) {
            currentCoroutineContext().ensureActive(); check(current(context))
            if (id !in fresh) continue
            context.api.action(if (id in additions) "POST" else "DELETE", "/api/tags/${android.net.Uri.encode(id)}/paths",
                JSONObject().put("path", file.path))
        }
    }
    fun loadPaths(id: String, parent: String?, more: Boolean = false) {
        val context = bound ?: return
        val tag = mutable.value.items.firstOrNull { it.id == id } ?: return
        val candidates = tag.paths.filter { parent == null || collectionPath(it.substringBeforeLast('/').ifEmpty { "/" }) == collectionPath(parent) }
        val same = mutable.value.pathTag == id && mutable.value.pathParent == parent
        val count = if (more && same) mutable.value.paths.size + 40 else if (same) maxOf(40, mutable.value.paths.size) else 40
        pathJob?.cancel(); val epoch = ++pathEpoch
        mutable.value = mutable.value.copy(pathTag = id, pathParent = parent, paths = if (same) mutable.value.paths else emptyList(),
            pathTotal = candidates.size, pathsLoading = true, pathsError = null)
        pathJob = scope.launch {
            try {
                val result = mutableListOf<TaggedResource>()
                for (chunk in candidates.take(count).chunked(100)) {
                    val rows = context.api.resourceBatch(chunk)
                    check(rows.length() == chunk.size)
                    for (index in chunk.indices) {
                        val row = rows.getJSONObject(index); val path = chunk[index]; val item = row.optJSONObject("item")
                        val file = item?.takeIf { it.optString("path") == path }?.let {
                            ResourceRef(path, it.optString("wirePath").ifEmpty { SearchResult.encodePath(path) }, it.getString("name"),
                                it.getBoolean("isDir"), it.optString("type"), it.optLong("size"), it.optString("modified"))
                        }
                        result.add(TaggedResource(path, file, if (file == null) when (row.optInt("status")) {
                            403 -> "当前账号无权访问"; 404 -> "文件已移走或删除"; else -> "路径暂时无法读取"
                        } else null))
                    }
                }
                if (current(context) && pathEpoch == epoch) mutable.value = mutable.value.copy(paths = result, pathsLoading = false)
            } catch (error: Exception) {
                if (error !is CancellationException && current(context) && pathEpoch == epoch)
                    mutable.value = mutable.value.copy(pathsLoading = false, pathsError = error.message ?: "标记路径读取失败，请重试")
            }
        }
    }
}
