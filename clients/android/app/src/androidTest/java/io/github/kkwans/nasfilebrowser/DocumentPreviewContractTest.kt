package io.github.kkwans.nasfilebrowser

import android.content.Context
import android.graphics.Color
import android.graphics.pdf.PdfDocument
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket

@RunWith(AndroidJUnit4::class)
class DocumentPreviewContractTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private class Authority(val file: ResourceRef, val download: Boolean = true) {
        val token = "owned." + android.util.Base64.encodeToString("{\"user\":{\"id\":1,\"username\":\"fixture\",\"perm\":{\"download\":$download}}}".toByteArray(), android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP) + ".fixture"
        val leases = mutableListOf<String>()
        val revoked = mutableListOf<String>()
        val endpoints = mutableListOf<String>()
        suspend fun session(owner: String): SessionContext {
            val profile = ServerProfile(name = "Owned document fixture", address = "https://fixture.invalid")
            val api = NasSession.restore(profile, token, 1) { command ->
                when (command.getString("op")) {
                    "open" -> "owned-document-$owner"
                    "token" -> token
                    "request" -> {
                        assertEquals("GET", command.getString("method"))
                        val endpoint = command.getString("endpoint"); endpoints.add(endpoint)
                        assertEquals("/api/resources${file.wirePath}?metadata=1", endpoint)
                        JSONObject().put("status", 200).put("body", JSONObject().put("path", file.path).put("wirePath", file.wirePath)
                            .put("name", file.name).put("type", file.type).put("isDir", false).put("size", file.size).put("modified", file.modified).toString())
                    }
                    "lease" -> {
                        assertEquals(file.wirePath, command.getString("wirePath"))
                        "http://127.0.0.1:12345/stream/$owner".also { leases.add(it) }
                    }
                    "revoke" -> { revoked.add(command.getString("url")); Unit }
                    else -> error("Unexpected native operation")
                }
            }
            return SessionContext(profile, AccountRecord(owner, profile.id, 0, 1, "fixture", "fixture-only", 0), api, 1, owner)
        }
    }
    private class Reader(var bytes: ByteArray) : DocumentPreviewReader {
        var hold: CompletableDeferred<Unit>? = null
        val entered = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        var working: File? = null
        override suspend fun text(lease: PreviewLease, expected: Long): ByteArray {
            val captured = bytes.copyOf(); val gate = hold
            entered.complete(Unit)
            try { gate?.let { withContext(NonCancellable) { it.await() } }; return captured }
            finally { finished.complete(Unit) }
        }
        override suspend fun pdf(context: Context, lease: PreviewLease, expected: Long): DocumentWorkingFile {
            val file = DocumentWorkingFiles.create(context, expected)
            file.file.writeBytes(bytes); working = file.file
            return file
        }
    }
    private suspend fun main(action: () -> Unit) = withContext(Dispatchers.Main) { action() }
    private suspend fun loaded(controller: DocumentPreviewController) = withTimeout(5000) {
        controller.state.first { !it.loading && (it.text != null || it.error != null || it.pageCount > 0) }
    }
    @Test fun realLocalhostReaderKeepsBytesRejectsRedirectsAndDetectsSizeChanges(): Unit = runBlocking {
        val data = "Owned UTF-8 中文".toByteArray()
        for ((status, expected) in listOf(200 to data.size.toLong(), 200 to (data.size + 1L), 302 to data.size.toLong())) {
            val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val served = scope.launch {
                runCatching { server.accept().use { socket ->
                    val input = socket.getInputStream().bufferedReader()
                    assertEquals("GET /stream/owned HTTP/1.1", input.readLine())
                    while (!input.readLine().isNullOrEmpty()) Unit
                    val headers = "HTTP/1.1 $status ${if (status == 200) "OK" else "Found"}\r\nContent-Length: ${data.size}\r\nConnection: close\r\n" +
                        (if (status == 302) "Location: http://fixture.invalid/forbidden\r\n" else "") + "\r\n"
                    socket.getOutputStream().apply { write(headers.toByteArray(Charsets.US_ASCII)); write(data); flush() }
                } }
            }
            val lease = PreviewLease("http://127.0.0.1:${server.localPort}/stream/owned", "owned") { }
            try {
                if (status == 200 && expected == data.size.toLong()) assertArrayEquals(data, NativeDocumentReader.text(lease, expected))
                else {
                    val failure = runCatching { NativeDocumentReader.text(lease, expected) }.exceptionOrNull()
                    assertNotNull(failure)
                    assertTrue(failure!!.message.orEmpty().contains(if (status == 302) "302" else "大小已变化"))
                }
            } finally { lease.release(); server.close(); served.cancel(); scope.cancel() }
        }
    }
    @Test fun textUsesOriginalWireAndPermissionsAndInvalidEncodingNeverBecomesReadable(): Unit = runBlocking {
        val bytes = "Owned text\n中文".toByteArray()
        val file = ResourceRef("/�/notes.txt", "/%FF/notes.txt", "notes.txt", false, "text", bytes.size.toLong())
        val authority = Authority(file); val owner = authority.session("one")
        val reader = Reader(bytes); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val controller = DocumentPreviewController(context, scope, { it === owner }, reader = reader)
        try {
            main { controller.bind(owner); controller.open(file, owner.api.id) }; loaded(controller)
            assertEquals("Owned text\n中文", controller.state.value.text!!.text)
            withTimeout(5000) { while (authority.revoked.size != 1) delay(10) }
            assertEquals(authority.leases, authority.revoked)
            reader.bytes = ByteArray(bytes.size) { 0xFF.toByte() }
            main { controller.retry() }; loaded(controller)
            assertNull(controller.state.value.text)
            assertTrue(controller.state.value.error!!.contains("正确编码"))
            val denied = Authority(file, download = false); val deniedOwner = denied.session("denied")
            val deniedController = DocumentPreviewController(context, scope, { it === deniedOwner }, reader = reader)
            main { deniedController.bind(deniedOwner); deniedController.open(file, deniedOwner.api.id) }; loaded(deniedController)
            assertTrue(denied.leases.isEmpty())
            assertTrue(deniedController.state.value.error!!.contains("读取文件内容的权限"))
            main { deniedController.close() }
        } finally { main { controller.close() }; scope.cancel() }
    }
    @Test fun lateOldAccountContentCannotReplaceNewTextAndBothLeasesAreRevoked(): Unit = runBlocking {
        val file = ResourceRef("/notes.txt", "/notes.txt", "notes.txt", false, "text", 3)
        val old = Authority(file); val fresh = Authority(file)
        val first = old.session("old"); val second = fresh.session("new")
        var active = first
        val reader = Reader("old".toByteArray()); val release = CompletableDeferred<Unit>(); reader.hold = release
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val controller = DocumentPreviewController(context, scope, { it === active }, reader = reader)
        try {
            main { controller.bind(first); controller.open(file, first.api.id) }
            withTimeout(5000) { reader.entered.await() }
            reader.hold = null; reader.bytes = "new".toByteArray()
            main { active = second; controller.bind(second); controller.open(file, second.api.id) }; loaded(controller)
            release.complete(Unit)
            withTimeout(5000) { while (old.revoked.size != 1 || fresh.revoked.size != 1) delay(10) }
            assertEquals("new", controller.state.value.scope)
            assertEquals("new", controller.state.value.text!!.text)
            assertEquals(old.leases, old.revoked); assertEquals(fresh.leases, fresh.revoked)
        } finally { release.complete(Unit); main { controller.close() }; scope.cancel() }
    }
    @Test fun pdfRendersActualPagesAndClosingDeletesPrivateWorkAndPreservesUiBorrowUntilReleased(): Unit = runBlocking {
        val bytes = ByteArrayOutputStream().also { output ->
            val document = PdfDocument()
            try {
            for (index in 1..2) {
                val page = document.startPage(PdfDocument.PageInfo.Builder(320, 480, index).create())
                page.canvas.drawColor(if (index == 1) Color.BLUE else Color.RED)
                document.finishPage(page)
            }
                document.writeTo(output)
            } finally { document.close() }
        }.toByteArray()
        val file = ResourceRef("/owned.pdf", "/owned.pdf", "owned.pdf", false, "pdf", bytes.size.toLong())
        val authority = Authority(file); val owner = authority.session("one"); val reader = Reader(bytes)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val controller = DocumentPreviewController(context, scope, { it === owner }, reader = reader)
        try {
            main { controller.bind(owner); controller.open(file, owner.api.id) }
            val first = withTimeout(5000) { controller.state.first { !it.rendering && it.pageImage != null } }
            assertEquals(2, first.pageCount)
            val borrowed = first.pageImage!!.borrow()!!
            assertEquals(Color.BLUE, borrowed.bitmap.getPixel(borrowed.bitmap.width / 2, borrowed.bitmap.height / 2))
            main { controller.selectPage(1) }
            val second = withTimeout(5000) { controller.state.first { it.page == 1 && !it.rendering && it.pageImage != null } }
            assertFalse(borrowed.bitmap.isRecycled)
            borrowed.close(); assertTrue(borrowed.bitmap.isRecycled)
            val last = second.pageImage!!.borrow()!!
            assertEquals(Color.RED, last.bitmap.getPixel(last.bitmap.width / 2, last.bitmap.height / 2))
            main { controller.bind(null) }
            assertNull(controller.state.value.file)
            withTimeout(5000) { while (reader.working!!.exists()) delay(10) }
            assertFalse(last.bitmap.isRecycled)
            last.close(); assertTrue(last.bitmap.isRecycled)
        } finally { main { controller.close() }; scope.cancel() }
    }
}
