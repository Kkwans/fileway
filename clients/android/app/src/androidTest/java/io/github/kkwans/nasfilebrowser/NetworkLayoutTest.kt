package io.github.kkwans.nasfilebrowser

import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import io.github.kkwans.nasfilebrowser.core.NetworkState
import io.github.kkwans.nasfilebrowser.ui.ClientTheme
import io.github.kkwans.nasfilebrowser.ui.LibraryTheme
import io.github.kkwans.nasfilebrowser.ui.ConnectionForm
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.lifecycle.ViewModelProvider
import io.github.kkwans.nasfilebrowser.app.ClientModel
import io.github.kkwans.nasfilebrowser.app.ClientState
import io.github.kkwans.nasfilebrowser.data.ServerProfile
import io.github.kkwans.nasfilebrowser.data.ConnectionMode
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Render the production network section with owned states, without contacting
 * Tailscale or modifying node identity. Real connection acceptance is separate. */
@RunWith(AndroidJUnit4::class)
class NetworkLayoutTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)

    @Test fun networkStatesAlignWithTheFormAndExposeTheirActualActions() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val states = listOf(
            NetworkState(), NetworkState(state = "Starting"),
            NetworkState(state = "NeedsLogin", authUrl = "https://login.tailscale.com/owned-layout-fixture"),
            NetworkState(state = "Running", ips = listOf("192.0.2.10"), authenticated = true),
        )
        var displayed by mutableStateOf(states.first())
        var dark by mutableStateOf(false)
        val page = ClientState(profile = ServerProfile(name = "布局验收", address = "https://nas.example.test", network = ConnectionMode.TAILNET))
        activity.scenario.onActivity { host ->
            val model = ViewModelProvider(host)[ClientModel::class.java]
            host.setContent {
                ClientTheme(darkTheme = dark) { LibraryTheme {
                    Box(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
                        ConnectionForm(model, page, displayed)
                    }
                } }
            }
        }
        device.executeShellCommand("mkdir -p /sdcard/Download/nfb-client-acceptance")
        for (isDark in listOf(false, true)) for (state in states) {
            activity.scenario.onActivity { host ->
                dark = isDark; displayed = state
                val bar = if (isDark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                host.enableEdgeToEdge(statusBarStyle = bar, navigationBarStyle = bar)
                host.window.isNavigationBarContrastEnforced = false
            }
            assertTrue(device.wait(Until.hasObject(By.text(state.label)), 5000))
            instrumentation.waitForIdleSync()
            device.waitForIdle(2000)
            device.executeShellCommand("screencap -p /sdcard/Download/nfb-client-acceptance/network-${state.state.lowercase()}-${if (isDark) "dark" else "light"}.png")
            val field = device.findObjects(By.clazz("android.widget.EditText")).first().visibleBounds
            val title = device.findObjects(By.text("Tailscale")).last().visibleBounds
            assertEquals("Network heading must share the field's left edge", field.left.toLong(), title.left.toLong())
            val actionText = when (state.state) { "Starting" -> "正在连接"; "NeedsLogin" -> "打开登录页"; "Running" -> null; else -> "连接 Tailscale" }
            if (actionText != null) {
                val button = device.wait(Until.findObject(By.res("network-primary-action")), 5000)
                    ?: error("Missing network button: $actionText")
                assertEquals(field.left.toLong(), button.visibleBounds.left.toLong())
                assertEquals(field.right.toLong(), button.visibleBounds.right.toLong())
                assertEquals(state.state != "Starting", button.isEnabled)
            }
            if (state.state == "NeedsLogin") assertTrue(device.hasObject(By.text("复制登录链接")))
            if (state.connected) {
                assertTrue(device.hasObject(By.text("网络详情")))
                assertTrue(device.hasObject(By.desc("Tailscale 操作")))
            }
        }
    }
}
