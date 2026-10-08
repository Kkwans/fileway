package io.github.kkwans.nasfilebrowser.data

import io.github.kkwans.nasfilebrowser.app.ResourceRef
import java.net.URLDecoder
import org.junit.Assert.*
import org.junit.Test

class FileRenameTest {
    private fun file(path: String, wire: String = SearchResult.encodePath(path)) = ResourceRef(path, wire, path.substringAfterLast('/'), false, "", 1)
    @Test fun queryDecodesExactlyOnceAndPreservesLiteralPercentAndReservedCharacters() {
        val target = renameTarget(file("/A +&=;/旧.png"), "%2F +# 中文.png")
        assertEquals("/A +&=;/%2F +# 中文.png", URLDecoder.decode(target.destinationQuery, "UTF-8"))
        assertEquals(target.path, URLDecoder.decode(target.destinationQuery, "UTF-8"))
        assertTrue(target.destinationQuery.contains("%252F"))
    }
    @Test fun opaqueParentBytesAreNotDecodedOrDoubleEscaped() {
        val target = renameTarget(file("/�/old.png", "/%D6%D0/old.png"), "新.png")
        assertEquals("/%D6%D0/%E6%96%B0.png", target.wirePath)
        assertEquals("%2F%D6%D0%2F%E6%96%B0.png", target.destinationQuery)
    }
    @Test fun invalidNamesAndLocalOrRootSourcesAreRejected() {
        for (name in listOf("", " ", ".", "..", "a/b", "a\u0000b")) assertNotNull(renameNameError(name))
        for (source in listOf(file("/"), file("/old.png").copy(downloadId = "local"))) {
            assertThrows(IllegalArgumentException::class.java) { renameTarget(source, "new.png") }
        }
        assertThrows(IllegalArgumentException::class.java) { renameTarget(file("/old.png"), "old.png") }
    }
}
