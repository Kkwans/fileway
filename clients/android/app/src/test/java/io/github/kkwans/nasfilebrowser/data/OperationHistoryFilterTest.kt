package io.github.kkwans.nasfilebrowser.data

import org.junit.Assert.*
import org.junit.Test
import java.net.URLDecoder

class OperationHistoryFilterTest {
    @Test fun historyUsesWebFilterNamesMillisecondBoundsAndOpaqueCursor() {
        val cursor = "opaque+/=%?#中文"
        val text = "/中文 #?%/report.txt"
        val query = OperationHistoryFilter(text, "file.rename", OperationHistoryStatus.SUCCESS, 1_800_000_000_000, 1_800_086_399_999).query(cursor, 30)
        assertTrue(query.startsWith("/api/history?"))
        val values = query.substringAfter('?').split('&').associate { entry ->
            val pair = entry.split('=', limit = 2)
            URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair[1], "UTF-8")
        }
        assertEquals(mapOf("limit" to "30", "text" to text, "action" to "file.rename", "status" to "success",
            "from" to "1800000000000", "to" to "1800086399999", "cursor" to cursor), values)
    }
    @Test fun unboundedFilterOmitsOptionalFieldsAndRejectsInvalidRanges() {
        assertEquals("/api/history?limit=30", OperationHistoryFilter().query())
        for (filter in listOf(OperationHistoryFilter(from = -1), OperationHistoryFilter(to = -1), OperationHistoryFilter(from = 20, to = 10))) {
            assertThrows(IllegalArgumentException::class.java) { filter.query() }
        }
        for (limit in listOf(0, 101)) assertThrows(IllegalArgumentException::class.java) { OperationHistoryFilter().query(limit = limit) }
    }
}
