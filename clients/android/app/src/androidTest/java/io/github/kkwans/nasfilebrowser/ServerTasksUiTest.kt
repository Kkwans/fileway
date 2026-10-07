package io.github.kkwans.nasfilebrowser

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.kkwans.nasfilebrowser.data.TaskView
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
internal class ServerTasksUiTest : LibraryUiHarness() {
    @Test fun retryCancelAndArchiveFollowRealNativeControlsAndServerIds(): Unit = runBlocking {
        val data = LibraryFixtureData()
        repeat(35) { data.tasks.put(data.task("done-$it", "analysis.storage", "completed", "已完成样本 $it")) }
        data.tasks.put(data.task("failed-storage", "analysis.storage", "failed", "存储统计样本").put("createdAt", System.currentTimeMillis() + 1000).put("error", "Owned failure"))
        fixture(data) {
            action("资料库导航").click(); action("任务资料页").click()
            withTimeout(5000) { model.tasks.state.first { it.loaded && !it.loading } }
            assertEquals(30, model.tasks.state.value.items.size)
            main { model.tasks.refresh(more = true) }
            withTimeout(5000) { model.tasks.state.first { !it.paging && it.items.size == 36 } }
            assertEquals("", model.tasks.state.value.nextCursor)
            main { model.tasks.filter(model.tasks.state.value.filter.copy(view = TaskView.ATTENTION)) }
            text("存储统计样本"); text("详情").click(); text("重试").click(); text("确认").click()
            val retried = withTimeout(5000) { model.tasks.state.first { !it.changing && it.selected?.id?.startsWith("retry-") == true } }.selected!!
            assertEquals("queued", retried.status)
            text("停止任务").click(); text("确认").click()
            withTimeout(5000) { model.tasks.state.first { !it.changing && it.selected?.status == "canceled" } }
            text("归档").click(); text("确认").click()
            withTimeout(5000) { model.tasks.state.first { !it.changing && (it.selected?.archivedAt ?: 0) > 0 } }
            assertTrue(data.mutations.contains("POST" to "/api/tasks/failed-storage/retry"))
            assertTrue(data.mutations.contains("POST" to "/api/tasks/${retried.id}/cancel"))
            assertTrue(data.mutations.contains("POST" to "/api/tasks/${retried.id}/archive"))
            capture("tasks-phone")
        }
    }
}
