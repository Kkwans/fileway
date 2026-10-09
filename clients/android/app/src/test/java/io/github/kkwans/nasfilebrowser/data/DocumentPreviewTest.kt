package io.github.kkwans.nasfilebrowser.data

import io.github.kkwans.nasfilebrowser.app.ResourceRef
import org.junit.Assert.*
import org.junit.Test

class DocumentPreviewTest {
    @Test fun strictBomDecodingPreservesContentAndNeverGuessesBrokenText() {
        val value = "中文\r\n🙂\n"
        assertEquals(value, decodeDocumentText(value.toByteArray()).text)
        assertEquals(value, decodeDocumentText(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + value.toByteArray()).text)
        for ((encoding, bom) in listOf("UTF-16LE" to byteArrayOf(0xFF.toByte(), 0xFE.toByte()), "UTF-16BE" to byteArrayOf(0xFE.toByte(), 0xFF.toByte()))) {
            val decoded = decodeDocumentText(bom + value.toByteArray(charset(encoding)))
            assertEquals(value, decoded.text); assertEquals(encoding, decoded.encoding)
        }
        assertThrows(IllegalStateException::class.java) { decodeDocumentText(byteArrayOf(0xD6.toByte(), 0xD0.toByte())) }
        assertThrows(IllegalArgumentException::class.java) { decodeDocumentText(byteArrayOf(0, 1, 2)) }
        assertThrows(IllegalArgumentException::class.java) { decodeDocumentText(ByteArray(DOCUMENT_TEXT_LIMIT.toInt() + 1)) }
        assertEquals("", decodeDocumentText(byteArrayOf()).text)
    }
    @Test fun searchAndLongLinesPreserveOffsetsWithoutUnboundedMatches() {
        val text = "a".repeat(4095) + "🙂" + "\nNeedle\nneedle"
        val document = decodeDocumentText(text.toByteArray())
        assertEquals(text, document.blocks.joinToString("") { it.text })
        assertFalse(document.blocks.first().text.last().isHighSurrogate())
        val found = searchDocumentText(text, "needle")
        assertEquals(listOf(text.indexOf("Needle"), text.indexOf("needle")), found.matches.map { it.start })
        assertFalse(found.truncated)
        val blankLines = decodeDocumentText("\n".repeat(100_000).toByteArray())
        assertTrue("Blank lines must not allocate one block each", blankLines.blocks.size < 100)
        assertThrows(IllegalArgumentException::class.java) { searchDocumentText(text, "x".repeat(1025)) }
        val bounded = searchDocumentText("x".repeat(3000), "x")
        assertEquals(2000, bounded.matches.size); assertTrue(bounded.truncated)
    }
    @Test fun opaqueSourcesAndBitmapBudgetsAreKeptExact() {
        val file = ResourceRef("/�/notes.txt", "/%FF/notes.txt", "notes.txt", false, "text", 10)
        assertEquals("/%FF/notes.txt", documentWirePath(file))
        assertThrows(IllegalArgumentException::class.java) { documentWirePath(file.copy(wirePath = "/%FF/%2E%2E/notes.txt")) }
        for (memory in listOf(32, 128, 256)) {
            val (width, height) = documentBitmapDimensions(1000, 100000, 4096, memory)
            val budget = (memory.toLong() * CACHE_MB / 16).coerceIn(4 * CACHE_MB, 16 * CACHE_MB)
            assertTrue(width.toLong() * height * 4 <= budget)
            assertTrue(width > 0 && height > 0)
        }
        val extreme = documentBitmapDimensions(1, Int.MAX_VALUE, 4096, 32)
        assertTrue(extreme.first.toLong() * extreme.second * 4 <= 4 * CACHE_MB)
    }
}
