package io.github.kkwans.nasfilebrowser.data

import io.github.kkwans.nasfilebrowser.app.ResourceRef

data class RenameTarget(val path: String, val wirePath: String, val name: String) {
    // Preserve percent-encoded path bytes, including non-UTF-8 parents. Only
    // escape characters whose meaning differs between a path and a query value.
    val destinationQuery: String get() = wirePath.replace("+", "%2B").replace("&", "%26")
        .replace("=", "%3D").replace(";", "%3B").replace("/", "%2F")
}

fun renameNameError(input: String): String? {
    val name = input.trim()
    return when {
        name.isEmpty() -> "请输入新名称"
        name == "." || name == ".." || name.contains('/') || name.contains('\u0000') -> "名称不能包含 /，也不能是 . 或 .."
        else -> null
    }
}

fun renameTarget(file: ResourceRef, input: String): RenameTarget {
    require(file.path.startsWith('/') && file.path != "/" && file.downloadId.isEmpty()) { "只能重命名服务器上的文件或文件夹" }
    require(renameNameError(input) == null) { renameNameError(input).orEmpty() }
    val name = input.trim()
    require(name != file.name) { "名称没有变化" }
    val wire = file.wirePath.ifEmpty { SearchResult.encodePath(file.path) }
    require(wire.startsWith('/') && !wire.startsWith("//") && !wire.contains('?') && !wire.contains('#')) { "文件来源无效，请刷新后重试" }
    return RenameTarget(file.path.substringBeforeLast('/') + "/" + name,
        wire.substringBeforeLast('/') + "/" + SearchResult.encodePath(name), name)
}
