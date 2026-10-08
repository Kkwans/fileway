package io.github.kkwans.nasfilebrowser

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import io.github.kkwans.nasfilebrowser.data.SearchResult
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
internal class TrashUiTest : LibraryUiHarness() {
    private fun clickText(label: String) {
        var target = text(label)
        while (!target.isClickable) target = target.parent ?: error("Missing clickable owner for $label")
        assertTrue(target.isEnabled)
        target.click()
    }

    @Test fun batchFailureKeepsOnlyUnconfirmedItemsAndRetryDoesNotRepeatAcknowledgedDeletes(): Unit = runBlocking {
        val data = LibraryFixtureData()
        val first = "/A回收样本 %+.png"; val second = "/B回收样本 #.png"
        data.file(first); data.file(second)
        data.rejectTrashPathOnce = second
        fixture(data) {
            clickText("多选"); clickText("全选"); text("已选 2 项")
            action("批量移入回收站").click(); text("将 2 项移入回收站？")
            clickText("移入回收站")
            withTimeout(5000) { model.trash.state.first { !it.changing && it.error != null } }
            text("将 1 项移入回收站？")
            assertTrue(model.trash.state.value.error!!.contains("1 / 2"))
            assertEquals(listOf(second), model.state.value.files.map { it.path })
            assertEquals(1, data.trash.length())
            assertEquals(listOf(first, second).map { "/api/resources" + SearchResult.encodePath(it) }, data.trashAttempts)
            capture("batch-trash-partial-failure")
            clickText("移入回收站")
            withTimeout(5000) { model.trash.state.first { !it.changing && it.items.size == 2 } }
            assertNull(model.trash.state.value.error)
            assertTrue(model.state.value.files.isEmpty())
            assertEquals(listOf(first, second, second).map { "/api/resources" + SearchResult.encodePath(it) }, data.trashAttempts)
            assertFalse(device.wait(Until.hasObject(By.text("将 1 项移入回收站？")), 300))
            text("已选 0 项")
            capture("batch-trash-completed")
        }
    }

    @Test fun sourceChangeAfterAcknowledgementStopsRemainingTrashWrites(): Unit = runBlocking {
        val data = LibraryFixtureData()
        data.file("/A当前来源.png"); data.file("/B保留文件.png")
        fixture(data) {
            val files = model.state.value.files.toList()
            var acknowledged = 0; var completed = false
            main { model.trash.moveAll(files, { acknowledged++; model.disconnect() }, { completed = true }) }
            withTimeout(5000) { while (acknowledged == 0) delay(25) }
            delay(250)
            assertEquals(1, acknowledged)
            assertFalse(completed)
            assertEquals(1, data.trashAttempts.size)
            assertEquals(1, data.trash.length())
            assertTrue(data.files.containsKey(files[1].path))
            assertTrue(model.trash.state.value.items.isEmpty())
            assertFalse(model.trash.state.value.changing)
            assertNull(model.trash.state.value.notice)
        }
    }

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
