package io.github.kkwans.nasfilebrowser.data

import io.github.kkwans.nasfilebrowser.app.ResourceRef
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

enum class FileTransferAction(val label: String, val wire: String) { COPY("复制", "copy"), MOVE("移动", "move") }
enum class FileConflictChoice(val label: String) { SKIP("跳过"), KEEP_BOTH("保留两份"), REPLACE("替换") }
data class FileTransferEntry(val file: ResourceRef, val targetPath: String, val targetWire: String)

/** Durable task args are JSON strings. The current server decodes wire paths
 * before persisting them; invalid UTF-8 would be replaced on serialization.
 * Reject that capability explicitly instead of addressing a different file.
 */
fun taskResourcePath(path: String, wirePath: String): String {
    val wire = wirePath.ifEmpty { SearchResult.encodePath(path) }
    val bytes = resourceWireBytes(wire)
    val decoded = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: java.nio.charset.CharacterCodingException) {
        error("服务器的复制／移动任务暂不支持这个旧编码路径，未提交操作")
    }
    require(decoded == path && !decoded.contains('\u0000') && decoded.split('/').none { it == "." || it == ".." }) { "路径已变化，请刷新后重新选择" }
    return decoded
}

/** Decode wire bytes exactly once, without interpreting legacy filename bytes. */
fun resourceWireBytes(wire: String): ByteArray {
    require(wire.startsWith('/') && !wire.startsWith("//") && !wire.contains('?') && !wire.contains('#')) { "路径来源无效，请刷新后重试" }
    val input = wire.toByteArray(Charsets.UTF_8); val output = ByteArrayOutputStream()
    var index = 0
    while (index < input.size) {
        if (input[index] == '%'.code.toByte()) {
            require(index + 2 < input.size) { "路径编码无效" }
            val high = input[index + 1].toInt().toChar().digitToIntOrNull(16)
            val low = input[index + 2].toInt().toChar().digitToIntOrNull(16)
            require(high != null && low != null) { "路径编码无效" }
            output.write(high * 16 + low); index += 3
        } else { output.write(input[index].toInt()); index++ }
    }
    return output.toByteArray()
}

fun fileTransferEntries(files: List<ResourceRef>, directory: DirectoryCrumb, action: FileTransferAction): List<FileTransferEntry> {
    val target = taskResourcePath(directory.path, requireNotNull(directory.wirePath) { "目标目录无法安全访问" })
    val snapshot = files.distinctBy { it.wirePath.ifEmpty { it.path } }
    require(snapshot.isNotEmpty() && snapshot.size <= 1000) { "请选择 1 到 1000 项" }
    val entries = snapshot.map { file ->
        val source = taskResourcePath(file.path, file.wirePath)
        require(source != "/" && file.downloadId.isEmpty()) { "只能操作服务器上的文件或文件夹，不能选择根目录" }
        require(!file.directory || target != source && !target.startsWith(source.trimEnd('/') + "/")) { "不能把文件夹复制或移动到自身或子目录" }
        val originalName = source.substringAfterLast('/')
        val sameDirectory = source.substringBeforeLast('/').ifEmpty { "/" } == target
        require(!sameDirectory || action != FileTransferAction.MOVE) { "项目已在目标目录，请选择其他目录" }
        val dot = if (file.directory) -1 else originalName.lastIndexOf('.').takeIf { it > 0 } ?: -1
        val name = if (!sameDirectory) originalName else if (dot < 0) "$originalName（副本）"
            else originalName.substring(0, dot) + "（副本）" + originalName.substring(dot)
        val path = target.trimEnd('/') + "/" + name
        FileTransferEntry(file, path, directory.wirePath!!.trimEnd('/') + "/" + SearchResult.encodePath(name))
    }
    require(entries.map { it.targetPath }.distinct().size == entries.size) { "不同来源有同名项目，请分批处理" }
    return entries
}
