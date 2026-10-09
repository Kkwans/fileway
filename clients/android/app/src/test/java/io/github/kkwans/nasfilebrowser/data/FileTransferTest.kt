package io.github.kkwans.nasfilebrowser.data

import io.github.kkwans.nasfilebrowser.app.ResourceRef
import org.junit.Assert.*
import org.junit.Test

class FileTransferTest {
    private fun file(path: String, directory: Boolean = false) = ResourceRef(path, SearchResult.encodePath(path), path.substringAfterLast('/'), directory, "", 1)
    @Test fun taskRoutesPreserveLiteralPercentPlusSpacesAndRealFilesDirectory() {
        val path = "/files/100%2F +#中文.png"
        assertEquals(path, taskResourcePath(path, SearchResult.encodePath(path)))
        assertEquals("/a+b/c=d&f.png", taskResourcePath("/a+b/c=d&f.png", "/a+b/c=d&f.png"))
        assertThrows(IllegalStateException::class.java) { taskResourcePath("/中文/a.png", "/%D6%D0/a.png") }
        assertThrows(IllegalArgumentException::class.java) { taskResourcePath("/changed/a", "/original/a") }
    }
    @Test fun sameDirectoryCopyGetsConcreteDuplicateNameWhileMoveRequiresAnotherDirectory() {
        val source = file("/照片/电影.mkv"); val dir = DirectoryCrumb("照片", "/照片", "/%E7%85%A7%E7%89%87")
        assertEquals("/照片/电影（副本）.mkv", fileTransferEntries(listOf(source), dir, FileTransferAction.COPY).single().targetPath)
        assertThrows(IllegalArgumentException::class.java) { fileTransferEntries(listOf(source), dir, FileTransferAction.MOVE) }
        assertEquals("/照片/目录.v1（副本）", fileTransferEntries(listOf(file("/照片/目录.v1", true)), dir, FileTransferAction.COPY).single().targetPath)
    }
    @Test fun sourceFoldersCannotTargetTheirChildrenAndConflictingSourceNamesCannotCollapse() {
        val source = file("/a", true)
        for (dir in listOf("/a", "/a/child")) assertThrows(IllegalArgumentException::class.java) {
            fileTransferEntries(listOf(source), DirectoryCrumb("目标", dir, dir), FileTransferAction.COPY)
        }
        assertThrows(IllegalArgumentException::class.java) {
            fileTransferEntries(listOf(file("/a/same.png"), file("/b/same.png")), DirectoryCrumb("目标", "/c", "/c"), FileTransferAction.COPY)
        }
        assertThrows(IllegalArgumentException::class.java) {
            fileTransferEntries(listOf(file("/a.png").copy(downloadId = "owned-local")), DirectoryCrumb("根", "/", "/"), FileTransferAction.COPY)
        }
    }
    @Test fun explicitWirePlanKeepsSameDisplaySiblingsAndLiteralPercentBasenamesDistinct() {
        val opaque = file("/源/中文.txt").copy(wirePath = "/%E6%BA%90/%D6%D0%CE%C4.txt")
        val utf8 = file(opaque.path)
        val percent = file("/源/100%2F.txt")
        val target = DirectoryCrumb("目标", "/目标", SearchResult.encodePath("/目标"))
        val entries = fileTransferEntries(listOf(opaque, utf8, percent), target, FileTransferAction.COPY, allowOpaque = true)
        assertEquals(3, entries.size)
        assertEquals(entries[0].targetPath, entries[1].targetPath)
        assertNotEquals(entries[0].targetWire, entries[1].targetWire)
        assertEquals("/%E7%9B%AE%E6%A0%87/%D6%D0%CE%C4.txt", entries[0].targetWire)
        assertEquals("/%E7%9B%AE%E6%A0%87/100%252F.txt", entries[2].targetWire)
        assertFalse(entries[0].legacyCompatible); assertTrue(entries[1].legacyCompatible)
        val aliases = listOf(file("/~a"), file("/~a").copy(wirePath = "/%7ea"))
        assertEquals(1, fileTransferEntries(aliases, target, FileTransferAction.COPY).size)
    }
    @Test fun sameDirectoryOpaqueCopyUsesNewUtf8NameAndRawParent() {
        val source = file("/中文/中文.txt").copy(wirePath = "/%D6%D0%CE%C4/%D6%D0%CE%C4.txt")
        val parent = DirectoryCrumb("中文", "/中文", "/%D6%D0%CE%C4")
        val row = fileTransferEntries(listOf(source), parent, FileTransferAction.COPY, allowOpaque = true).single()
        assertEquals("/中文/中文（副本）.txt", row.targetPath)
        assertEquals("/%D6%D0%CE%C4/" + SearchResult.encodePath("中文（副本）.txt"), row.targetWire)
        assertThrows(IllegalStateException::class.java) { fileTransferEntries(listOf(source), parent, FileTransferAction.COPY) }
        // C4 BF C2 BC is valid UTF-8 for Ŀ¼, so it cannot acknowledge 目录.
        assertThrows(IllegalArgumentException::class.java) { taskResourceTarget("/目录", "/%C4%BF%C2%BC", allowOpaque = true) }
    }
    @Test fun containmentAndMetadataAcknowledgementRequireByteIdentity() {
        val opaque = file("/中文", true).copy(wirePath = "/%D6%D0%CE%C4")
        val unicodeChild = DirectoryCrumb("child", "/中文/child", SearchResult.encodePath("/中文/child"))
        assertEquals(1, fileTransferEntries(listOf(opaque), unicodeChild, FileTransferAction.COPY, allowOpaque = true).size)
        assertThrows(IllegalArgumentException::class.java) {
            fileTransferEntries(listOf(opaque), unicodeChild.copy(wirePath = opaque.wirePath + "/child"), FileTransferAction.COPY, allowOpaque = true)
        }
        assertFalse(resourceWireContains("/a", "/ab/c"))
        assertTrue(resourceWireContains("/a%20", "/a%20/child"))
        assertFalse(taskResourceAcknowledged(opaque.path, opaque.wirePath, opaque.path, ""))
        assertFalse(taskResourceAcknowledged(opaque.path, opaque.wirePath, opaque.path, SearchResult.encodePath(opaque.path)))
        assertTrue(taskResourceAcknowledged(opaque.path, opaque.wirePath, opaque.path, "/%d6%d0%ce%c4"))
        assertFalse(taskResourceAcknowledged(opaque.path, opaque.wirePath, opaque.path, opaque.wirePath, pathVerified = false))
        assertTrue(taskResourceAcknowledged("/100%2F.txt", "/100%252F.txt", "/100%2F.txt", ""))
        assertFalse(taskResourceAcknowledged("/100%2F.txt", "/100%252F.txt", "/100/.txt", ""))
    }
    @Test fun malformedWireCannotSmuggleSeparatorsTraversalOrChangeLegalFilenameBytes() {
        for (wire in listOf("/a%2Fb", "/a%00b", "/%2e", "/%2E%2e/a", "/bad%", "/%GG", "//a", "/a//b", "/中文")) {
            assertThrows("$wire must be rejected", RuntimeException::class.java) { resourceWireBytes(wire) }
        }
        assertArrayEquals("/ a\\b ".toByteArray(), resourceWireBytes("/%20a%5Cb%20"))
        assertArrayEquals("/%2F".toByteArray(), resourceWireBytes("/%252F"))
        assertThrows(IllegalArgumentException::class.java) { taskResourceTarget("/changed", "/original", allowOpaque = true) }
    }
}
