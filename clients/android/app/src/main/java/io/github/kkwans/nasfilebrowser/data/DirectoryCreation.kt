package io.github.kkwans.nasfilebrowser.data

fun directoryCreationTarget(parent: DirectoryCrumb, input: String): DirectoryCrumb {
    require(parent.path.startsWith('/') && !parent.path.contains('\u0000')) { "父目录无效，请刷新" }
    require(renameNameError(input) == null) { renameNameError(input).orEmpty() }
    val wire = parent.wirePath ?: error("父目录无法安全访问")
    val bytes = resourceWireBytes(wire)
    // Latin-1 maps one byte to one character: only ASCII separators/dots are
    // inspected here, without repairing or re-encoding legacy filename bytes.
    require(bytes.none { it == 0.toByte() } && bytes.toString(Charsets.ISO_8859_1).split('/').none { it == "." || it == ".." }) { "父目录来源无效" }
    val name = input.trim()
    return DirectoryCrumb(name, parent.path.trimEnd('/') + "/" + name, wire.trimEnd('/') + "/" + SearchResult.encodePath(name))
}
