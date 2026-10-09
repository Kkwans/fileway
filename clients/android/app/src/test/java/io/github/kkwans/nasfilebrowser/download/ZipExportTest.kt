package io.github.kkwans.nasfilebrowser.download

import io.github.kkwans.nasfilebrowser.app.ResourceRef
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ZipExportTest {
    private fun bytes(): ByteArray = ByteArrayOutputStream().also { output -> ZipOutputStream(output).use { zip ->
        zip.putNextEntry(ZipEntry("comma,name +%?#.txt")); zip.write("owned payload".toByteArray()); zip.closeEntry()
    } }.toByteArray()
    private fun verify(bytes: ByteArray) = verifyZipExport(bytes.size.toLong()) { offset, size -> bytes.copyOfRange(offset.toInt(), offset.toInt() + size) }

    @Test fun validatesRealZipAndEmptyZipWithoutReadingAllMediaData() {
        verify(bytes())
        val empty = ByteArrayOutputStream().also { ZipOutputStream(it).close() }.toByteArray(); verify(empty)
    }

    @Test fun rejectsTruncatedCentralDirectoryWrongOffsetsAndServerErrorSuffixes() {
        val valid = bytes()
        for (broken in listOf(valid.copyOf(valid.size - 8), valid + "500 server error".toByteArray(), "HTTP200 is not ZIP".toByteArray()))
            assertTrue(runCatching { verify(broken) }.isFailure)
        val offset = valid.copyOf(); offset[offset.size - 22 + 16] = 0x7f
        assertTrue(runCatching { verify(offset) }.isFailure)
    }

    @Test fun validatesZip64EndAndRejectsBrokenZip64Locator() {
        val value = ByteArray(98)
        fun put(offset: Int, number: Long, size: Int = 4) { repeat(size) { value[offset + it] = (number ushr (it * 8)).toByte() } }
        put(0, 0x06064b50); put(4, 44, 8)
        put(56, 0x07064b50); put(72, 1)
        put(76, 0x06054b50); put(84, 0xffff, 2); put(86, 0xffff, 2); put(88, 0xffffffffL); put(92, 0xffffffffL)
        verify(value)
        value[56] = 0
        assertTrue(runCatching { verify(value) }.isFailure)
    }

    @Test fun computesParentAndKeepsSameDisplayByteSiblingsWithoutCommaSplitting() {
        val plan = zipExportPlan(listOf(
            ResourceRef("/中文/comma,name", "/%D6%D0%CE%C4/comma%2Cname", "comma,name", false, "", 1),
            ResourceRef("/中文/100% +", "/%E4%B8%AD%E6%96%87/100%25%20%2B", "100% +", false, "", 2)))
        assertEquals("/", plan.parentWire); assertEquals("/", plan.parentPath); assertEquals(2, plan.files.size)
        val single = zipExportPlan(listOf(ResourceRef("/docs/one.txt", "/docs/one.txt", "one.txt", false, "", 1)))
        assertEquals("/docs", single.parentWire); assertEquals("one.txt.zip", single.suggestedName)
        val nested = zipExportPlan(listOf(ResourceRef("/docs", "/docs", "docs", true, "", 0), ResourceRef("/docs/one", "/docs/one", "one", false, "", 1)))
        assertEquals(1, nested.files.size); assertEquals("/", nested.parentWire)
    }
}
