package io.github.kkwans.nasfilebrowser.data

import io.github.kkwans.nasfilebrowser.app.ResourceRef
import org.junit.Assert.*
import org.junit.Test

class RecentAccessPathsTest {
    private fun file(path: String, wire: String = "") = ResourceRef(path, wire, path.substringAfterLast('/'), false, "", 0)

    @Test fun literalPercentPlusAndReservedCharactersAreNeverDecodedTwice() {
        val path = "/中文/100% +?#.mkv"
        val target = recentAccessRecordTarget(file(path, "/%E4%B8%AD%E6%96%87/100%25%20%2B%3F%23.mkv"))
        assertEquals(path, target.path); assertTrue(target.legacyCompatible)
        assertEquals("/a%2Fb.mkv", recentAccessRecordTarget(file("/a%2Fb.mkv", "/a%252Fb.mkv")).path)
        assertEquals("/a%2Bb", recentAccessWireIdentity("/a+b"))
        assertEquals("/a~b", recentAccessWireIdentity("/%61%7eb"))
    }

    @Test fun originalBytesRemainDistinctEvenWhenTheirDisplayNamesMatch() {
        val first = recentAccessRecordTarget(file("/中文.mkv", "/%D6%D0%CE%C4.mkv"))
        val second = recentAccessRecordTarget(file("/中文.mkv", "/%E4%B8%AD%E6%96%87.mkv"))
        assertFalse(first.legacyCompatible); assertTrue(second.legacyCompatible)
        assertNotEquals(recentAccessWireIdentity(first.wirePath), recentAccessWireIdentity(second.wirePath))
        assertEquals(first.wirePath, RecentAccessEntry("one", first.path, first.wirePath, "中文.mkv", false, 1).resource().wirePath)
    }

    @Test fun rootTrailingDirectorySeparatorSpacesCaseAndBackslashesArePreserved() {
        assertEquals("/", recentAccessRecordTarget(file("/", "/")).path)
        assertEquals("/Folder/  ", recentAccessRecordTarget(file("/Folder/  /", "/Folder/%20%20/")).path)
        assertEquals("/Folder/a\\b", recentAccessRecordTarget(file("/Folder/a\\b", "/Folder/a%5Cb")).path)
        assertNotEquals(recentAccessWireIdentity("/A"), recentAccessWireIdentity("/a"))
    }

    @Test fun malformedOrAmbiguousPathsAreRejectedBeforeNetworkWrite() {
        listOf("/broken%", "/bad%GG", "//host/file", "/file?query", "/file#fragment", "/a%2Fb", "/bad%00", "/%2E%2E/file").forEach { wire ->
            assertTrue("must reject $wire", runCatching { recentAccessRecordTarget(file("/file", wire)) }.isFailure)
        }
        assertTrue(runCatching { recentAccessRecordTarget(file("/same", "/different")) }.isFailure)
        assertTrue(runCatching { recentAccessRecordTarget(file("/lost�")) }.isFailure)
        assertTrue(runCatching { recentAccessRecordTarget(file("/local").copy(downloadId = "owned-download")) }.isFailure)
    }
}
