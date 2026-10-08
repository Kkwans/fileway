package io.github.kkwans.nasfilebrowser

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
internal class TrashUiTest : LibraryUiHarness() {
    @Test fun moveRestoreAndUndoDeletionKeepSharedMetadataAndUsePendingTaskEndpoint(): Unit = runBlocking {
        val data = LibraryFixtureData(); val path = "/回收样本.png"
        data.file(path)
        data.tags.put(JSONObject().put("id", "tag-saved").put("name", "照片").put("color", "#E5484D").put("paths", JSONArray().put(path)))
        fixture(data) { source ->
            source.favoriteRecords.put(JSONObject().put("id", "web-saved").put("name", "我的照片").put("path", path).put("order", 0))
            text("回收样本.png").longClick(); action("移入回收站").click(); text("移入回收站").click()
            withTimeout(5000) { model.trash.state.first { !it.changing && it.items.size == 1 } }
            assertEquals(0, source.favoriteRecords.length())
            assertEquals(0, data.tags.getJSONObject(0).getJSONArray("paths").length())
            action("资料库导航").click(); action("回收站资料页").click()
            text("回收样本.png"); text("恢复").click()
            withTimeout(5000) { model.trash.state.first { !it.changing && it.items.isEmpty() } }
            assertEquals("web-saved", source.favoriteRecords.getJSONObject(0).getString("id"))
            assertEquals(path, data.tags.getJSONObject(0).getJSONArray("paths").getString(0))
            // Restore conflict options are owned by the server protocol; skipping
            // must leave the same recycle-bin record available.
            action("文件导航").click(); text("回收样本.png").longClick(); action("移入回收站").click(); text("移入回收站").click()
            withTimeout(5000) { model.trash.state.first { !it.changing && it.items.size == 1 } }
            action("资料库导航").click(); action("回收站资料页").click()
            data.conflictOnce = true
            text("恢复").click(); text("跳过恢复").click()
            withTimeout(5000) { model.trash.state.first { !it.changing && it.conflict == null && it.items.size == 1 } }
            text("清空").click(); text("提交删除").click()
            val submitted = withTimeout(5000) { model.trash.state.first { !it.changing && it.lastTask?.pendingDeletion == true } }.lastTask!!
            text("撤销删除").click()
            withTimeout(5000) { model.trash.state.first { !it.changing && it.lastTask?.status == "canceled" } }
            assertEquals(1, data.trash.length())
            assertTrue(data.mutations.contains("POST" to "/api/deletions/pending"))
            assertTrue(data.mutations.contains("POST" to "/api/tasks/${submitted.id}/cancel"))
            assertFalse(data.mutations.any { it.first == "DELETE" && it.second.startsWith("/api/trash/") })
            capture("trash-phone")
        }
    }
}
