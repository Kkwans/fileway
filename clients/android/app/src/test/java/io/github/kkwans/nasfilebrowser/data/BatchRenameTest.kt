package io.github.kkwans.nasfilebrowser.data

import io.github.kkwans.nasfilebrowser.app.ResourceRef
import org.junit.Assert.*
import org.junit.Test

class BatchRenameTest {
    private fun file(path: String, wire: String = SearchResult.encodePath(path), directory: Boolean = false) =
        ResourceRef(path, wire, path.substringAfterLast('/'), directory, "", 12)

    @Test fun cachedSwapUsesOriginalWireIdentityAndRewritesOnlyTheMatchingDirectory() {
        val first = file("/a.txt").copy(size = 1); val second = file("/b.txt").copy(size = 2)
        val swap = listOf(BatchRenameChange(first, RenameTarget("/b.txt", "/b.txt", "b.txt")),
            BatchRenameChange(second, RenameTarget("/a.txt", "/a.txt", "a.txt")))
        val after = applyBatchResourceRenames(listOf(first, second), swap)
        assertEquals(listOf("/b.txt", "/a.txt"), after.map { it.path }); assertEquals(listOf(1L, 2L), after.map { it.size })
        val folder = file("/�", "/%FF", directory = true)
        val descendant = file("/�/nested/a.txt", "/%ff/nested/a.txt")
        val sibling = file("/�/nested/a.txt", "/%FE/nested/a.txt")
        val renamed = applyBatchResourceRenames(listOf(folder, descendant, sibling), listOf(BatchRenameChange(folder, RenameTarget("/new", "/new", "new"))))
        assertEquals(listOf("/new", "/new/nested/a.txt", sibling.path), renamed.map { it.path })
        assertEquals(listOf("/new", "/new/nested/a.txt", sibling.wirePath), renamed.map { it.wirePath })
    }

    @Test fun fourRulesMatchWebSemanticsAndNumberingFollowsSnapshotOrder() {
        val files = listOf(file("/z.tar.gz"), file("/a.txt"), file("/folder.ext", directory = true), file("/.hidden"))
        assertEquals("/", batchRenameParent(files.first()).wirePath)
        assertEquals(listOf("pre-z.tar.gz", "pre-a.txt", "pre-folder.ext", "pre-.hidden"),
            batchRenameRows(files, BatchRenameOptions(text = "pre-")).map { it.newName })
        assertEquals(listOf("z.tar-tail.gz", "a-tail.txt", "folder.ext-tail", ".hidden-tail"),
            batchRenameRows(files, BatchRenameOptions(rule = BatchRenameRule.SUFFIX, text = "-tail")).map { it.newName })
        assertEquals("/x-x.txt", batchRenameRows(listOf(file("/a-a.txt")), BatchRenameOptions(rule = BatchRenameRule.REPLACE, search = "a", replacement = "x")).single().target!!.path)
        val numbering = BatchRenameOptions(rule = BatchRenameRule.NUMBER, text = "file-", start = "9", padding = "3")
        assertEquals(listOf("file-009.gz", "file-010.txt", "file-011", "file-012"), batchRenameRows(files, numbering).map { it.newName })
        assertEquals(listOf("file-009", "file-010", "file-011", "file-012"), batchRenameRows(files, numbering.copy(preserveExtension = false)).map { it.newName })
    }
    @Test fun displayedRulesProduceUtf8FullNamesWhileSourceAndParentKeepOriginalBytes() {
        val source = file("/资料/中文.txt", "/%D7%CA%C1%CF/%D6%D0%CE%C4.txt")
        val examples = listOf(
            BatchRenameOptions(text = "新") to "新中文.txt",
            BatchRenameOptions(rule = BatchRenameRule.SUFFIX, text = "新") to "中文新.txt",
            BatchRenameOptions(rule = BatchRenameRule.REPLACE, search = "中", replacement = "新") to "新文.txt",
            BatchRenameOptions(rule = BatchRenameRule.NUMBER, text = "集", start = "2", padding = "3") to "集002.txt")
        for ((options, expected) in examples) {
            val row = batchRenameRows(listOf(source), options).single()
            assertNull(row.error)
            assertTrue(row.changed)
            assertEquals(expected, row.newName)
            val target = requireNotNull(row.target)
            assertEquals("/资料/$expected", target.path)
            assertEquals("/%D7%CA%C1%CF/" + SearchResult.encodePath(expected), target.wirePath)
            assertEquals("/%D7%CA%C1%CF/%D6%D0%CE%C4.txt", batchRenameSourceWire(row.file))
            val manual = batchRenameRows(listOf(source), options, mapOf(source.wirePath to expected)).single()
            assertEquals("Re-entering the preview must not change the target", row.target, manual.target)
            assertFalse(batchRenameLegacySafe(listOf(BatchRenameChange(row.file, target))))
        }
        for (options in listOf(BatchRenameOptions(), BatchRenameOptions(rule = BatchRenameRule.SUFFIX),
            BatchRenameOptions(rule = BatchRenameRule.REPLACE), BatchRenameOptions(rule = BatchRenameRule.REPLACE, search = "missing", replacement = "new"))) {
            val unchanged = batchRenameRows(listOf(source), options).single()
            assertNull(unchanged.error)
            assertFalse(unchanged.changed)
            assertEquals(source.wirePath, unchanged.target!!.wirePath)
        }
        assertFalse(batchRenameRows(listOf(source), BatchRenameOptions(text = "new-"), mapOf(source.wirePath to source.name)).single().changed)
        val safe = batchRenameRows(listOf(file("/普通.txt")), BatchRenameOptions(text = "新-"))
        assertTrue(batchRenameLegacySafe(safe.map { BatchRenameChange(it.file, it.target!!) }))
    }
    @Test fun sameDisplaySourcesRemainDistinctButSameUtf8DestinationsConflict() {
        val files = listOf(file("/中文.txt", "/%D6%D0%CE%C4.txt"), file("/中文.txt"))
        assertNotEquals(resourceWireBytes(files[0].wirePath).toList(), resourceWireBytes(files[1].wirePath).toList())
        val unchanged = batchRenameRows(files, BatchRenameOptions())
        assertTrue(unchanged.all { !it.changed && it.error == null })
        assertEquals(files.map { it.wirePath }, unchanged.map { it.target!!.wirePath })
        val rows = batchRenameRows(files, BatchRenameOptions(text = "新"))
        assertTrue(rows.all { it.changed && it.error != null })
        assertEquals(listOf("/%E6%96%B0%E4%B8%AD%E6%96%87.txt", "/%E6%96%B0%E4%B8%AD%E6%96%87.txt"), rows.map { it.target!!.wirePath })
        assertEquals(files.map { it.wirePath }, rows.map { batchRenameSourceWire(it.file) })
    }
    @Test fun unknownDisplayCharactersRequireCompleteNamingButRealUtf8ReplacementCharacterIsText() {
        val unknown = file("/�.txt", "/%FF.txt")
        for (options in listOf(BatchRenameOptions(text = "新"), BatchRenameOptions(rule = BatchRenameRule.SUFFIX, text = "新"))) {
            val row = batchRenameRows(listOf(unknown), options).single()
            assertNull(row.target)
            assertTrue(row.error!!.contains("无法识别"))
        }
        val unchanged = batchRenameRows(listOf(unknown), BatchRenameOptions()).single()
        assertFalse(unchanged.changed)
        assertEquals("/%FF.txt", unchanged.target!!.wirePath)
        val explicit = batchRenameRows(listOf(unknown), BatchRenameOptions(), mapOf("/%FF.txt" to "新�.txt")).single()
        assertNull(explicit.error)
        assertEquals("/" + SearchResult.encodePath("新�.txt"), explicit.target!!.wirePath)
        assertEquals("/%FF.txt", batchRenameSourceWire(explicit.file))
        val replaced = batchRenameRows(listOf(unknown), BatchRenameOptions(rule = BatchRenameRule.REPLACE, search = "�", replacement = "已知")).single()
        assertEquals("已知.txt", replaced.target!!.name)
        val numbered = batchRenameRows(listOf(unknown), BatchRenameOptions(rule = BatchRenameRule.NUMBER, text = "file-")).single()
        assertNull(numbered.error)
        assertEquals("/file-001.txt", numbered.target!!.wirePath)
        val unknownExtension = file("/name.�", "/name.%FF")
        assertNotNull(batchRenameRows(listOf(unknownExtension), BatchRenameOptions(rule = BatchRenameRule.NUMBER)).single().error)
        assertNull(batchRenameRows(listOf(unknownExtension), BatchRenameOptions(rule = BatchRenameRule.NUMBER, preserveExtension = false)).single().error)
        val known = batchRenameRows(listOf(file("/�.txt")), BatchRenameOptions(text = "新")).single()
        assertNull(known.error)
        assertEquals("/" + SearchResult.encodePath("新�.txt"), known.target!!.wirePath)
    }
    @Test fun duplicatesIllegalNamesCrossDirectoryAndUnsafeSourcePathsAreRejected() {
        val files = listOf(file("/a.txt"), file("/b.txt"))
        val duplicate = batchRenameRows(files, BatchRenameOptions(), mapOf("/a.txt" to "same.txt", "/b.txt" to "same.txt"))
        assertTrue(duplicate.all { it.error != null })
        for (name in listOf("", ".", "..", "a/b", "a\u0000b")) {
            assertNotNull(batchRenameRows(files.take(1), BatchRenameOptions(), mapOf("/a.txt" to name)).single().error)
        }
        assertFalse(batchRenameRows(files, BatchRenameOptions()).any { it.changed })
        assertThrows(IllegalArgumentException::class.java) { batchRenameRows(listOf(files[0], file("/other/b.txt")), BatchRenameOptions()) }
        assertThrows(IllegalArgumentException::class.java) { batchRenameRows(listOf(file("/a.txt"), file("/a.txt", "/%61.txt")), BatchRenameOptions()) }
        for (wire in listOf("/%2Fescape.txt", "/%2E%2E/a.txt", "/%00.txt", "/a//b.txt")) {
            assertThrows(IllegalArgumentException::class.java) { batchRenameRows(listOf(file("/bad.txt", wire)), BatchRenameOptions()) }
        }
        assertThrows(IllegalArgumentException::class.java) { batchRenameRows(List(501) { file("/file-$it") }, BatchRenameOptions()) }
    }
}
