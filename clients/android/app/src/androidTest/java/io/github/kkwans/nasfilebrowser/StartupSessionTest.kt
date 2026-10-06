package io.github.kkwans.nasfilebrowser

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.uiautomator.UiDevice
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.kkwans.nasfilebrowser.app.ClientModel
import io.github.kkwans.nasfilebrowser.app.ResourceRef
import io.github.kkwans.nasfilebrowser.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StartupSessionTest {
    @Test fun rootBackFinishesActivityAndRelaunchRestoresSavedAccount(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as Application
        val store = ProfileStore(ClientDatabase.get(app), CredentialVault(app))
        val source = ClientSessionTest.Fixture("activity-source")
        val profile = store.save(ServerProfile(name = "Activity startup fixture", address = source.url))
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var model: ClientModel
                scenario.onActivity { model = ViewModelProvider(it)[ClientModel::class.java] }
                withContext(Dispatchers.Main) { model.selectProfile(profile); model.connectDraft(profile.name, source.url, BackendKind.NAS, "one", "fixture-only", "direct") }
                withTimeout(15_000) { model.state.first { it.connected && !it.busy && !it.startupPending } }
                UiDevice.getInstance(instrumentation).pressBack()
                withTimeout(5_000) { while (scenario.state != Lifecycle.State.DESTROYED) delay(100) }
                assertEquals("one", store.active()?.second?.username)
            }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var model: ClientModel
                scenario.onActivity { model = ViewModelProvider(it)[ClientModel::class.java] }
                val state = withTimeout(15_000) { model.state.first { it.connected && !it.busy && !it.startupPending } }
                assertEquals(profile.id, state.profile?.id)
                assertEquals("one", state.accountName)
                assertEquals(1, source.logins.get())
            }
        } finally { store.remove(profile); source.close() }
    }

    @Test fun newModelRestoresVerifiedAccountDirectoryAndLayoutWithoutLoginAndKeepsExpiredInputs(): Unit = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val store = ProfileStore(ClientDatabase.get(app), CredentialVault(app))
        val source = ClientSessionTest.Fixture("startup-source")
        val profile = store.save(ServerProfile(name = "Startup fixture", address = source.url))
        val holders = mutableListOf<ViewModelStore>()
        suspend fun newModel(): ClientModel = withContext(Dispatchers.Main) {
            val holder = ViewModelStore().also(holders::add)
            ClientModel(app).also { holder.put("startup", it); it.foreground(true) }
        }
        suspend fun ready(model: ClientModel, path: String) = withTimeout(15_000) {
            model.state.first { it.connected && !it.busy && !it.startupPending && it.path == path }
        }
        try {
            val first = newModel()
            withContext(Dispatchers.Main) { first.selectProfile(profile); first.connectDraft(profile.name, source.url, BackendKind.NAS, "one", "fixture-only", "direct") }
            ready(first, "/")
            withContext(Dispatchers.Main) { first.open(ResourceRef("/folder", "/folder", "Folder", true, "", 0)) }
            ready(first, "/folder")
            withContext(Dispatchers.Main) { first.fileLayout(FileLayout.COMPACT) }
            withTimeout(5_000) { first.state.first { it.fileLayout == FileLayout.COMPACT } }
            assertEquals("one", store.active()?.second?.username)
            withContext(Dispatchers.Main) { holders.last().clear() }

            val restored = newModel()
            val restoredState = ready(restored, "/folder")
            assertEquals(profile.id, restoredState.profile?.id)
            assertEquals("one", restoredState.accountName)
            assertEquals(FileLayout.COMPACT, restoredState.fileLayout)
            assertEquals("startup-source", restoredState.files.single().name)
            assertEquals("Restoration must use the saved token, not a password login", 1, source.logins.get())
            withContext(Dispatchers.Main) { holders.last().clear() }

            source.rejectRequests = true
            val expired = newModel()
            val failure = withTimeout(15_000) { expired.state.first { !it.startupPending && !it.busy && it.error != null } }
            assertFalse(failure.connected)
            assertEquals(profile.id, failure.profile?.id)
            assertEquals(source.url, failure.profile?.address)
            assertEquals("one", failure.accountName)
            assertEquals(1, source.logins.get())
            assertEquals("one", store.active()?.second?.username)
            source.rejectRequests = false
            withContext(Dispatchers.Main) { expired.restore(failure.accounts.single()) }
            ready(expired, "/folder")
            assertEquals(1, source.logins.get())
        } finally {
            withContext(Dispatchers.Main) { holders.forEach { it.clear() } }
            store.remove(profile)
            source.close()
        }
    }
}
