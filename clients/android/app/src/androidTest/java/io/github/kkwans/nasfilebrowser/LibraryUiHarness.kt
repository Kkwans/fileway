package io.github.kkwans.nasfilebrowser

import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import io.github.kkwans.nasfilebrowser.app.ClientModel
import io.github.kkwans.nasfilebrowser.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Rule

internal open class LibraryUiHarness {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)
    protected val instrumentation = InstrumentationRegistry.getInstrumentation()
    protected val device get() = UiDevice.getInstance(instrumentation)
    protected lateinit var model: ClientModel
    protected suspend fun main(action: () -> Unit) = withContext(Dispatchers.Main) { action() }
    protected fun text(label: String): UiObject2 { device.waitForIdle(); return device.wait(Until.findObject(By.text(label)), 5000) ?: missing(label) }
    protected fun action(label: String): UiObject2 { device.waitForIdle(); return device.wait(Until.findObject(By.desc(label)), 5000) ?: missing(label) }
    private fun missing(label: String): Nothing {
        capture("library-missing-action")
        device.dumpWindowHierarchy(java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "library-missing-action.xml"))
        error("Missing visible action $label; tab=${model.state.value.tab}, busy=${model.state.value.busy}, tagsLoaded=${model.tags.state.value.loaded}")
    }
    protected suspend fun fixture(data: LibraryFixtureData, block: suspend (ClientSearchTest.Fixture) -> Unit) {
        val source = ClientSearchTest.Fixture(data.files.keys.filter { it.substringBeforeLast('/').isEmpty() }.map { it.substringAfterLast('/') }, library = data)
        val store = ProfileStore(ClientDatabase.get(instrumentation.targetContext), CredentialVault(instrumentation.targetContext))
        val profile = store.save(ServerProfile(name = "Owned library UI fixture", address = source.url))
        activity.scenario.onActivity { model = ViewModelProvider(it)[ClientModel::class.java] }
        try {
            main { model.selectProfile(profile); model.connectDraft(profile.name, source.url, BackendKind.NAS, "one", "fixture-only", "direct") }
            withTimeout(10_000) { model.state.first { it.connected && !it.busy } }
            withTimeout(5000) {
                while (true) { var focused = false; activity.scenario.onActivity { focused = it.hasWindowFocus() }; if (focused) break; delay(50) }
            }
            block(source)
        } finally { withContext(NonCancellable) { main { model.disconnect() }; store.remove(profile); source.close() } }
    }
    protected fun capture(name: String) {
        device.executeShellCommand("mkdir -p /sdcard/Download/nfb-client-acceptance")
        device.executeShellCommand("screencap -p /sdcard/Download/nfb-client-acceptance/$name.png")
    }
}
