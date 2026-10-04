package io.github.kkwans.nasfilebrowser.data

data class DirectoryCrumb(val label: String, val path: String, val wirePath: String?)

/** Display labels never decode or reinterpret the source's opaque wire bytes. */
fun directoryTrail(path: String, wirePath: String): List<DirectoryCrumb> {
    val root = DirectoryCrumb("根目录", "/", "/")
    if (path == "/") return listOf(root)
    val names = path.removePrefix("/").split('/')
    if (!path.startsWith('/') || names.any { it.isEmpty() }) {
        return listOf(root, DirectoryCrumb(path, path, wirePath.takeIf { it.isNotEmpty() }))
    }
    val wire = wirePath.removePrefix("/").split('/')
    val safe = wirePath.startsWith('/') && wire.size == names.size && wire.none { it.isEmpty() }
    return listOf(root) + names.indices.map { index ->
        val prefix = "/" + names.take(index + 1).joinToString("/")
        val encoded = when {
            wirePath.isEmpty() -> SearchResult.encodePath(prefix)
            safe -> "/" + wire.take(index + 1).joinToString("/")
            index == names.lastIndex -> wirePath
            else -> null
        }
        DirectoryCrumb(names[index], prefix, encoded)
    }
}
