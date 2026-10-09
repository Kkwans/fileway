package io.github.kkwans.nasfilebrowser.download

import io.github.kkwans.nasfilebrowser.app.ResourceRef
import io.github.kkwans.nasfilebrowser.data.archiveAbsoluteWire
import io.github.kkwans.nasfilebrowser.data.resourceWireBytes
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

const val ZIP_EXPORT_TYPE = "fileway.zip-export"
data class ZipExportPlan(val files: List<ResourceRef>, val parentPath: String, val parentWire: String, val suggestedName: String) {
    fun identity(): String = JSONObject().put("version", 1).put("wires", JSONArray(files.map { it.wirePath })).toString()
}

private fun zipKey(wire: String) = java.util.Base64.getEncoder().encodeToString(resourceWireBytes(wire))
private fun within(child: String, parent: String): Boolean {
    val target = resourceWireBytes(child); val root = resourceWireBytes(parent)
    return target.contentEquals(root) || (parent == "/" && child.startsWith('/')) ||
        (target.size > root.size && target.take(root.size).toByteArray().contentEquals(root) && target[root.size] == '/'.code.toByte())
}

fun zipExportPlan(files: List<ResourceRef>): ZipExportPlan {
    require(files.isNotEmpty() && files.size <= 1000 && files.all { it.downloadId.isEmpty() }) { "请选择 1 到 1000 个服务器项目" }
    val normalized = files.map { it.copy(wirePath = archiveAbsoluteWire(it.path, it.wirePath)) }.distinctBy { zipKey(it.wirePath) }
    val selected = normalized.filter { file -> normalized.none { parent -> parent !== file && parent.directory && within(file.wirePath, parent.wirePath) } }
    require(selected.isNotEmpty()) { "ZIP选择范围无效" }
    val parents = selected.map { it.wirePath.split('/').dropLast(1) }
    var count = parents.minOf { it.size }
    for (index in 0 until count) if (parents.any { !resourceWireBytes("/" + it[index]).contentEquals(resourceWireBytes("/" + parents[0][index])) }) { count = index; break }
    val parentWire = parents[0].take(count).joinToString("/").ifEmpty { "/" }
    val parentPath = selected[0].path.split('/').take(count).joinToString("/").ifEmpty { "/" }
    require(selected.all { within(it.wirePath, parentWire) }) { "ZIP项目不在同一个有效父目录下" }
    val name = if (selected.size == 1) selected[0].name + ".zip" else "文件打包.zip"
    return ZipExportPlan(selected, parentPath, parentWire, name)
}

fun zipExportNameError(name: String): String? = when {
    name.isBlank() || name == "." || name == ".." -> "请输入ZIP文件名"
    name.contains('/') || name.contains('\\') || name.contains('\u0000') -> "ZIP文件名不能包含路径分隔符"
    !name.endsWith(".zip", true) -> "请保留 .zip 扩展名"
    else -> null
}

internal fun zipExportWires(record: DownloadRecord): List<String> {
    require(record.type == ZIP_EXPORT_TYPE) { "不是ZIP导出任务" }
    val value = JSONObject(record.identity); require(value.getInt("version") == 1) { "ZIP任务版本暂不支持" }
    val rows = value.getJSONArray("wires")
    require(rows.length() in 1..1000) { "ZIP任务选择范围无效" }
    return (0 until rows.length()).map { rows.getString(it).also { wire ->
        archiveAbsoluteWire(record.path, wire)
        require(within(wire, record.wirePath)) { "ZIP任务选择范围已变化" }
    } }
}

internal fun zipExportEndpoint(record: DownloadRecord): String = "/api/raw${record.wirePath}?algo=zip&" +
    zipExportWires(record).joinToString("&") { "fileWirePath=" + URLEncoder.encode(it, "UTF-8") }

/** Validate only ZIP structure and offsets, including ZIP64. No media payload
 * hashing or extraction; truncated streams and server-error suffixes fail. */
fun verifyZipExport(size: Long, readAt: (Long, Int) -> ByteArray) {
    fun read(offset: Long, count: Int): ByteArray {
        require(offset >= 0 && count >= 0 && offset <= size && count.toLong() <= size - offset) { "ZIP结构超出已接收范围" }
        return readAt(offset, count).also { require(it.size == count) { "ZIP数据未完整接收" } }
    }
    fun u16(bytes: ByteArray, offset: Int) = (bytes[offset].toInt() and 255) or ((bytes[offset + 1].toInt() and 255) shl 8)
    fun u32(bytes: ByteArray, offset: Int): Long = (0..3).fold(0L) { value, index -> value or ((bytes[offset + index].toLong() and 255) shl (index * 8)) }
    fun u64(bytes: ByteArray, offset: Int): Long { val high = u32(bytes, offset + 4); require(high <= 0x7fffffff) { "ZIP长度超出支持范围" }; return (high shl 32) or u32(bytes, offset) }
    fun signature(bytes: ByteArray, offset: Int, value: Long) = u32(bytes, offset) == value
    require(size >= 22) { "ZIP结束记录缺失，请重新打包下载" }
    val tailSize = minOf(size, 65_557L).toInt(); val tail = read(size - tailSize, tailSize)
    val endIndex = (tail.size - 22 downTo 0).firstOrNull { signature(tail, it, 0x06054b50) && u16(tail, it + 20) == tail.size - it - 22 }
        ?: error("ZIP结束记录缺失或尾部不完整，请重新打包下载")
    val end = size - tailSize + endIndex
    require(u16(tail, endIndex + 4) == 0 && u16(tail, endIndex + 6) == 0) { "不支持分卷ZIP" }
    var count = u16(tail, endIndex + 10).toLong(); var centralSize = u32(tail, endIndex + 12); var centralOffset = u32(tail, endIndex + 16)
    var footer = end
    if (count == 0xffffL || centralSize == 0xffffffffL || centralOffset == 0xffffffffL) {
        val locator = read(end - 20, 20)
        require(signature(locator, 0, 0x07064b50) && u32(locator, 4) == 0L && u32(locator, 16) == 1L) { "ZIP64结束定位记录无效" }
        val zip64Offset = u64(locator, 8); val header = read(zip64Offset, 56); val recordSize = u64(header, 4)
        require(signature(header, 0, 0x06064b50) && recordSize >= 44 && zip64Offset <= end - 32 && recordSize == end - 32 - zip64Offset &&
            u32(header, 16) == 0L && u32(header, 20) == 0L && u64(header, 24) == u64(header, 32)) { "ZIP64结束记录不完整" }
        count = u64(header, 32); centralSize = u64(header, 40); centralOffset = u64(header, 48); footer = zip64Offset
    } else require(u16(tail, endIndex + 8).toLong() == count) { "ZIP目录数量不一致" }
    require(centralOffset <= footer && centralSize == footer - centralOffset && count <= centralSize / 46) { "ZIP中央目录范围无效" }
    var position = centralOffset; var processed = 0L
    while (processed < count) {
        val header = read(position, 46)
        require(signature(header, 0, 0x02014b50) && u16(header, 34) == 0) { "ZIP中央目录条目不完整" }
        val nameSize = u16(header, 28); val extraSize = u16(header, 30); val commentSize = u16(header, 32)
        val next = position + 46L + nameSize + extraSize + commentSize
        require(next <= footer) { "ZIP中央目录条目超出范围" }
        var compressed = u32(header, 20); var localOffset = u32(header, 42)
        if (compressed == 0xffffffffL || u32(header, 24) == 0xffffffffL || localOffset == 0xffffffffL) {
            val extra = read(position + 46 + nameSize, extraSize); var index = 0; var found = false
            while (index + 4 <= extra.size) {
                val tag = u16(extra, index); val length = u16(extra, index + 2); val limit = index + 4 + length
                require(limit <= extra.size) { "ZIP64扩展字段不完整" }
                if (tag == 1) {
                    var cursor = index + 4
                    fun number(): Long { require(cursor + 8 <= limit) { "ZIP64长度字段不完整" }; val result = u64(extra, cursor); cursor += 8; return result }
                    if (u32(header, 24) == 0xffffffffL) number()
                    if (compressed == 0xffffffffL) compressed = number()
                    if (localOffset == 0xffffffffL) localOffset = number()
                    found = true; break
                }
                index = limit
            }
            require(found) { "ZIP64条目缺少扩展字段" }
        }
        val local = read(localOffset, 30)
        require(signature(local, 0, 0x04034b50) && u16(local, 26) == nameSize && u16(local, 8) == u16(header, 10)) { "ZIP本地文件头不匹配" }
        val dataOffset = localOffset + 30 + nameSize + u16(local, 28)
        require(dataOffset <= centralOffset && compressed <= centralOffset - dataOffset &&
            read(localOffset + 30, nameSize).contentEquals(read(position + 46, nameSize))) { "ZIP文件数据或名称不完整" }
        position = next; processed++
    }
    require(position == footer) { "ZIP中央目录长度不一致" }
}
