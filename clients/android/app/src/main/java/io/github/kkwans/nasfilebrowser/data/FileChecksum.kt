package io.github.kkwans.nasfilebrowser.data

enum class ChecksumAlgorithm(val label: String, val query: String, val digits: Int) {
    SHA256("SHA-256", "sha256", 64), SHA1("SHA-1", "sha1", 40), MD5("MD5", "md5", 32)
}

/** Accept pasted upper/lowercase hexadecimal, with harmless surrounding whitespace. */
fun normalizedChecksum(value: String, algorithm: ChecksumAlgorithm): String? = value.trim().lowercase(java.util.Locale.ROOT)
    .takeIf { it.length == algorithm.digits && it.all { character -> character in '0'..'9' || character in 'a'..'f' } }
