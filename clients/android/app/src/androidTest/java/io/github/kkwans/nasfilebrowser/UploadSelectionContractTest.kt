package io.github.kkwans.nasfilebrowser

import android.net.Uri
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.kkwans.nasfilebrowser.app.SessionContext
import io.github.kkwans.nasfilebrowser.data.*
import io.github.kkwans.nasfilebrowser.download.*
import io.github.kkwans.nasfilebrowser.upload.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Owned MediaStore inputs, original NAS transport and actual Room reservations. No UI assertions. */
@RunWith(AndroidJUnit4::class)
class UploadSelectionContractTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)

    @Test fun keepBothReservesLaterOriginalAndDuplicatePickUploadsExactlyOnce(): Unit = runBlocking {
        withFixture { context, source, binding, controller, allocate ->
            val stem = "owned-${UUID.randomUUID()}"
            val first = allocate("$stem.txt", byteArrayOf(1, 2))
            val second = allocate("$stem（2）.txt", byteArrayOf(3, 4, 5))
            source.files["/$stem.txt"] = byteArrayOf(9)
            withContext(Dispatchers.Main) {
                assertTrue(controller.begin(binding, DirectoryCrumb("根目录", "/", "/")))
                controller.prepare(listOf(first, first, second))
            }
            withTimeout(10_000) { controller.state.first { !it.busy && it.drafts.size == 2 } }
            withContext(Dispatchers.Main) { controller.submit() }
            val rows = withTimeout(20_000) {
                ClientDatabase.get(context).uploads().observe().first { rows -> rows.count { it.profileId == binding.profile.id && it.complete } == 2 }
            }.filter { it.profileId == binding.profile.id }
            assertEquals(2, rows.size)
            assertEquals(setOf("/$stem（3）.txt", "/$stem（2）.txt"), rows.map { it.targetPath }.toSet())
            assertEquals(2, rows.map { uploadTargetKey(it.targetWire) }.distinct().size)
            assertArrayEquals(byteArrayOf(9), source.files["/$stem.txt"])
            assertArrayEquals(byteArrayOf(1, 2), source.files["/$stem（3）.txt"])
            assertArrayEquals(byteArrayOf(3, 4, 5), source.files["/$stem（2）.txt"])
            assertTrue(rows.all { it.batchItems == 2 && it.batchBytes == 5L })
        }
    }

    @Test fun twoControllersCannotInsertTheSamePendingTargetAfterBothPreflights(): Unit = runBlocking {
        withFixture { context, source, binding, first, allocate ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val second = UploadController(context, scope) { it === binding }
            try {
                val name = "owned-race-${UUID.randomUUID()}.bin"
                val uri = allocate(name, ByteArray(2 * 1024 * 1024 + 1) { 17 })
                withContext(Dispatchers.Main) {
                    assertTrue(first.begin(binding, DirectoryCrumb("根目录", "/", "/")))
                    assertTrue(second.begin(binding, DirectoryCrumb("根目录", "/", "/")))
                    first.prepare(listOf(uri)); second.prepare(listOf(uri))
                }
                withTimeout(10_000) { first.state.first { !it.busy && it.drafts.size == 1 }; second.state.first { !it.busy && it.drafts.size == 1 } }
                val preflight = CountDownLatch(2)
                source.beforeMetadata = { path -> if (path == "/$name") { preflight.countDown(); check(preflight.await(10, TimeUnit.SECONDS)) } }
                source.holdSecondChunk = true
                withContext(Dispatchers.Main) { first.submit(); second.submit() }
                withTimeout(15_000) { first.state.first { !it.busy }; second.state.first { !it.busy } }
                source.beforeMetadata = null
                val rows = ClientDatabase.get(context).uploads().observe().first().filter { it.profileId == binding.profile.id }
                assertEquals("One transaction must reject the stale plan before inserting anything", 1, rows.size)
                assertEquals("/$name", rows.single().targetPath)
                val rejected = listOf(first.state.value, second.state.value).single { it.error != null }
                assertTrue(rejected.error.orEmpty().contains("整批未建立"))
                assertEquals("The rejected selection remains reviewable", 1, rejected.drafts.size)
            } finally { scope.cancel(); source.beforeMetadata = null; source.release.countDown() }
        }
    }

    @Test fun ambiguousCaseOnlyTargetsKeepWholeSelectionAndCreateNoJobs(): Unit = runBlocking {
        val directory = "owned-upload-case-${UUID.randomUUID()}"
        // MediaStore can rename case-only siblings in the same local folder.
        // Select from two real folders; ordinary multi-file upload flattens
        // these sources into the same remote destination.
        withFixture(directoryFor = { "$directory/" + if (it.endsWith("A.txt")) "upper" else "lower" }) { context, _, binding, controller, allocate ->
            val stem = "owned-case-${UUID.randomUUID()}"
            val lower = allocate("${stem}a.txt", byteArrayOf(1))
            val upper = allocate("${stem}A.txt", byteArrayOf(2))
            val expectedNames = listOf("${stem}a.txt", "${stem}A.txt")
            val actualNames = withContext(Dispatchers.IO) {
                listOf(UploadSources(context).read(lower).name, UploadSources(context).read(upper).name)
            }
            assertEquals("The real provider must preserve the intended case-only source names", expectedNames, actualNames)
            withContext(Dispatchers.Main) {
                assertTrue(controller.begin(binding, DirectoryCrumb("根目录", "/", "/")))
                controller.prepare(listOf(lower, upper))
            }
            val prepared = withTimeout(10_000) { controller.state.first { !it.busy && it.drafts.size == 2 } }
            assertEquals(expectedNames, prepared.drafts.map { it.source.name })
            withContext(Dispatchers.Main) { controller.submit() }
            val rejected = try { withTimeout(10_000) { controller.state.first { !it.busy && it.error != null } } }
            catch (error: TimeoutCancellationException) {
                val state = controller.state.value
                throw AssertionError("Case-only rejection: busy=${state.busy}, selecting=${state.selecting}, " +
                    "drafts=${state.drafts.map { it.source.name }}, error=${state.error}", error)
            }
            assertEquals(2, rejected.drafts.size)
            assertTrue(rejected.selecting)
            assertTrue(rejected.error.orEmpty().contains("大小写冲突"))
            assertTrue(ClientDatabase.get(context).uploads().observe().first().none { it.profileId == binding.profile.id })
        }
    }

    private suspend fun withFixture(directoryFor: (String) -> String = { "" }, block: suspend (android.content.Context, OwnedUploadFixture, SessionContext, UploadController,
        suspend (String, ByteArray) -> Uri) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        if (android.os.Build.VERSION.SDK_INT >= 33) InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(context.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        val database = ClientDatabase.get(context); val dao = database.uploads()
        val store = ProfileStore(database, CredentialVault(context)); val source = OwnedUploadFixture()
        val profile = store.save(ServerProfile(name = "Owned upload selection", address = source.url))
        val api = NasSession.login(profile, "fixture", "fixture-only")
        val account = store.saveLogin(profile, api.identity.id, api.identity.username, api.token())
        val binding = SessionContext(profile, account, api, 1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val controller = UploadController(context, scope) { it === binding }
        val target = DownloadTarget(context); val local = arrayListOf<DownloadRecord>()
        try {
            block(context, source, binding, controller) { name, bytes ->
                val row = DownloadRecord(UUID.randomUUID().toString(), 0, "owned", profile.id, 0, "/$name", SearchResult.encodePath("/$name"), name,
                    "blob", bytes.size.toLong(), "owned", "owned", "Owned source", "", createdAt = 1, updatedAt = 1,
                    relativeDirectory = directoryFor(name))
                val uri = target.allocate(row); val saved = row.copy(localUri = uri.toString())
                local.add(saved)
                context.contentResolver.openOutputStream(uri, "w")!!.use { it.write(bytes) }; target.complete(saved)
                uri
            }
        } finally { withContext(NonCancellable) {
            source.beforeMetadata = null; source.release.countDown(); scope.cancel()
            dao.observe().first().filter { it.profileId == profile.id }.forEach {
                UploadScheduler.pause(context, it.id); UploadRuntime.get(context).awaitStopped(it.id); dao.removeRecord(it.id)
            }
            local.forEach { target.delete(it) }; api.close(); store.remove(profile); source.close()
        } }
    }
}
