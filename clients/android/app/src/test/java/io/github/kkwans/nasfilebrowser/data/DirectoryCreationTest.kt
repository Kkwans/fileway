package io.github.kkwans.nasfilebrowser.data

import org.junit.Assert.*
import org.junit.Test

class DirectoryCreationTest {
    @Test fun childNamesAreEncodedOnceAndLegacyParentBytesRemainOpaque() {
        val target = directoryCreationTarget(DirectoryCrumb("旧目录", "/中文", "/%D6%D0"), "%2F +#新目录")
        assertEquals("/中文/%2F +#新目录", target.path)
        assertEquals("/%D6%D0/%252F%20%2B%23%E6%96%B0%E7%9B%AE%E5%BD%95", target.wirePath)
        assertArrayEquals(resourceWireBytes("/%d6%d0/~a%2B"), resourceWireBytes("/%D6%D0/%7Ea+"))
        assertFalse(resourceWireBytes("/A").contentEquals(resourceWireBytes("/a")))
    }
    @Test fun traversalEmptyNamesAndInvalidParentAreRejected() {
        for (name in listOf("", " ", ".", "..", "a/b", "a\u0000b")) assertThrows(IllegalArgumentException::class.java) {
            directoryCreationTarget(DirectoryCrumb("根", "/", "/"), name)
        }
        assertThrows(IllegalArgumentException::class.java) { directoryCreationTarget(DirectoryCrumb("坏目录", "/x", "//x"), "a") }
        assertThrows(IllegalArgumentException::class.java) { directoryCreationTarget(DirectoryCrumb("坏目录", "/..", "/%2e%2e"), "a") }
        assertThrows(IllegalArgumentException::class.java) { directoryCreationTarget(DirectoryCrumb("坏目录", "/..", "/.%2e"), "a") }
        assertEquals("/文件夹", directoryCreationTarget(DirectoryCrumb("根", "/", "/"), " 文件夹 ").path)
    }
}
