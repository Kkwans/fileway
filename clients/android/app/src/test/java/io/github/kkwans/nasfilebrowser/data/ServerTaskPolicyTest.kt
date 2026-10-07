package io.github.kkwans.nasfilebrowser.data

import org.junit.Assert.*
import org.junit.Test

class ServerTaskPolicyTest {
    private fun task(type: String, status: String) = ServerTask("owned", 1, "fixture", type, "fixture", status, 1, 0, 0, 0, 0, 100, 35, 0, 0, "", "", "")
    @Test fun irreversibleDeletionIsNeverRetriedAndCanceledCopyFollowsTheActualBackendRule() {
        assertFalse(task("file.delete.permanent", "failed").canRetry)
        assertFalse(task("trash.delete.permanent", "interrupted").canRetry)
        assertTrue(task("file.copy", "canceled").canRetry)
        assertTrue(task("analysis.duplicates.cleanup", "canceled").canRetry)
        assertFalse(task("analysis.storage", "canceled").canRetry)
        assertFalse(task("media.transcode", "failed").copy(archivedAt = 5).canRetry)
    }
    @Test fun unknownTotalsStayUnknownAndFailedProgressIsNotResetOrCompleted() {
        assertNull(task("analysis.storage", "running").copy(totalItems = 0).progress)
        assertEquals(.35f, task("analysis.storage", "failed").progress!!, .001f)
        assertEquals(1f, task("analysis.storage", "completed").progress!!, 0f)
        assertEquals(.99f, task("media.transcode", "running").copy(duration = 12.0, processedSeconds = 12.0).progress!!, .001f)
        assertFalse(task("file.copy", "running").canArchive)
        assertTrue(task("file.copy", "canceled").canArchive)
    }
    @Test fun filtersEncodeOnlyOnceAndKeepOpaqueKeysetCursor() {
        val query = TaskFilter(owner = "用户 #+", text = "中文 %?", view = TaskView.ATTENTION).query("keyset+/=%")
        assertTrue(query.contains("user=%E7%94%A8%E6%88%B7+%23%2B"))
        assertTrue(query.contains("cursor=keyset%2B%2F%3D%25"))
        assertTrue(query.contains("status=failed%2Cinterrupted"))
    }
}
