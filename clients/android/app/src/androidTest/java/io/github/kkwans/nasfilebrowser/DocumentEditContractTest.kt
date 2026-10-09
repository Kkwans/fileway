package io.github.kkwans.nasfilebrowser

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.kkwans.nasfilebrowser.app.*
import io.github.kkwans.nasfilebrowser.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

internal class DocumentEditFixture(bytes: ByteArray = "old\r\n".toByteArray(), modify: Boolean = true, create: Boolean = true, download: Boolean = true) {
    val parent = DirectoryCrumb("原目录", "/�", "/%FF")
    val sourceWire = "/%FF/notes.txt"
    val files = linkedMapOf(sourceWire to bytes)
    var version = "2026-10-09T10:00:00Z"
    var supported = true
    var writeStatus = 200
    var loseWriteResponse = false
    var holdWrite: CompletableDeferred<Unit>? = null
    val writeEntered = CompletableDeferred<Unit>()
    val writeFinished = CompletableDeferred<Unit>()
    val writes = mutableListOf<JSONObject>()
    private val leases = mutableMapOf<String, String>()
    private val token = "owned." + Base64.encodeToString("{\"user\":{\"id\":1,\"username\":\"fixture\",\"perm\":{\"modify\":$modify,\"create\":$create,\"download\":$download}}}".toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP) + ".fixture"
    val file get() = ResourceRef("/�/notes.txt", sourceWire, "notes.txt", false, "text", files.getValue(sourceWire).size.toLong(), version)
    val reader = object : DocumentPreviewReader {
        override suspend fun text(lease: PreviewLease, expected: Long): ByteArray = files.getValue(leases.getValue(lease.url)).copyOf()
        override suspend fun pdf(context: Context, lease: PreviewLease, expected: Long): DocumentWorkingFile = error("Not a PDF fixture")
    }
    suspend fun session(owner: String): SessionContext {
        val profile = ServerProfile(name = "Owned document edit", address = "https://fixture.invalid")
        val api = NasSession.restore(profile, token, 1) { command -> when (command.getString("op")) {
            "open" -> "owned-edit-$owner"
            "token" -> token
            "lease" -> "http://127.0.0.1:12345/stream/$owner".also { leases[it] = command.getString("wirePath") }
            "revoke" -> Unit
            "request" -> {
                val endpoint = command.getString("endpoint"); val method = command.getString("method")
                if (method == "GET" && endpoint == "/api/client-capabilities") JSONObject().put("status", 200).put("body", JSONObject().put("conditionalTextSave", supported).toString())
                else if (method == "GET") {
                    val wire = endpoint.substringBefore('?').removePrefix("/api/resources")
                    val directory = wire == parent.wirePath
                    val content = files[wire]
                    if (!directory && content == null) JSONObject().put("status", 404).put("body", "missing")
                    else {
                        val name = if (directory) "�" else Uri.decode(wire.substringAfterLast('/'))
                        JSONObject().put("status", 200).put("body", JSONObject().put("path", if (directory) parent.path else parent.path + "/" + name)
                            .put("wirePath", wire).put("name", name).put("isDir", directory).put("type", if (directory) "" else "text")
                            .put("size", content?.size ?: 0).put("modified", version).toString())
                    }
                } else {
                    assertTrue(command.has("bodyBase64")); assertFalse(command.has("body"))
                    val wire = endpoint.substringBefore('?').removePrefix("/api/resources")
                    val body = Base64.decode(command.getString("bodyBase64"), Base64.DEFAULT)
                    writes.add(JSONObject(command.toString()))
                    val query = Uri.parse(endpoint)
                    var status = writeStatus
                    if (status == 200 && method == "POST" && files.containsKey(wire)) status = 409
                    if (status == 200 && method == "PUT" && (query.getQueryParameter("expectedModified") != version ||
                        query.getQueryParameter("expectedSize") != files.getValue(wire).size.toString())) status = 409
                    if (method == "PUT") assertEquals("true", query.getQueryParameter("conditional"))
                    else { assertEquals("POST", method); assertEquals("false", query.getQueryParameter("override")) }
                    if (status == 200) { files[wire] = body; version = "2026-10-09T10:01:00Z" }
                    val gate = holdWrite
                    writeEntered.complete(Unit)
                    gate?.let { try { withContext(NonCancellable) { it.await() } } finally { writeFinished.complete(Unit) } }
                    if (loseWriteResponse && status == 200) throw IOException("Owned lost write response")
                    JSONObject().put("status", status).put("body", if (status == 200) "200 OK" else "rejected")
                }
            }
            else -> error("Unexpected native operation")
        } }
        return SessionContext(profile, AccountRecord(owner, profile.id, 0, 1, "fixture", "fixture-only", 0), api, 1, owner)
    }
}

@RunWith(AndroidJUnit4::class)
class DocumentEditContractTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private suspend fun main(action: () -> Unit) = withContext(Dispatchers.Main) { action() }
    private suspend fun settled(controller: DocumentEditController) = withTimeout(5000) { controller.state.first { !it.saving && !it.loading } }
    @Test fun rawConditionalSavePreservesUtf16BomCrLfAndUnsupportedServersNeverReceiveWrites(): Unit = runBlocking {
        val initial = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "old\r\n".toByteArray(Charsets.UTF_16LE)
        val fixture = DocumentEditFixture(initial); val owner = fixture.session("one")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main); var saved = 0
        val controller = DocumentEditController(context, scope, { it === owner }, onSaved = { _, _ -> saved++ }, reader = fixture.reader)
        try {
            val originalFile = fixture.file
            main { controller.bind(owner); controller.open(originalFile, decodeDocumentText(initial), owner.api.id); controller.edit("新\n文本\n") }
            fixture.supported = false
            main { controller.save() }; settled(controller)
            assertTrue(fixture.writes.isEmpty()); assertTrue(controller.state.value.dirty)
            assertTrue(controller.state.value.error!!.contains("升级服务器"))
            fixture.supported = true
            main { controller.save(); controller.save() }; settled(controller)
            assertEquals(1, fixture.writes.size); assertEquals(1, saved)
            val expected = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "新\r\n文本\r\n".toByteArray(Charsets.UTF_16LE)
            assertArrayEquals(expected, fixture.files.getValue(fixture.sourceWire))
            assertTrue(fixture.writes.single().getString("endpoint").startsWith("/api/resources${fixture.sourceWire}?conditional=true&expectedSize=${initial.size}&expectedModified="))
            assertFalse(controller.state.value.dirty)
            assertEquals(fixture.version, controller.state.value.file!!.modified)
            val denied = DocumentEditFixture(modify = false); val deniedOwner = denied.session("denied")
            val deniedController = DocumentEditController(context, scope, { it === deniedOwner }, reader = denied.reader)
            main { deniedController.bind(deniedOwner); deniedController.open(denied.file, decodeDocumentText(denied.files.getValue(denied.sourceWire)), deniedOwner.api.id); deniedController.edit("not allowed"); deniedController.save() }
            settled(deniedController)
            assertTrue(denied.writes.isEmpty())
            assertTrue(deniedController.state.value.error!!.contains("修改权限"))
            main { deniedController.close(discard = true) }
        } finally { main { controller.close(discard = true) }; scope.cancel() }
    }
    @Test fun conflictRetainsDraftAndUnknownSaveOnlyReadsDuringVerification(): Unit = runBlocking {
        val fixture = DocumentEditFixture(); val owner = fixture.session("one")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val controller = DocumentEditController(context, scope, { it === owner }, reader = fixture.reader)
        try {
            main { controller.bind(owner); controller.open(fixture.file, decodeDocumentText(fixture.files.getValue(fixture.sourceWire)), owner.api.id); controller.edit("draft\n") }
            fixture.writeStatus = 409
            main { controller.save() }; settled(controller)
            assertTrue(controller.state.value.conflict); assertEquals("draft\n", controller.state.value.draft)
            main { controller.save() }; assertEquals(1, fixture.writes.size)
            main { controller.reload() }; settled(controller)
            assertFalse(controller.state.value.conflict); assertFalse(controller.state.value.dirty)
            fixture.writeStatus = 200; fixture.loseWriteResponse = true
            main { controller.edit("lost\n"); controller.save() }; settled(controller)
            assertTrue(controller.state.value.unknownWrite)
            main { controller.save(); controller.save() }; assertEquals(2, fixture.writes.size)
            main { controller.verifySave() }; settled(controller)
            assertFalse(controller.state.value.unknownWrite); assertFalse(controller.state.value.dirty)
            assertEquals(2, fixture.writes.size)
        } finally { main { controller.close(discard = true) }; scope.cancel() }
    }
    @Test fun exclusiveCreateRetainsRejectedInputAndAllowsCreateWithoutDownloadPermission(): Unit = runBlocking {
        val fixture = DocumentEditFixture(download = false); val owner = fixture.session("one")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main); var created: ResourceRef? = null
        val controller = DocumentEditController(context, scope, { it === owner }, onCreated = { _, file -> created = file }, reader = fixture.reader)
        try {
            main { controller.bind(owner); controller.startCreate(fixture.parent, owner.api.id); controller.createName("notes.txt"); controller.createContent("保留输入\n"); controller.create() }; settled(controller)
            assertEquals("保留输入\n", controller.state.value.creation!!.content)
            assertTrue(controller.state.value.error!!.contains("未覆盖"))
            assertArrayEquals("old\r\n".toByteArray(), fixture.files.getValue(fixture.sourceWire))
            val name = "%2F +# 中文.txt"
            main { controller.createName(name); controller.createContent(""); controller.create() }; settled(controller)
            assertNull(controller.state.value.creation)
            val expectedWire = "/%FF/" + SearchResult.encodePath(name)
            assertEquals(expectedWire, created!!.wirePath)
            assertArrayEquals(byteArrayOf(), fixture.files.getValue(expectedWire))
            assertTrue(fixture.writes.last().getString("endpoint").endsWith("?override=false"))
        } finally { scope.cancel() }
    }
    @Test fun unknownCreateNeverResubmitsAndOnlyMatchingContentAcknowledgesRecovery(): Unit = runBlocking {
        val fixture = DocumentEditFixture(); val owner = fixture.session("one")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main); var created = 0
        val controller = DocumentEditController(context, scope, { it === owner }, onCreated = { _, _ -> created++ }, reader = fixture.reader)
        fixture.loseWriteResponse = true
        try {
            main { controller.bind(owner); controller.startCreate(fixture.parent, owner.api.id); controller.createName("new.txt"); controller.createContent("\"新\"\n"); controller.create() }; settled(controller)
            assertTrue(controller.state.value.unknownWrite); assertEquals(0, created)
            main { controller.create(); controller.create() }; assertEquals(1, fixture.writes.size)
            main { controller.verifyCreation() }; settled(controller)
            assertNull(controller.state.value.creation); assertEquals(1, created); assertEquals(1, fixture.writes.size)
        } finally { scope.cancel() }
    }
    @Test fun latePreviousAccountWriteCannotChangeNewDraftOrCallSavedCallback(): Unit = runBlocking {
        val old = DocumentEditFixture(); val fresh = DocumentEditFixture("fresh\n".toByteArray())
        val first = old.session("old"); val second = fresh.session("new"); var active = first; var saved = 0
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val controller = DocumentEditController(context, scope, { it === active }, onSaved = { _, _ -> saved++ }, reader = old.reader)
        old.holdWrite = CompletableDeferred()
        try {
            main { controller.bind(first); controller.open(old.file, decodeDocumentText(old.files.getValue(old.sourceWire)), first.api.id); controller.edit("changed\n"); controller.save() }
            withTimeout(5000) { old.writeEntered.await() }
            main { active = second; controller.bind(second); controller.open(fresh.file, decodeDocumentText(fresh.files.getValue(fresh.sourceWire)), second.api.id) }
            old.holdWrite!!.complete(Unit)
            withTimeout(5000) { old.writeFinished.await() }; withContext(Dispatchers.Main) { yield() }
            assertEquals("new", controller.state.value.scope); assertEquals("fresh\n", controller.state.value.draft)
            assertEquals(0, saved); assertTrue(fresh.writes.isEmpty())
        } finally { old.holdWrite?.complete(Unit); main { controller.close(discard = true) }; scope.cancel() }
    }
}
