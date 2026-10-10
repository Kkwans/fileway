package io.github.kkwans.nasfilebrowser.data

import org.junit.Assert.*
import org.junit.Test
import io.github.kkwans.nasfilebrowser.app.ResourceRef

class TagPathsTest {
    @Test fun sameDisplayReferencesAndOpaqueParentPrefixesAreIndependent() {
        val opaque = tagPathRef("/中文/中.txt", "/%D6%D0%CE%C4/%D6%D0.txt", true)
        val utf8 = tagPathRef("/中文/中.txt", "/%E4%B8%AD%E6%96%87/%E4%B8%AD.txt", true)
        val file = ResourceRef(opaque.path, opaque.wirePath, "中.txt", false, "", 0)
        assertTrue(taggedResourceMatches(file, listOf(opaque)))
        assertFalse(taggedResourceMatches(file, listOf(utf8)))
        assertTrue(taggedResourceMatches(ResourceRef("/中文", "/%D6%D0%CE%C4", "中文", true, "", 0), listOf(opaque), true))
        assertFalse(taggedResourceMatches(ResourceRef("/中文", "/%E4%B8%AD%E6%96%87", "中文", true, "", 0), listOf(opaque), true))
        assertFalse(tagPathRef("/lost�").openable)
        assertTrue(tagPathRef("/lost�", "/lost%EF%BF%BD", true).openable)
        assertFalse(tagPathRef("/lost�", "/lost%EF%BF%BD", false).openable)
    }
    @Test fun globalTagNavigationIncludesAncestorsAndChildrenButNotSimilarPrefixes() {
        val paths = listOf("/films/中文.mkv", "/photos/album")
        assertTrue(taggedPathMatches("/films", paths, true))
        assertTrue(taggedPathMatches("/photos/album/one.jpg", paths, true))
        assertFalse(taggedPathMatches("/photos/album-two/one.jpg", paths, true))
        assertFalse(taggedPathMatches("/films", paths, false))
        assertTrue(taggedPathMatches("/films/中文.mkv", paths, false))
    }
    @Test fun comparisonsPreserveLegalSpacesCaseAndLiteralPercentNames() {
        assertEquals("/folder/  ", collectionPath("/folder/  "))
        assertEquals("/A/100%#?.png", collectionPath("//A/./100%#?.png"))
        assertFalse(taggedPathMatches("/a/photo.png", listOf("/A/photo.png"), false))
        assertFalse(taggedPathMatches("/folder/one", listOf("/folder/one "), false))
    }
    @Test fun immutableReferencesReuseTheirIdentityWithoutSharingCopies() {
        val wire = "/" + List(16) { "%01".repeat(200) }.joinToString("/") + "/owned.txt"
        val ref = TagPathRef("/owned", wire, true)
        val identity = ref.identity
        assertEquals(wire, identity)
        repeat(10) { assertSame(identity, ref.identity) }
        val sibling = ref.copy(wirePath = "/%FF.txt")
        assertEquals("/%FF.txt", sibling.identity)
        assertEquals(identity, ref.identity)
        assertNull(ref.copy(openable = false).identity)
        assertEquals("/", ref.copy(wirePath = "/").identity)
    }
    @Test fun closedReferencesSkipValidationAndOpenReferencesStillValidateOnAccess() {
        val closed = TagPathRef("/unknown", "/bad%GG", false)
        assertNull(closed.identity)
        val opened = closed.copy(openable = true)
        repeat(2) { assertTrue(runCatching { opened.identity }.isFailure) }
        assertNull(closed.identity)
    }
}
