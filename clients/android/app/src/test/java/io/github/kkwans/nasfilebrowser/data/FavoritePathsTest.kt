package io.github.kkwans.nasfilebrowser.data

import io.github.kkwans.nasfilebrowser.app.ResourceRef
import org.junit.Assert.*
import org.junit.Test

class FavoritePathsTest {
    @Test fun opaqueDisplaySiblingsHaveDifferentFavoriteIdentities() {
        val opaque = favoriteRecordTarget(ResourceRef("/中文.txt", "/%D6%D0%CE%C4.txt", "中文.txt", false, "", 0))
        val utf8 = favoriteRecordTarget(ResourceRef("/中文.txt", "/%E4%B8%AD%E6%96%87.txt", "中文.txt", false, "", 0))
        assertFalse(opaque.legacyCompatible); assertTrue(utf8.legacyCompatible)
        assertNotEquals(favoriteWireIdentity(opaque.wirePath), favoriteWireIdentity(utf8.wirePath))
        assertEquals("/a%252Fb%2B%23%3F", favoriteRecordTarget(ResourceRef("/a%2Fb+#?", "", "owned", false, "", 0)).wirePath)
    }
    @Test fun lostLegacyBytesStayClosedButActualUnicodeReplacementCharacterIsOpenable() {
        assertFalse(favoritePathIdentity("/lost�", null, null).openable)
        assertFalse(favoritePathIdentity("/lost�", "/lost%EF%BF%BD", false).openable)
        assertTrue(favoritePathIdentity("/lost�", "/lost%EF%BF%BD", true).openable)
        assertTrue(favoritePathIdentity("/known.txt", null, null).openable)
        assertFalse(favoritePathIdentity("/known.txt", null, true).openable)
    }
    @Test fun invalidOrContradictoryPathsCannotSelectACollectionSibling() {
        for (wire in listOf("/bad%GG", "/a%2Fb", "//host/file", "/bad%00", "/different")) {
            assertFalse(wire, favoritePathIdentity("/same", wire, true).openable)
        }
        assertFalse(favoritePathIdentity("/same", "/same", false).openable)
        assertTrue(runCatching { favoriteRecordTarget(ResourceRef("/local", "/local", "local", false, "", 0, downloadId = "owned")) }.isFailure)
    }
}
