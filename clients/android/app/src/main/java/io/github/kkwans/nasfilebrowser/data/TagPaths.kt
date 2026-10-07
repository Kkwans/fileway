package io.github.kkwans.nasfilebrowser.data

/** NAS path comparison: preserve case, Unicode and legal filename spaces. */
fun collectionPath(path: String): String {
    val segments = ArrayDeque<String>()
    path.split('/').forEach { when (it) { "", "." -> Unit; ".." -> if (segments.isNotEmpty()) segments.removeLast(); else -> segments.add(it) } }
    return "/" + segments.joinToString("/")
}
fun taggedPathMatches(path: String, marked: Collection<String>, global: Boolean): Boolean {
    val target = collectionPath(path)
    return marked.any {
        val value = collectionPath(it)
        value == target || (global && (value == "/" || target == "/" || value.startsWith("$target/") || target.startsWith("$value/")))
    }
}

val TAG_COLORS = listOf("#E5484D", "#D95876", "#F06A5B", "#F28C28", "#DDAA1D", "#D6BE21", "#86B83E", "#35A867",
    "#2AA889", "#28AFC0", "#3A9BD9", "#3F72D8", "#5B62D9", "#7656C9", "#9B4DB5", "#C34F90", "#758195")
