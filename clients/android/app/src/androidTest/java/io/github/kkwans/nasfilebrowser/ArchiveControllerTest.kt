package io.github.kkwans.nasfilebrowser

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.kkwans.nasfilebrowser.app.*
import io.github.kkwans.nasfilebrowser.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ArchiveControllerTest {
    private val sourceWire = "/%D6%D0%CE%C4.zip"
    private val entryWire = "%D6%D0%CE%C4.txt"
    private val modified = "2023-11-14T22:13:20Z"
    private fun source() = ResourceRef("/中文.zip", sourceWire, "中文.zip", false, "blob", 123, modified)
    private fun entry(wire: String, size: Long = 11) = JSONObject().put("path", "中文.txt").put("wirePath", wire).put("pathVerified", true)
        .put("name", "中文.txt").put("isDir", false).put("size", size).put("modified", 1)
    private fun listing() = JSONObject().put("archivePath", "/中文.zip").put("archiveWirePath", sourceWire).put("pathVerified", true)
        .put("format", "zip").put("sourceSize", 123).put("sourceModified", 1_700_000_000_000L)
        .put("entries", JSONArray().put(entry(entryWire)).put(entry("%E4%B8%AD%E6%96%87.txt", 22)))
        .put("listedBytes", 33).put("blockedCount", 0).put("truncated", false).put("maxEntries", 10000)
        .put("maxFileBytes", 8L shl 30).put("maxExtractBytes", 20L shl 30)
    private fun task(status: String, user: Long = 7) = JSONObject().put("id", "owned-archive-task").put("userId", user)
        .put("type", "archive.extract").put("title", "Owned archive task").put("status", status)
    private fun report() = JSONObject().put("archivePath", "/中文.zip").put("archiveWirePath", sourceWire).put("destination", "/")
        .put("destinationWirePath", "/").put("selected", JSONArray().put("中文.txt")).put("selectedWirePaths", JSONArray().put(entryWire))
        .put("pathsVerified", true).put("extractedFiles", 1).put("extractedDirs", 0).put("extractedBytes", 11)
        .put("skippedCount", 0).put("completedAt", 1_700_000_000_500L)
    private fun response(body: JSONObject, status: Int = 200) = JSONObject().put("status", status).put("body", body.toString())
    private suspend fun session(owner: String, handler: suspend (JSONObject) -> JSONObject): SessionContext {
        val profile = ServerProfile(id = "fixture-$owner", name = "Owned archive fixture", address = "https://archive.invalid")
        val payload = JSONObject().put("user", JSONObject().put("id", 7).put("username", "fixture").put("perm", JSONObject().put("download", true).put("create", true)))
        val token = "owned." + Base64.encodeToString(payload.toString().toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP) + ".fixture"
        val api = NasSession.restore(profile, token, 7) { command -> when (command.getString("op")) {
            "open" -> "fixture-session-$owner"
            "token" -> token
            "request" -> handler(command)
            else -> error("Unexpected owned-fixture operation")
        } }
        return SessionContext(profile, AccountRecord(owner, profile.id, 0, 7, "fixture", "unused-fixture-vault", 0), api, 1, owner)
    }
    private fun metadata(endpoint: String): JSONObject = if (endpoint.startsWith("/api/resources$sourceWire"))
        JSONObject().put("path", "/中文.zip").put("wirePath", sourceWire).put("isDir", false).put("size", 123).put("modified", modified)
    else JSONObject().put("path", "/").put("wirePath", "/").put("name", "根目录").put("isDir", true).put("items", JSONArray())

    @Test fun rawSelectedEntrySubmissionAndOwnedTaskReportCompleteWithoutMergingSiblings(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        var submitted: JSONObject? = null; var accepted = 0; var opened = 0
        val context = session("owned") { command ->
            val endpoint = command.getString("endpoint")
            when {
                endpoint.startsWith("/api/archives/entries?") -> response(listing())
                endpoint.startsWith("/api/resources") -> response(metadata(endpoint))
                endpoint == "/api/archives/extractions" -> { submitted = command.getJSONObject("body"); response(task("queued"), 202) }
                endpoint.startsWith("/api/tasks/") -> response(task("completed"))
                endpoint.startsWith("/api/archives/extractions/") -> response(report())
                else -> error("Unexpected owned-fixture endpoint")
            }
        }
        try {
            val controller = withContext(Dispatchers.Main) { ArchiveController(scope, { it === context }, { _, _ -> opened++ }, { _, _ -> accepted++ }).also {
                it.bind(context); it.setVisible(true); it.open(source(), context.api.id)
            } }
            val loaded = withTimeout(5000) { controller.state.first { it.listing != null && !it.loading } }
            assertEquals(2, loaded.rows.size); assertNotEquals(loaded.rows[0].wirePath, loaded.rows[1].wirePath)
            withContext(Dispatchers.Main) { controller.toggle(loaded.rows.single { it.wirePath == entryWire }); controller.submit() }
            val completed = withTimeout(5000) { controller.state.first { it.report != null } }
            assertEquals(1, accepted); assertEquals(1, opened)
            assertEquals(sourceWire, submitted!!.getString("archiveWirePath")); assertEquals("/", submitted!!.getString("destinationWirePath"))
            assertEquals(entryWire, submitted!!.getJSONArray("selectedWirePaths").getString(0)); assertFalse(submitted!!.has("selected"))
            assertEquals(11L, completed.report!!.extractedBytes); assertEquals("/", completed.report!!.destination.wirePath)
        } finally { scope.cancel() }
    }

    @Test fun uncertainPostCannotBeResubmittedBeforeExplicitTaskCenterCheck(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main); var posts = 0
        val context = session("uncertain") { command ->
            val endpoint = command.getString("endpoint")
            when {
                endpoint.startsWith("/api/archives/entries?") -> response(listing())
                endpoint.startsWith("/api/resources") -> response(metadata(endpoint))
                endpoint == "/api/archives/extractions" -> { posts++; throw java.io.IOException("Owned uncertain response") }
                else -> error("Unexpected owned-fixture endpoint")
            }
        }
        try {
            val controller = withContext(Dispatchers.Main) { ArchiveController(scope, isCurrent = { it === context }).also { it.bind(context); it.open(source(), context.api.id) } }
            withTimeout(5000) { controller.state.first { it.listing != null && !it.loading } }
            withContext(Dispatchers.Main) { controller.toggle(controller.state.value.rows.first()); controller.submit() }
            withTimeout(5000) { controller.state.first { it.unknownSubmission } }
            withContext(Dispatchers.Main) { controller.submit() }
            assertEquals(1, posts)
            withContext(Dispatchers.Main) { controller.acknowledgeSubmission(); controller.submit() }
            withTimeout(5000) { controller.state.first { it.unknownSubmission } }
            assertEquals(2, posts)
        } finally { scope.cancel() }
    }

    @Test fun lateReadAndStaleUiScopeCannotCrossAccounts(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val entered = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>(); val completed = CompletableDeferred<Unit>()
        val old = session("old") {
            entered.complete(Unit); withContext(NonCancellable) { finish.await(); completed.complete(Unit); response(listing()) }
        }
        var freshReads = 0
        val fresh = session("fresh") { freshReads++; response(listing()) }
        var current = old
        try {
            val controller = withContext(Dispatchers.Main) { ArchiveController(scope, isCurrent = { it === current }).also { it.bind(old); it.open(source(), old.api.id) } }
            withTimeout(5000) { entered.await() }
            withContext(Dispatchers.Main) { current = fresh; controller.bind(fresh); controller.open(source(), old.api.id) }
            assertEquals(0, freshReads); assertNull(controller.state.value.file)
            finish.complete(Unit); withTimeout(5000) { completed.await() }; withContext(Dispatchers.Main) { yield() }
            assertEquals(fresh.owner, controller.state.value.scope); assertNull(controller.state.value.listing)
        } finally { finish.complete(Unit); scope.cancel() }
    }

    @Test fun parserRejectsZipSlipAndUnverifiedHistoryButAllowsFreshLiteralReplacementName(): Unit = runBlocking {
        val value = listing()
        value.getJSONArray("entries").getJSONObject(0).put("wirePath", "%2E%2E/escape")
        assertTrue(runCatching { parseArchiveListing(value) }.isFailure)
        val unknown = listing().put("pathVerified", false)
        assertTrue(runCatching { parseArchiveListing(unknown) }.isFailure)
        val literal = listing().put("archivePath", "/lost�.zip").put("archiveWirePath", "/lost%EF%BF%BD.zip")
        assertEquals("/lost%EF%BF%BD.zip", parseArchiveListing(literal).archiveWirePath)
        assertTrue(runCatching { parseArchiveReport(report().put("pathsVerified", false)) }.isFailure)
    }

    @Test fun destinationBrowsingUsesRawIdentityAndCancelDoesNotChangeCommittedTarget(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val raw = "/%D6%D0%CE%C4"
        val context = session("directory") { command ->
            val endpoint = command.getString("endpoint")
            when {
                endpoint.startsWith("/api/archives/entries?") -> response(listing())
                endpoint == "/api/resources/" -> response(JSONObject().put("path", "/").put("wirePath", "/").put("name", "根目录").put("isDir", true)
                    .put("items", JSONArray().put(JSONObject().put("path", "/中文").put("wirePath", raw).put("name", "中文").put("isDir", true))))
                endpoint == "/api/resources$raw" -> response(JSONObject().put("path", "/中文").put("wirePath", raw).put("name", "中文").put("isDir", true).put("items", JSONArray()))
                else -> error("Unexpected owned-fixture endpoint: $endpoint")
            }
        }
        try {
            val controller = withContext(Dispatchers.Main) { ArchiveController(scope, isCurrent = { it === context }).also { it.bind(context); it.open(source(), context.api.id) } }
            withTimeout(5000) { controller.state.first { it.listing != null && !it.loading } }
            withContext(Dispatchers.Main) { controller.pickDestination() }
            val root = withTimeout(5000) { controller.state.first { it.pickingDestination && !it.directoryLoading && it.directories.isNotEmpty() } }
            val folder = root.directories.single()
            withContext(Dispatchers.Main) { controller.browseDestination(DirectoryCrumb(folder.name, folder.path, folder.wirePath)) }
            withTimeout(5000) { controller.state.first { !it.directoryLoading && it.destinationDraft?.wirePath == raw } }
            withContext(Dispatchers.Main) { controller.closeDestination() }
            assertEquals("/", controller.state.value.destination.wirePath)
            withContext(Dispatchers.Main) { controller.pickDestination() }
            withTimeout(5000) { controller.state.first { !it.directoryLoading && it.directories.isNotEmpty() } }
            withContext(Dispatchers.Main) { controller.browseDestination(DirectoryCrumb(folder.name, folder.path, folder.wirePath)) }
            withTimeout(5000) { controller.state.first { !it.directoryLoading && it.destinationDraft?.wirePath == raw } }
            withContext(Dispatchers.Main) { controller.confirmDestination() }
            assertEquals(raw, controller.state.value.destination.wirePath); assertFalse(controller.state.value.pickingDestination)
        } finally { scope.cancel() }
    }

    @Test fun cancelUsesExistingOwnedTaskEndpointAndKeepsCanceledReceipt(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main); var cancellations = 0
        val context = session("cancel") { command ->
            val endpoint = command.getString("endpoint")
            when {
                endpoint.startsWith("/api/archives/entries?") -> response(listing())
                endpoint.startsWith("/api/resources") -> response(metadata(endpoint))
                endpoint == "/api/archives/extractions" -> response(task("queued"), 202)
                endpoint.endsWith("/cancel") -> { assertEquals("POST", command.getString("method")); cancellations++; response(task("canceled")) }
                else -> error("Unexpected owned-fixture endpoint")
            }
        }
        try {
            val controller = withContext(Dispatchers.Main) { ArchiveController(scope, isCurrent = { it === context }).also { it.bind(context); it.open(source(), context.api.id) } }
            withTimeout(5000) { controller.state.first { it.listing != null && !it.loading } }
            withContext(Dispatchers.Main) { controller.toggle(controller.state.value.rows.first()); controller.submit() }
            withTimeout(5000) { controller.state.first { it.task != null && !it.submitting } }
            withContext(Dispatchers.Main) { controller.cancel() }
            withTimeout(5000) { controller.state.first { it.task?.status == "canceled" && !it.canceling } }
            assertEquals(1, cancellations); assertNull(controller.state.value.report)
        } finally { scope.cancel() }
    }
}
