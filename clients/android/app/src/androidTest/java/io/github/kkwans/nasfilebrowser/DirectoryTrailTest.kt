package io.github.kkwans.nasfilebrowser

import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import io.github.kkwans.nasfilebrowser.app.ClientModel
import io.github.kkwans.nasfilebrowser.app.ResourceRef
import io.github.kkwans.nasfilebrowser.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DirectoryTrailTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)
    @Test fun opaqueAncestorBytesRemainExactAndMissingWireIsEncodedOnlyOnce() {
        val path = "/中/a+b/100%?#"
        val opaque = directoryTrail(path, "/%D6%D0/a+b/100%25%3F%23")
        assertEquals(listOf("/", "/中", "/中/a+b", path), opaque.map { it.path })
        assertEquals(listOf("/", "/%D6%D0", "/%D6%D0/a+b", "/%D6%D0/a+b/100%25%3F%23"), opaque.map { it.wirePath })
        assertEquals(listOf("/", "/%E4%B8%AD", "/%E4%B8%AD/a%2Bb", "/%E4%B8%AD/a%2Bb/100%25%3F%23"), directoryTrail(path, "").map { it.wirePath })
        val ambiguous = directoryTrail("/a/b/c", "/a%2Fb/c")
        assertNull(ambiguous[1].wirePath); assertNull(ambiguous[2].wirePath)
        assertEquals("/a%2Fb/c", ambiguous.last().wirePath)
        assertEquals(listOf(DirectoryCrumb("根目录", "/", "/")), directoryTrail("/", "/"))
    }

    @Test fun collapsedTrailShowsLastTwoAndFullPathDialogJumpsThroughRealTransport(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val source = ClientSessionTest.Fixture("breadcrumb-source")
        val store = ProfileStore(ClientDatabase.get(instrumentation.targetContext), CredentialVault(instrumentation.targetContext))
        val profile = store.save(ServerProfile(name = "Breadcrumb fixture", address = source.url))
        lateinit var model: ClientModel
        activity.scenario.onActivity { model = ViewModelProvider(it)[ClientModel::class.java] }
        val path = "/中/电影/上一层/当前层"
        val wire = "/%D6%D0/%E7%94%B5%E5%BD%B1/%E4%B8%8A%E4%B8%80%E5%B1%82/%E5%BD%93%E5%89%8D%E5%B1%82"
        suspend fun ready(expected: String) = withTimeout(10_000) { model.state.first { !it.busy && it.path == expected && it.connected } }
        fun text(value: String) = device.wait(Until.findObject(By.text(value)), 5_000) ?: error("Missing breadcrumb: $value")
        try {
            withContext(Dispatchers.Main) { model.selectProfile(profile); model.connectDraft(profile.name, source.url, BackendKind.NAS, "one", "fixture-only", "direct") }
            ready("/")
            withContext(Dispatchers.Main) { model.open(ResourceRef(path, wire, "当前层", true, "", 0)) }
            ready(path)
            text("上一层"); text("当前层")
            assertFalse(device.hasObject(By.text(path)))
            text("…").click()
            text("目录路径"); text(path); text("根目录")
            device.executeShellCommand("mkdir -p /sdcard/Download/nfb-client-acceptance")
            device.executeShellCommand("screencap -p /sdcard/Download/nfb-client-acceptance/breadcrumb-full-path.png")
            text("中").click()
            val first = ready("/中")
            assertEquals("/%D6%D0", first.wirePath)
            withContext(Dispatchers.Main) { model.jumpDirectory(DirectoryCrumb("untrusted", "/elsewhere", "/elsewhere")) }
            assertEquals("/中", model.state.value.path)
            text("根目录").click()
            ready("/")
            withContext(Dispatchers.Main) { model.open(ResourceRef(path, wire, "当前层", true, "", 0)) }
            ready(path)
            text("上一层").click()
            val parent = ready("/中/电影/上一层")
            assertEquals(wire.substringBeforeLast('/'), parent.wirePath)
            device.executeShellCommand("screencap -p /sdcard/Download/nfb-client-acceptance/breadcrumb-last-two.png")
        } finally {
            withContext(Dispatchers.Main) { model.disconnect() }
            store.remove(profile); source.close()
        }
    }
}
