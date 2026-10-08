package io.github.kkwans.nasfilebrowser

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import io.github.kkwans.nasfilebrowser.data.SearchResult
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
internal class FileRenameUiTest : LibraryUiHarness() {
    private fun clickText(label: String) {
        var target = text(label)
        while (!target.isClickable) target = target.parent ?: error("Missing clickable owner for $label")
        assertTrue(target.isEnabled); target.click()
    }
    @Test fun rejectedNameIsRetainedConflictDoesNotOverwriteAndSuccessRefreshesSharedMetadata(): Unit = runBlocking {
        val data = LibraryFixtureData().apply { renameAllowed = true; liveDirectoryListing = true; rejectNextRename = true }
        val old = "/旧名称 +%.png"; val conflict = "/已有.png"; val renamed = "/%2F +# 中文.png"
        data.file(old); data.file(conflict)
        data.tags.put(JSONObject().put("id", "owned-tag").put("name", "测试标签").put("color", "#1767E8").put("paths", JSONArray().put(old)))
        fixture(data) { source ->
            source.favoriteRecords.put(JSONObject().put("id", "web-owned").put("name", "保留自定义收藏名").put("path", old).put("order", 0))
            fileDetails(old.substringAfterLast('/')); action("更多文件操作").click(); action("重命名文件").click()
            fun input() = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 5000) ?: error("Missing name field")
            input().text = renamed.substringAfterLast('/')
            clickText("保存名称")
            withTimeout(5000) { while (data.renameAttempts.size < 1) delay(25); model.fileOperations.state.first { !it.changing && it.error != null } }
            assertEquals(renamed.substringAfterLast('/'), input().text)
            assertTrue(data.files.containsKey(old)); assertFalse(data.files.containsKey(renamed))
            capture("rename-rejected-retained")
            input().text = conflict.substringAfterLast('/')
            clickText("保存名称")
            withTimeout(5000) { while (data.renameAttempts.size < 2) delay(25); model.fileOperations.state.first { !it.changing && it.error != null } }
            assertEquals(2, data.renameAttempts.size)
            assertTrue(model.fileOperations.state.value.error!!.contains("目标已存在"))
            assertEquals(conflict.substringAfterLast('/'), input().text)
            assertTrue(data.files.containsKey(old)); assertTrue(data.files.containsKey(conflict))
            input().text = renamed.substringAfterLast('/')
            clickText("保存名称")
            withTimeout(5000) { model.fileOperations.state.first { !it.changing && it.notice != null } }
            withTimeout(5000) { model.favorites.state.first { !it.loading && it.items.singleOrNull()?.path == renamed } }
            withTimeout(5000) { model.tags.state.first { !it.loading && it.items.singleOrNull()?.paths == listOf(renamed) } }
            assertEquals("web-owned", model.favorites.state.value.items.single().id)
            assertEquals("保留自定义收藏名", model.favorites.state.value.items.single().name)
            assertFalse(data.files.containsKey(old)); assertTrue(data.files.containsKey(renamed))
            assertTrue(data.files.containsKey(conflict))
            assertTrue(data.renameAttempts.last().startsWith("/api/resources" + SearchResult.encodePath(old) + "?"))
            assertTrue(data.renameAttempts.last().contains("destination=%2F%252F%20%2B%23%20%E4%B8%AD%E6%96%87.png"))
            assertTrue(device.wait(Until.gone(By.text("文件详情")), 5000))
            text(renamed.substringAfterLast('/'))
            main { model.retry() }
            withTimeout(5000) { model.state.first { !it.busy && it.files.any { row -> row.path == renamed } } }
            text(renamed.substringAfterLast('/')); capture("rename-confirmed-server-list")
        }
    }
    @Test fun missingRenamePermissionHidesControlAndRejectsDirectCommandBeforeWrite(): Unit = runBlocking {
        val data = LibraryFixtureData(); data.file("/仅查看.png")
        fixture(data) {
            assertFalse(model.state.value.permissions.rename)
            fileDetails("仅查看.png")
            action("更多文件操作").click()
            assertTrue(device.wait(Until.gone(By.desc("重命名文件")), 5000))
            main { model.fileOperations.rename(model.state.value.files.single(), "不能保存.png") }
            withTimeout(5000) { model.fileOperations.state.first { !it.changing && it.error != null } }
            assertTrue(data.renameAttempts.isEmpty())
            assertTrue(data.files.containsKey("/仅查看.png"))
        }
    }
}
