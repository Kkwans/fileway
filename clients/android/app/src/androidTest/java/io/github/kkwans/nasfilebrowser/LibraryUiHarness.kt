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
import org.junit.rules.RuleChain

internal open class LibraryUiHarness {
    protected val activity = ActivityScenarioRule(MainActivity::class.java)
    private val uiTrace = OwnedUiTraceRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(uiTrace).around(activity)
    protected val instrumentation = InstrumentationRegistry.getInstrumentation()
    protected val device get() = UiDevice.getInstance(instrumentation)
    protected lateinit var model: ClientModel
    protected suspend fun main(action: () -> Unit) = withContext(Dispatchers.Main) { action() }
    protected fun text(label: String): UiObject2 { device.waitForIdle(); return device.wait(Until.findObject(By.text(label)), 5000) ?: missing(label) }
    protected fun action(label: String): UiObject2 { device.waitForIdle(); return device.wait(Until.findObject(By.desc(label)), 5000) ?: missing(label) }
    protected fun fileDetails(name: String) {
        var target = text(name)
        while (!target.isLongClickable) target = target.parent ?: missing("长按文件卡片 $name")
        target.longClick()
        text("文件详情")
    }
    private fun missing(label: String): Nothing {
        // Pure setContent tests intentionally need no bound ClientModel. A
        // diagnostic failure must never replace the original missing control.
        fun observe(block: () -> String) = runCatching(block).getOrElse { "unavailable(${it.javaClass.simpleName})" }
        val modelState = observe {
            if (!this::model.isInitialized) "model=uninitialized" else {
                val state = model.state.value
                "model=bound,tab=${state.tab},busy=${state.busy},startupPending=${state.startupPending}," +
                    "connected=${state.connected},tagsLoaded=${model.tags.state.value.loaded}"
            }
        }
        // No hierarchy, screenshot, Intent or View text: those can contain
        // credentials/input/private filenames. Explicit owned captures remain opt-in.
        // Even scenario.state waits for main idle in core 1.7.0. Read only a
        // timestamped main-callback cache; unavailable foreground is not a fact.
        val diagnostics = "$modelState; foregroundPackage=unverified; scenario=unverified; ${uiTrace.snapshot()}"
        error("Missing visible action $label; $diagnostics")
    }
    protected suspend fun fixture(data: LibraryFixtureData, block: suspend (ClientSearchTest.Fixture) -> Unit) {
        val source = ClientSearchTest.Fixture(data.files.keys.filter { it.substringBeforeLast('/').isEmpty() }.map { it.substringAfterLast('/') }, library = data)
        val database = ClientDatabase.get(instrumentation.targetContext)
        val previous = database.profiles().activeSession()
        val store = ProfileStore(database, CredentialVault(instrumentation.targetContext))
        val profile = store.save(ServerProfile(name = "Owned library UI fixture", address = source.url))
        activity.scenario.onActivity { model = ViewModelProvider(it)[ClientModel::class.java] }
        try {
            main { model.selectProfile(profile); model.connectDraft(profile.name, source.url, BackendKind.NAS, "one", "fixture-only", "direct") }
            withTimeout(10_000) { model.state.first { it.connected && !it.busy } }
            withTimeout(5000) {
                while (true) { var focused = false; activity.scenario.onActivity { focused = it.hasWindowFocus() }; if (focused) break; delay(50) }
            }
            block(source)
        } finally { withContext(NonCancellable) {
            main { model.disconnect() }; store.remove(profile); source.close()
            previous?.let { if (database.profiles().account(it.accountKey) != null) database.profiles().saveActiveSession(it) }
        } }
    }
    protected fun capture(name: String) {
        device.executeShellCommand("mkdir -p /sdcard/Download/nfb-client-acceptance")
        device.executeShellCommand("screencap -p /sdcard/Download/nfb-client-acceptance/$name.png")
    }
}
