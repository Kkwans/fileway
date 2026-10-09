package io.github.kkwans.nasfilebrowser.data

import org.junit.Assert.*
import org.junit.Test

class DocumentEditTest {
    @Test fun utf8AndUtf16KeepBomAndOriginalLineEndingsIncludingEmptyContent() {
        for ((encoding, bom) in listOf("UTF-8" to byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()),
            "UTF-16LE" to byteArrayOf(0xFF.toByte(), 0xFE.toByte()), "UTF-16BE" to byteArrayOf(0xFE.toByte(), 0xFF.toByte()))) {
            val bytes = bom + "中文\r\n🙂\r\n".toByteArray(charset(encoding))
            val document = decodeDocumentText(bytes)
            assertEquals("中文\n🙂\n", documentEditorText(document))
            assertArrayEquals(bytes, encodeEditedDocument(documentEditorText(document), document))
            assertArrayEquals(bom + "新\r\n一行\r\n".toByteArray(charset(encoding)), encodeEditedDocument("新\n一行\n", document))
            assertArrayEquals(bom, encodeEditedDocument("", document))
        }
        assertArrayEquals(byteArrayOf(), encodeEditedDocument("", decodeDocumentText("old".toByteArray())))
    }
    @Test fun mixedLineEndingsAreNotNormalizedAndMalformedInputIsRejected() {
        val text = "a\r\nb\nc\rd"
        val document = decodeDocumentText(text.toByteArray())
        assertEquals(DocumentLineEnding.MIXED, documentLineEnding(text))
        assertEquals(text, documentEditorText(document))
        assertArrayEquals(text.toByteArray(), encodeEditedDocument(text, document))
        assertThrows(IllegalStateException::class.java) { encodeEditedDocument("\uD800", document) }
        assertThrows(IllegalArgumentException::class.java) { encodeCreatedDocument("a\u0000b") }
        assertThrows(IllegalArgumentException::class.java) { encodeCreatedDocument("x".repeat(DOCUMENT_TEXT_LIMIT.toInt() + 1)) }
    }
    @Test fun createdUtf8BytesAreRawAndOpaqueParentAndReservedNameArePreserved() {
        val text = "\"quoted\"\n中文 +%"
        assertArrayEquals(text.toByteArray(), encodeCreatedDocument(text))
        assertArrayEquals(byteArrayOf(), encodeCreatedDocument(""))
        val target = createdDocumentTarget(DirectoryCrumb("原目录", "/�", "/%FF"), "%2F +# 中文.txt")
        assertEquals("/�/%2F +# 中文.txt", target.path)
        assertEquals("/%FF/" + SearchResult.encodePath("%2F +# 中文.txt"), target.wirePath)
    }
}
