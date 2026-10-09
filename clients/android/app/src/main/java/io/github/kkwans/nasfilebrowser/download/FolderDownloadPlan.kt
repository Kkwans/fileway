package io.github.kkwans.nasfilebrowser.download

import io.github.kkwans.nasfilebrowser.app.ResourceRef
import io.github.kkwans.nasfilebrowser.data.SearchResult
import io.github.kkwans.nasfilebrowser.data.resourceWireBytes
import org.json.JSONObject
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class FolderDownloadEntry(val file: ResourceRef, val relativeDirectory: String, val rootKey: String)
data class FolderDownloadPlan(val entries: List<FolderDownloadEntry>, val roots: List<ResourceRef>, val emptyDirectories: Int) {
    val bytes: Long get() = entries.fold(0L) { total, entry -> Math.addExact(total, entry.file.size) }
}

/** A bounded, read-only snapshot. Download writers continue using each original
 * opaque wire path; display names are used only for the local folder layout. */
internal suspend fun scanFolderDownloads(selected: List<ResourceRef>, current: () -> Boolean,
    read: suspend (String) -> JSONObject, progress: (Int) -> Unit): FolderDownloadPlan {
    fun wire(file: ResourceRef) = file.wirePath.ifEmpty { SearchResult.encodePath(file.path) }
    fun key(file: ResourceRef): String {
        val bytes = resourceWireBytes(wire(file))
        require(bytes.none { it == 0.toByte() } && bytes.toString(Charsets.ISO_8859_1).split('/').none { it == "." || it == ".." }) { "下载来源路径无效" }
        return java.util.Base64.getEncoder().encodeToString(bytes)
    }
    fun ancestor(parent: ResourceRef, child: ResourceRef): Boolean {
        val prefix = resourceWireBytes(wire(parent).trimEnd('/') + "/")
        return resourceWireBytes(wire(child)).let { it.size > prefix.size && it.copyOfRange(0, prefix.size).contentEquals(prefix) }
    }
    val distinct = selected.distinctBy(::key)
    require(distinct.isNotEmpty() && distinct.size <= 1000 && distinct.all { it.downloadId.isEmpty() }) { "请选择 1 到 1000 项服务器文件或文件夹" }
    val roots = distinct.filter { file -> distinct.none { it !== file && it.directory && ancestor(it, file) } }
    val entries = arrayListOf<FolderDownloadEntry>()
    val visited = hashSetOf<String>()
    var directories = 0; var empty = 0
    suspend fun request(endpoint: String): JSONObject {
        check(current()) { "下载来源已切换" }
        return read(endpoint).also { currentCoroutineContext().ensureActive(); check(current()) { "下载来源已切换" } }
    }
    suspend fun scan(file: ResourceRef, local: String, root: String, depth: Int) {
        require(depth <= 64 && visited.size < 7000) { "文件夹过深或项目过多，请分批下载子目录" }
        require(visited.add(key(file))) { "文件夹返回重复路径，请刷新后重试" }
        val metadata = request("/api/resources${wire(file)}?metadata=1")
        check(resourceWireBytes(metadata.optString("wirePath").ifEmpty { SearchResult.encodePath(metadata.getString("path")) })
            .contentEquals(resourceWireBytes(wire(file))) && metadata.getBoolean("isDir") == file.directory) { "文件来源已变化，请刷新后重新选择" }
        if (!file.directory) {
            val size = metadata.getLong("size"); val modified = metadata.optString("modified")
            check(size >= 0 && size == file.size && (file.modified.isEmpty() || modified == file.modified)) { "${file.name} 已变化，请刷新后重新选择" }
            require(entries.size < 5000) { "超过 5000 个文件，请分批下载子目录" }
            entries.add(FolderDownloadEntry(file.copy(size = size, modified = modified), local, root))
            progress(entries.size)
            return
        }
        require(++directories <= 2000) { "超过 2000 个文件夹，请分批下载子目录" }
        val listing = request("/api/resources${wire(file)}")
        val items = listing.getJSONArray("items")
        if (items.length() == 0) empty++
        val usedNames = (0 until items.length()).map { items.getJSONObject(it) }.filterNot { it.getBoolean("isDir") }
            .mapTo(hashSetOf()) { it.getString("name").replace('/', '_').replace('\u0000', '_') }
        for (index in 0 until items.length()) {
            val row = items.getJSONObject(index)
            val child = ResourceRef(row.getString("path"), row.optString("wirePath"), row.getString("name"), row.getBoolean("isDir"),
                row.optString("type"), row.getLong("size"), row.optString("modified"))
            val parentBytes = resourceWireBytes(wire(file).trimEnd('/') + "/")
            val childBytes = resourceWireBytes(wire(child))
            check(ancestor(file, child) && childBytes.drop(parentBytes.size).none { it == '/'.code.toByte() }) { "目录返回了其他来源的项目，未建立下载" }
            val folder = if (child.directory) uniqueDownloadFolder(child.name, usedNames) else ""
            scan(child, if (child.directory) "$local/$folder" else local, root, depth + 1)
        }
    }
    val names = roots.filterNot { it.directory }.mapTo(hashSetOf()) { it.name.replace('/', '_').replace('\u0000', '_') }
    for (file in roots) scan(file, if (file.directory) uniqueDownloadFolder(file.name.ifBlank { "文件" }, names) else "", key(file), 0)
    return FolderDownloadPlan(entries.toList(), roots, empty).also { it.bytes /* reject overflow before creating any task */ }
}

internal fun uniqueDownloadFolder(name: String, used: MutableSet<String>): String {
    val base = name.replace('/', '_').replace('\\', '_').replace('\u0000', '_').let { if (it.isBlank() || it == "." || it == "..") "文件夹" else it }
    var value = base; var suffix = 2
    while (!used.add(value)) value = "$base（${suffix++}）"
    return value
}

internal fun downloadDirectorySegments(directory: String): List<String> {
    if (directory.isEmpty()) return emptyList()
    val segments = directory.split('/')
    require(segments.size <= 65 && segments.all { it.isNotBlank() && it != "." && it != ".." && !it.contains('\u0000') && !it.contains('\\') }) { "本机下载目录无效" }
    return segments
}
