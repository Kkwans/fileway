package io.github.kkwans.nasfilebrowser

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import io.github.kkwans.nasfilebrowser.data.FileLayout
import io.github.kkwans.nasfilebrowser.ui.collectionDescription
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Previews stay unobstructed; only the regular list has a per-item action button. */
@RunWith(AndroidJUnit4::class)
internal class FileActionsUiTest : LibraryUiHarness() {
    @Test fun listMenuAndLongPressOpenDetailsWithoutCoveringPreviewsAndSaveServerTags(): Unit = runBlocking {
        val data = LibraryFixtureData(); val path = "/中文 #? %.png"
        data.file(path)
        data.tags.put(JSONObject().put("id", "tag-owned").put("name", "本次测试").put("color", "#3F72D8").put("paths", JSONArray()))
        fixture(data) {
            for (layout in FileLayout.entries) {
                main { model.fileLayout(layout) }
                withTimeout(5000) { model.state.first { it.fileLayout == layout } }
                assertTrue("The requested layout must be rendered before locating its button",
                    device.wait(Until.hasObject(By.desc(layout.collectionDescription())), 5000))
                val label = "文件操作：${path.substringAfterLast('/')}"
                if (layout == FileLayout.LIST) {
                    var menu = action(label)
                    while (!menu.isClickable) menu = menu.parent ?: error("File action has no clickable owner")
                    assertTrue("The button must retain a 48dp target", menu.visibleBounds.width() >= (48 * instrumentation.targetContext.resources.displayMetrics.density).toInt() - 2)
                    menu.click()
                } else {
                    assertTrue("Preview layouts must not have per-file buttons", device.wait(Until.gone(By.desc(label)), 5000))
                    text(path.substringAfterLast('/')).longClick()
                }
                text("文件详情"); action("设置文件标签")
                capture("file-actions-${layout.name.lowercase()}")
                text("关闭").click()
                assertTrue("The previous dialog must be gone before the next layout",
                    device.wait(Until.gone(By.text("文件详情")), 5000))
                withTimeout(5000) {
                    while (true) {
                        var focused = false
                        activity.scenario.onActivity { focused = it.hasWindowFocus() }
                        if (focused) break
                        delay(25)
                    }
                }
            }
            text(path.substringAfterLast('/')).longClick(); text("文件详情")
            action("设置文件标签").click(); text("本次测试").click(); text("保存标记").click()
            withTimeout(5000) { model.tags.state.first { !it.changing && it.items.singleOrNull()?.paths?.contains(path) == true } }
            assertTrue(data.mutations.contains("POST" to "/api/tags/tag-owned/paths"))
            assertFalse(data.mutations.any { it.first == "DELETE" })
        }
    }
}
