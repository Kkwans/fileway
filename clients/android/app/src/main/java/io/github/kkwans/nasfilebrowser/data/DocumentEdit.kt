package io.github.kkwans.nasfilebrowser.data

import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

enum class DocumentLineEnding(val label: String, val separator: String?) {
    LF("LF", "\n"), CRLF("CRLF", "\r\n"), CR("CR", "\r"), MIXED("混合换行", null)
}

fun documentLineEnding(value: String): DocumentLineEnding {
    var crlf = false; var cr = false; var lf = false; var index = 0
    while (index < value.length) {
        when (value[index]) {
            '\r' -> if (index + 1 < value.length && value[index + 1] == '\n') { crlf = true; index++ } else cr = true
            '\n' -> lf = true
        }
        index++
    }
    if (listOf(crlf, cr, lf).count { it } > 1) return DocumentLineEnding.MIXED
    return when { crlf -> DocumentLineEnding.CRLF; cr -> DocumentLineEnding.CR; else -> DocumentLineEnding.LF }
}

fun documentEditorText(document: DocumentText): String = if (documentLineEnding(document.text) == DocumentLineEnding.MIXED) document.text
    else document.text.replace("\r\n", "\n").replace('\r', '\n')

private fun strictDocumentBytes(text: String, encoding: String, bom: Boolean): ByteArray {
    require(encoding in setOf("UTF-8", "UTF-16LE", "UTF-16BE")) { "不支持保存此编码，请从原文件重新读取" }
    require(text.length.toLong() * (if (encoding == "UTF-8") 1 else 2) <= DOCUMENT_TEXT_LIMIT) { "文本超过 10 MiB 保存上限，请减少内容" }
    require(text.none { it < ' ' && it !in setOf('\n', '\r', '\t', '\u000C') }) { "文本包含不可保存的二进制控制字符" }
    val buffer = try {
        charset(encoding).newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(text))
    } catch (_: java.nio.charset.CharacterCodingException) { error("输入包含无法按原编码保存的文字，请检查后重试") }
    val prefix = if (!bom) byteArrayOf() else when (encoding) {
        "UTF-8" -> byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        "UTF-16LE" -> byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        else -> byteArrayOf(0xFE.toByte(), 0xFF.toByte())
    }
    require(buffer.remaining().toLong() + prefix.size <= DOCUMENT_TEXT_LIMIT) { "文本超过 10 MiB 保存上限，请减少内容" }
    return prefix + ByteArray(buffer.remaining()).also { buffer.get(it) }
}

fun encodeEditedDocument(draft: String, original: DocumentText): ByteArray {
    val ending = documentLineEnding(original.text)
    val content = if (ending.separator == null) draft else draft.replace("\r\n", "\n").replace('\r', '\n').replace("\n", ending.separator)
    return strictDocumentBytes(content, original.encoding, original.bom)
}
fun encodeCreatedDocument(content: String): ByteArray = strictDocumentBytes(content, "UTF-8", false)
fun createdDocumentTarget(parent: DirectoryCrumb, name: String): DirectoryCrumb = directoryCreationTarget(parent, name)
