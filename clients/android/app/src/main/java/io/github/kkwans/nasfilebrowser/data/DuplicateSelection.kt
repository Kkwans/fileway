package io.github.kkwans.nasfilebrowser.data

/** Same schema/link/incomplete-group rules as the existing Web cleanup flow. */
fun duplicateGroupCanBeCleaned(schema: Int, expectedFiles: Int, reason: String, identities: List<Pair<Long, Int>?>): Boolean =
    schema == 3 && expectedFiles > 1 && identities.size == expectedFiles && reason !in setOf("truncated", "unsafe-identity") &&
        identities.all { it != null && it.first == 1L && (it.second and 0xF000) == 0x8000 }
