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

    @Test fun everyLegalFilenameByteKeepsItsCanonicalIdentity() {
        val unreserved = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
        for (byte in 1..255) {
            if (byte == '/'.code) continue
            // Prefix the byte so a legal dot cannot become a relative segment.
            val escaped = "%%%02x".format(java.util.Locale.ROOT, byte)
            val expected = if (byte.toChar() in unreserved) byte.toChar().toString()
                else "%%%02X".format(java.util.Locale.ROOT, byte)
            assertEquals("byte=$byte", "/byte-$expected", recentAccessWireIdentity("/byte-$escaped"))
        }
        assertEquals("/", recentAccessWireIdentity("/"))
        assertEquals("/Folder/~a", recentAccessWireIdentity("/%46older/%7e%61/"))
        assertEquals("/%D6%D0%CE%C4.txt", recentAccessWireIdentity("/%d6%d0%ce%c4.txt"))
        assertEquals("/%E4%B8%AD%E6%96%87.txt", recentAccessWireIdentity("/%e4%b8%ad%e6%96%87.txt"))
    }

    @Test fun longControlCharacterPathsKeepTheirOriginalByteIdentity() {
        val prefix = "/" + List(16) { "%01".repeat(200) }.joinToString("/")
        repeat(40) { index ->
            val wire = "$prefix/$index.txt"
            assertEquals(wire, recentAccessWireIdentity(wire))
        }
    }

    @Test fun identityNormalizationRetainsEverySegmentSafetyCheck() {
        listOf("", "relative", "//host/file", "/a//b", "/a%00b", "/a%2fb", "/a%2Fb",
            "/a/.", "/a/%2E", "/a/..", "/a/%2e%2E", "/a%", "/a%0", "/a%GG",
            "/a?query", "/a#fragment", "/a b", "/中文", "/a\u0001b").forEach { wire ->
            assertTrue("must reject $wire", runCatching { recentAccessWireIdentity(wire) }.isFailure)
        }
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
