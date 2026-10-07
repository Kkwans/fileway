package io.github.kkwans.nasfilebrowser.app

import org.junit.Assert.*
import org.junit.Test

class MediaQueueTest {
    private fun file(path: String, name: String = path.substringAfterLast('/')) = ResourceRef(path, path, name, false, "", 10)

    @Test fun snapshotPreservesOrderAndOriginalIdentityInsteadOfNames() {
        val a = file("/a/photo.jpg", "photo.jpg")
        val b = file("/b/photo.jpg", "photo.jpg")
        val input = mutableListOf(b, file("/movie.mkv"), a, b)
        val queue = MediaQueue.snapshot(1, "account-a", a, input, MediaQueueSource.SEARCH)!!
        input.clear()
        assertEquals(listOf(b, a), queue.items)
        assertEquals(1, queue.index)
        assertTrue(queue.hasPrevious)
        assertFalse(queue.hasNext)
        assertNull(queue.select(2))
        assertNull(queue.select(-1))
    }

    @Test fun metadataRefreshReplacesOnlyTheSelectedEntryAndKeepsOpaqueWirePath() {
        val old = file("/same.jpg").copy(wirePath = "/%FF.jpg")
        val other = old.copy(wirePath = "/%FE.jpg")
        val current = old.copy(size = 500, modified = "new")
        val queue = MediaQueue.snapshot(2, "account-b", current, listOf(old, other), MediaQueueSource.DIRECTORY)!!
        assertEquals(listOf(current, other), queue.items)
        assertEquals("/%FF.jpg", queue.current.mediaKey)
        assertEquals("account-b", queue.owner)
    }

    @Test fun unlistedRecentMediaBecomesSingletonAndFoldersNeverEnterMediaQueues() {
        val video = file("/recent.MKV")
        val queue = MediaQueue.snapshot(3, "account", video, listOf(file("/other.mkv")), MediaQueueSource.SINGLE)!!
        assertEquals(listOf(video), queue.items)
        assertFalse(queue.hasPrevious || queue.hasNext)
        assertNull(MediaQueue.snapshot(4, "account", video.copy(directory = true), emptyList(), MediaQueueSource.DIRECTORY))
    }
}
