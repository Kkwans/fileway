package io.github.kkwans.nasfilebrowser.data

import org.junit.Assert.*
import org.junit.Test

class TagPathsTest {
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
}
