package io.github.kkwans.nasfilebrowser.data

import io.github.kkwans.nasfilebrowser.app.ResourceRef
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

enum class BatchRenameRule(val label: String) { PREFIX("添加前缀"), SUFFIX("添加后缀"), REPLACE("替换文字"), NUMBER("连续编号") }
data class BatchRenameOptions(val rule: BatchRenameRule = BatchRenameRule.PREFIX, val text: String = "", val search: String = "",
    val replacement: String = "", val start: String = "1", val padding: String = "3", val preserveExtension: Boolean = true)
data class BatchRenameChange(val file: ResourceRef, val target: RenameTarget)
fun applyBatchResourceRenames(resources: List<ResourceRef>, changes: List<BatchRenameChange>): List<ResourceRef> {
    val mapping = changes.associateBy { resourceWireBytes(batchRenameSourceWire(it.file)).toList() }
    val directories = changes.filter { it.file.directory }.map { it to resourceWireBytes(batchRenameSourceWire(it.file)) }
    return resources.map { file ->
        val wire = file.wirePath.ifEmpty { SearchResult.encodePath(file.path) }
        val bytes = runCatching { resourceWireBytes(wire) }.getOrNull() ?: return@map file
        val direct = mapping[bytes.toList()]
        val change = direct ?: directories.firstOrNull { (_, source) -> bytes.size > source.size && bytes[source.size] == 47.toByte() &&
            source.indices.all { bytes[it] == source[it] } }?.first ?: return@map file
        if (direct != null) file.copy(path = change.target.path, wirePath = change.target.wirePath, name = change.target.name)
        else {
            val count = batchRenameSourceWire(change.file).split('/').size
            val wireSuffix = wire.split('/').drop(count).joinToString("/", prefix = "/")
            val displaySuffix = file.path.split('/').drop(count).joinToString("/", prefix = "/")
            file.copy(path = change.target.path + displaySuffix, wirePath = change.target.wirePath + wireSuffix)
        }
    }
}
data class BatchRenameRow(val file: ResourceRef, val newName: String, val target: RenameTarget?, val error: String? = null) {
    val changed get() = target != null && !resourceWireBytes(target.wirePath).contentEquals(resourceWireBytes(batchRenameSourceWire(file)))
}

fun batchRenameSourceWire(file: ResourceRef): String {
    require(file.path.startsWith('/') && file.path != "/" && file.downloadId.isEmpty()) { "只能重命名服务器上的文件或文件夹" }
    require(file.wirePath.isNotEmpty() || !file.path.contains('\uFFFD')) { "原始路径不可用，请刷新后重新选择" }
    val wire = file.wirePath.ifEmpty { SearchResult.encodePath(file.path) }
    resourceWireBytes(wire)
    val segments = wire.removePrefix("/").split('/')
    require(segments.none { segment ->
        val bytes = resourceWireBytes("/$segment").drop(1).toByteArray()
        bytes.isEmpty() || bytes.any { it == 0.toByte() || it == 47.toByte() } || bytes.contentEquals(byteArrayOf(46)) || bytes.contentEquals(byteArrayOf(46, 46))
    }) { "原始路径无效，请刷新后重新选择" }
    return wire
}

fun batchRenameParent(file: ResourceRef) = DirectoryCrumb("原目录", file.path.substringBeforeLast('/').ifEmpty { "/" },
    batchRenameSourceWire(file).substringBeforeLast('/').ifEmpty { "/" })

/** The old JSON-only protocol is safe only when every display path is the exact UTF-8 path. */
fun batchRenameLegacySafe(changes: List<BatchRenameChange>): Boolean = changes.all { change ->
    runCatching { strictUtf8(resourceWireBytes(batchRenameSourceWire(change.file))) == change.file.path &&
        strictUtf8(resourceWireBytes(change.target.wirePath)) == change.target.path }.getOrDefault(false)
}

private fun strictUtf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()

private fun newUtf8(text: String): ByteArray {
    val encoded = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .encode(CharBuffer.wrap(text))
    return ByteArray(encoded.remaining()).also { encoded.get(it) }
}

private fun wireSegment(bytes: ByteArray): String = buildString {
    val hex = "0123456789ABCDEF"
    for (byte in bytes) {
        val value = byte.toInt() and 255
        if (value in 65..90 || value in 97..122 || value in 48..57 || value in listOf(45, 46, 95, 126)) append(value.toChar())
        else { append('%'); append(hex[value ushr 4]); append(hex[value and 15]) }
    }
}

/** Rules name the displayed text; only the source and parent retain opaque bytes. */
fun batchRenameRows(files: List<ResourceRef>, options: BatchRenameOptions, overrides: Map<String, String> = emptyMap()): List<BatchRenameRow> {
    require(files.isNotEmpty() && files.size <= 500) { "每次请选择1到500项" }
    val sources = files.map { resourceWireBytes(batchRenameSourceWire(it)).toList() }
    require(sources.distinct().size == sources.size) { "选择中有重复的源项目，请刷新后重新选择" }
    val parents = files.map { resourceWireBytes(batchRenameParent(it).wirePath!!).toList() }
    require(parents.distinct().size == 1) { "请只选择同一服务器目录中的项目" }
    val rows = files.mapIndexed { index, file ->
        val wire = batchRenameSourceWire(file)
        var name = overrides[wire] ?: file.name
        try {
            val old = resourceWireBytes("/" + wire.substringAfterLast('/')).drop(1).toByteArray()
            val dot = if (file.directory) -1 else file.name.lastIndexOf('.').takeIf { it > 0 } ?: -1
            val stem = if (dot < 0) file.name else file.name.substring(0, dot)
            val extension = if (dot < 0) "" else file.name.substring(dot)
            name = overrides[wire] ?: when (options.rule) {
                BatchRenameRule.PREFIX -> options.text + file.name
                BatchRenameRule.SUFFIX -> stem + options.text + extension
                BatchRenameRule.REPLACE -> if (options.search.isEmpty()) file.name else file.name.replace(options.search, options.replacement)
                BatchRenameRule.NUMBER -> {
                    val start = options.start.toLongOrNull(); val padding = options.padding.toIntOrNull()
                    require(start != null && start in 0..999_999_999 && padding != null && padding in 1..8) { "起始序号需为0到999999999，位数为1到8" }
                    options.text + (start + index).toString().padStart(padding, '0') + if (options.preserveExtension) extension else ""
                }
            }
            // Matching display text is a skip, including manually re-entered text;
            // it must never silently convert an old filename's encoding.
            if (name == file.name) {
                return@mapIndexed BatchRenameRow(file, name, RenameTarget(file.path, wire, name))
            }
            require(renameNameError(name) == null) { renameNameError(name).orEmpty() }
            val unknownSource = file.name.contains('\uFFFD') && runCatching { strictUtf8(old) }.isFailure
            require(wire in overrides || !unknownSource || !name.contains('\uFFFD')) {
                "原名称有无法识别的文字，请手动输入完整新名称，或用替换、编号消除未知部分"
            }
            val bytes = newUtf8(name)
            val target = RenameTarget(file.path.substringBeforeLast('/') + "/" + name, wire.substringBeforeLast('/') + "/" + wireSegment(bytes), name)
            BatchRenameRow(file, name, target)
        } catch (error: IllegalArgumentException) { BatchRenameRow(file, name, null, error.message) }
        catch (error: IllegalStateException) { BatchRenameRow(file, name, null, error.message) }
        catch (_: java.nio.charset.CharacterCodingException) { BatchRenameRow(file, name, null, "新名称包含无法编码的文字，请重新输入") }
    }
    val duplicate = rows.filter { it.target != null }.groupBy { resourceWireBytes(it.target!!.wirePath).toList() }.values.filter { it.size > 1 }.flatten().toSet()
    return rows.map { if (it in duplicate) it.copy(error = "与其他项目的新名称重复") else it }
}
