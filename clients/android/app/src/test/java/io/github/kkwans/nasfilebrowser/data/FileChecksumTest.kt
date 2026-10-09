package io.github.kkwans.nasfilebrowser.data

import org.junit.Assert.*
import org.junit.Test

class FileChecksumTest {
    @Test fun pastedDigestMustMatchSelectedAlgorithmAndCannotHideNonHexCharacters() {
        assertEquals("a".repeat(64), normalizedChecksum("  " + "A".repeat(64) + "\n", ChecksumAlgorithm.SHA256))
        assertNull(normalizedChecksum("a".repeat(40), ChecksumAlgorithm.SHA256))
        assertNull(normalizedChecksum("a".repeat(31) + "g", ChecksumAlgorithm.MD5))
        assertNull(normalizedChecksum("a".repeat(20) + " " + "a".repeat(19), ChecksumAlgorithm.SHA1))
        assertNull(normalizedChecksum("", ChecksumAlgorithm.MD5))
    }
}
