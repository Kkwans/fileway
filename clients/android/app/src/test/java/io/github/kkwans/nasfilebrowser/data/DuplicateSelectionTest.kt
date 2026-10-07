package io.github.kkwans.nasfilebrowser.data

import org.junit.Assert.*
import org.junit.Test

class DuplicateSelectionTest {
    @Test fun incompleteOldOrLinkedReportsCannotOfferCleanup() {
        val regular = 1L to 0x81A4
        assertTrue(duplicateGroupCanBeCleaned(3, 2, "oldest-created", listOf(regular, regular)))
        assertFalse(duplicateGroupCanBeCleaned(2, 2, "oldest-created", listOf(regular, regular)))
        assertFalse(duplicateGroupCanBeCleaned(3, 3, "truncated", listOf(regular, regular)))
        assertFalse(duplicateGroupCanBeCleaned(3, 2, "missing-created", listOf(regular, null)))
        assertFalse(duplicateGroupCanBeCleaned(3, 2, "oldest-created", listOf(regular, 2L to 0x81A4)))
        assertFalse(duplicateGroupCanBeCleaned(3, 2, "oldest-created", listOf(regular, 1L to 0xA1FF)))
        assertTrue("A timestamp tie requires explicit user selection but is otherwise cleanable", duplicateGroupCanBeCleaned(3, 2, "tied-created", listOf(regular, regular)))
    }
}
