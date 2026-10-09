package io.github.kkwans.nasfilebrowser

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.kkwans.nasfilebrowser.app.*
import io.github.kkwans.nasfilebrowser.data.*
import io.github.kkwans.nasfilebrowser.ui.ClientTheme
import io.github.kkwans.nasfilebrowser.ui.LibraryTheme
import io.github.kkwans.nasfilebrowser.ui.RecentAccessContent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
internal class RecentAccessUiTest : LibraryUiHarness() {
    @Test fun failedReadRetriesThenOpensOriginalBytesAndShowsEmptyState(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val profile = ServerProfile(name = "Owned recent UI", address = "https://recent.invalid")
        val token = "owned." + android.util.Base64.encodeToString("{\"user\":{\"id\":1,\"username\":\"fixture\"}}".toByteArray(),
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP) + ".fixture"
        var failed = true
        var rows = JSONArray().put(JSONObject().put("id", "owned-recent").put("path", "/中文/100% +?#.mkv")
            .put("wirePath", "/%D6%D0%CE%C4/100%25%20%2B%3F%23.mkv").put("name", "100% +?#.mkv")
            .put("isDir", false).put("accessedAt", 1_800_000_000_000L))
        val api = NasSession.restore(profile, token, 1) { command ->
            when (command.getString("op")) {
                "open" -> "owned-recent-ui"
                "token" -> token
                "request" -> {
                    check(command.getString("method") == "GET" && command.getString("endpoint") == "/api/recent?limit=100")
                    JSONObject().put("status", if (failed) 403 else 200).put("body", if (failed) "forbidden" else rows.toString())
                }
                else -> error("Unexpected owned-fixture operation")
            }
        }
        val context = SessionContext(profile, AccountRecord("owned", profile.id, 0, 1, "fixture", "fixture-only", 0), api, 1, "owned-recent-ui")
        val controller = RecentAccessController(scope) { it === context }
        var opened: RecentAccessEntry? = null
        activity.scenario.onActivity { host ->
            model = ViewModelProvider(host)[ClientModel::class.java]
            host.setContent { ClientTheme(darkTheme = false) { LibraryTheme {
                Surface(Modifier.safeDrawingPadding()) {
                    RecentAccessContent(controller, connected = true, onConnect = {}, onOpen = { opened = it })
                }
            } } }
        }
        try {
            main { controller.bind(context); controller.setVisible(true) }
            withTimeout(5000) { controller.state.first { it.error != null } }
            text("无法读取最近访问")
            main { failed = false }
            text("重试").click()
            withTimeout(5000) { controller.state.first { it.loaded && !it.loading } }
            text("100% +?#.mkv").click()
            main { assertEquals("/%D6%D0%CE%C4/100%25%20%2B%3F%23.mkv", opened?.resource()?.wirePath) }
            main { rows = JSONArray(); controller.refresh() }
            withTimeout(5000) { controller.state.first { !it.loading && it.items.isEmpty() } }
            text("还没有最近访问")
        } finally { scope.cancel() }
    }
}
