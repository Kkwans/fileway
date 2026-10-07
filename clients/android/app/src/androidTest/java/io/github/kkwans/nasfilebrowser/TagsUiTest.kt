package io.github.kkwans.nasfilebrowser

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
internal class TagsUiTest : LibraryUiHarness() {
    @Test fun multiTagWritesUseUnicodePathsAndFilterTheNativeFileList(): Unit = runBlocking {
        val data = LibraryFixtureData(); val path = "/中文 #? %.png"
        data.file(path); data.file("/未标记.png")
        data.tags.put(JSONObject().put("id", "tag-movie").put("name", "电影").put("color", "#3F72D8").put("paths", JSONArray()))
        data.tags.put(JSONObject().put("id", "tag-photo").put("name", "照片").put("color", "#E5484D").put("paths", JSONArray()))
        fixture(data) {
            text(path.substringAfterLast('/')).longClick()
            action("设置文件标签").click(); text("电影").click(); text("照片").click(); text("保存标记").click()
            withTimeout(5000) { model.tags.state.first { !it.changing && it.items.count { tag -> path in tag.paths } == 2 } }
            assertTrue(data.mutations.contains("POST" to "/api/tags/tag-movie/paths"))
            assertTrue(data.mutations.contains("POST" to "/api/tags/tag-photo/paths"))
            text("关闭").click(); action("资料库导航").click(); action("标签资料页").click()
            withTimeout(5000) { model.tags.state.first { !it.pathsLoading && it.paths.singleOrNull()?.file?.path == path } }
            text(path.substringAfterLast('/')); capture("tags-phone")
            text("筛选文件页").click()
            withTimeout(5000) { model.state.first { it.tab == "files" } }
            assertEquals(listOf(path), model.directoryItems().map { it.path })
            assertFalse(device.hasObject(By.text("未标记.png")))
            text("清除筛选").click(); text("未标记.png")
        }
    }
}
