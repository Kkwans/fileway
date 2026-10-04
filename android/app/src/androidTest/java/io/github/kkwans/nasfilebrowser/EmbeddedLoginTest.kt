package io.github.kkwans.nasfilebrowser

import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.kkwans.nasfilebrowser.core.EmbeddedNetwork
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import io.github.kkwans.nasfilebrowser.core.NetworkState
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until

/** Actual JNI/tsnet first-login smoke test. Does not authorize a node or use an auth key. */
@RunWith(AndroidJUnit4::class)
class EmbeddedLoginTest {
    @Test fun freshNodeProvidesOfficialLoginWithoutClaimingAuthentication() = runBlocking {
        // This contacts official control servers but never approves a node.
        // Run separately on an owned fresh installation, not as an offline fixture.
        assumeTrue(InstrumentationRegistry.getArguments().getString("embeddedLoginAcceptance") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val network = EmbeddedNetwork(context)
        try {
            var state = network.start()
            assertFalse(state.canLogout)
            network.stop()
            val stopped = network.status()
            assertEquals("Stopped", stopped.state)
            assertTrue(stopped.authUrl.isEmpty())
            state = network.start()
            assertFalse(state.canLogout)
            withTimeout(30_000) {
                while (state.authUrl.isEmpty()) {
                    assertFalse("Fresh node must not claim connected", state.connected)
                    assertFalse("Fresh node must not offer logout", state.canLogout)
                    delay(250)
                    state = network.status()
                }
            }
            val url = Uri.parse(state.authUrl)
            assertEquals("https", url.scheme)
            assertTrue(url.host == "login.tailscale.com" || url.host == "tailscale.com" || url.host?.endsWith(".tailscale.com") == true)
            assertEquals("NeedsLogin", state.state)
            assertFalse(state.connected)
            assertFalse(state.canLogout)
        } finally {
            network.stop()
        }
    }

    @Test fun connectAndCancelUiUseTheActualEmbeddedLoginFlow() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("embeddedLoginAcceptance") == "true")
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        ActivityScenario.launch(MainActivity::class.java).use {
            fun click(text: String) {
                repeat(4) {
                    val target = device.wait(Until.findObject(By.text(text)), 1_000)
                    if (target != null) { target.click(); return }
                    device.findObject(By.scrollable(true))?.scroll(Direction.DOWN, 0.65f)
                }
                fail("Missing reachable action: $text")
            }
            click("Tailscale")
            click("连接 Tailscale")
            assertTrue("Official login entry must appear", device.wait(Until.hasObject(By.text("打开登录页")), 30_000))
            assertFalse("Never offer logout before authentication", device.hasObject(By.text("退出 Tailscale 账号")))
            device.executeShellCommand("mkdir -p /sdcard/Download/nfb-client-acceptance")
            device.executeShellCommand("screencap -p /sdcard/Download/nfb-client-acceptance/embedded-first-login.png")
            click("取消连接")
            assertTrue(device.wait(Until.hasObject(By.text("应用内 Tailscale · 未连接")), 10_000))
            assertFalse(device.hasObject(By.text("打开登录页")))
            assertFalse(device.hasObject(By.text("退出 Tailscale 账号")))
        }
    }

    @Test fun logoutRequiresConfirmedIdentityInsteadOfAnErrorLabel() {
        for (state in listOf("Unconfigured", "Configured", "Starting", "NeedsLogin", "Error", "Stopped")) {
            assertFalse(NetworkState(state = state).canLogout)
        }
        assertTrue(NetworkState(state = "Stopped", authenticated = true).canLogout)
        assertTrue(NetworkState(state = "Error", authenticated = true).canLogout)
        assertFalse(NetworkState(state = "Starting", authenticated = true).canLogout)
    }
}
