package io.github.kkwans.nasfilebrowser

import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import io.github.kkwans.nasfilebrowser.app.ClientModel
import io.github.kkwans.nasfilebrowser.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real Compose UI + authenticated JNI HTTP reads/writes against one owned authority. */
@RunWith(AndroidJUnit4::class)
class FavoritesUiTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)
    @Test fun serverCollectionRefreshesAndFileActionsUseTheSameRecord(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val source = ClientSearchTest.Fixture(listOf("sample.png"))
        source.favoriteGroups.put(JSONObject().put("id", "movie-group").put("name", "电影").put("order", 0).put("color", "#3F72D8"))
        source.favoriteRecords.put(JSONObject().put("id", "web-owned-id").put("path", "/sample.png").put("name", "网页已有收藏").put("order", 0).put("groupId", "movie-group"))
        val store = ProfileStore(ClientDatabase.get(instrumentation.targetContext), CredentialVault(instrumentation.targetContext))
        val profile = store.save(ServerProfile(name = "Favorites UI fixture", address = source.url))
        lateinit var model: ClientModel
        activity.scenario.onActivity { model = ViewModelProvider(it)[ClientModel::class.java] }
        fun text(label: String) = device.wait(Until.findObject(By.text(label)), 5000) ?: error("Missing visible $label; tab=${model.state.value.tab}, loading=${model.favorites.state.value.loading}")
        fun action(label: String): androidx.test.uiautomator.UiObject2 {
            device.waitForIdle()
            return device.wait(Until.findObject(By.desc(label)), 5000) ?: error("Missing action $label")
        }
        try {
            withContext(Dispatchers.Main) { model.selectProfile(profile); model.connectDraft(profile.name, source.url, BackendKind.NAS, "one", "fixture-only", "direct") }
            withTimeout(10_000) { model.state.first { it.connected && !it.busy } }
            withTimeout(5000) {
                while (true) {
                    var focused = false
                    activity.scenario.onActivity { focused = it.hasWindowFocus() }
                    if (focused) break
                    delay(50)
                }
            }
            action("资料库导航").click()
            text("收藏夹"); text("网页已有收藏")
            assertEquals("web-owned-id", model.favorites.state.value.items.single().id)
            // Simulate an edit made by another client in the same server record.
            source.favoriteRecords.getJSONObject(0).put("name", "网页端更新的名称")
            action("刷新收藏").click(); text("网页端更新的名称")
            action("文件导航").click(); text("sample.png").longClick()
            action("取消收藏").click()
            withTimeout(5000) { model.favorites.state.first { !it.changing && it.items.isEmpty() } }
            assertEquals(0, source.favoriteRecords.length())
            action("加入收藏").click()
            text("电影").click()
            device.executeShellCommand("mkdir -p /sdcard/Download/nfb-client-acceptance")
            device.executeShellCommand("screencap -p /sdcard/Download/nfb-client-acceptance/favorite-group-picker.png")
            text("保存收藏").click()
            withTimeout(5000) { model.favorites.state.first { !it.changing && it.items.size == 1 } }
            assertEquals("movie-group", source.favoriteRecords.getJSONObject(0).getString("groupId"))
            assertTrue(source.favoriteRecords.getJSONObject(0).getString("id").startsWith("server-"))
            text("关闭").click(); action("资料库导航").click()
            text("收藏夹"); text("sample.png")
            device.executeShellCommand("mkdir -p /sdcard/Download/nfb-client-acceptance")
            device.executeShellCommand("screencap -p /sdcard/Download/nfb-client-acceptance/favorites-phone.png")
        } finally { withContext(Dispatchers.Main) { model.disconnect() }; store.remove(profile); source.close() }
    }
}
