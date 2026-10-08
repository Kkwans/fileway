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
}
