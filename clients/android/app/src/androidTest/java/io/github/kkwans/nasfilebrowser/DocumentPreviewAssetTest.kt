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
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(AndroidJUnit4::class)
class DocumentPreviewAssetTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private class Authority(private val download: Boolean = true) {
        var requests = 0; var leases = 0
        private val token = "owned." + android.util.Base64.encodeToString("{\"user\":{\"id\":1,\"username\":\"fixture\",\"perm\":{\"download\":$download}}}".toByteArray(),
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP) + ".fixture"
        suspend fun session(owner: String): SessionContext {
            val profile = ServerProfile(name = "Owned borrowed document fixture", address = "https://fixture.invalid")
            val api = NasSession.restore(profile, token, 1) { command -> when (command.getString("op")) {
                "open" -> "owned-asset-$owner"
                "token" -> token
                "request" -> { requests++; error("Asset must not request ordinary NAS metadata") }
                "lease" -> { leases++; error("Asset must not create an ordinary raw lease") }
                else -> error("Unexpected owned-fixture operation")
            } }
            return SessionContext(profile, AccountRecord(owner, profile.id, 0, 1, "fixture", "fixture-only", 0), api, 1, owner)
        }
    }
    private class Reader(var bytes: ByteArray) : DocumentPreviewReader {
        var reads = 0; var hold: CompletableDeferred<Unit>? = null
        val entered = CompletableDeferred<Unit>(); val finished = CompletableDeferred<Unit>()
        val blockedFinished = CompletableDeferred<Unit>()
        var working: File? = null
        override suspend fun text(lease: PreviewLease, expected: Long): ByteArray {
            reads++; val captured = bytes.copyOf(); val gate = hold; entered.complete(Unit)
            // Even releasing the borrowed wrapper cannot revoke root's lease.
            lease.release()
            try { if (gate != null) withContext(NonCancellable) { gate.await() }; return captured } finally { finished.complete(Unit); if (gate != null) blockedFinished.complete(Unit) }
        }
        override suspend fun pdf(context: Context, lease: PreviewLease, expected: Long): DocumentWorkingFile {
            reads++; lease.release()
            return DocumentWorkingFiles.create(context, expected).also { it.file.writeBytes(bytes); working = it.file }
        }
    }
    private suspend fun main(action: () -> Unit) = withContext(Dispatchers.Main) { action() }
    private suspend fun settled(controller: DocumentPreviewController) = withTimeout(5000) {
        controller.state.first { !it.loading && (it.text != null || it.error != null || it.pageImage != null) }
    }
    private fun file(name: String, size: Long) = ResourceRef("包内/$name", "", name, false, if (name.endsWith(".pdf")) "pdf" else "text", size)

    @Test fun borrowedTextRetriesWithoutNasMetadataRevocationOrRecentRecording(): Unit = runBlocking {
        val authority = Authority(); val owner = authority.session("text")
        val bytes = "owned 文本".toByteArray(); val reader = Reader(bytes)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        var revoked = 0; var recorded = 0
        val lease = PreviewLease("http://127.0.0.1:12345/stream/borrowed-text", owner.api.id) { revoked++ }
        val controller = DocumentPreviewController(context, scope, { it === owner }, { _, _ -> recorded++ }, reader)
        try {
            main { controller.bind(owner); controller.openAsset(file("notes.txt", bytes.size.toLong()), lease, owner.api.id) }; settled(controller)
            assertEquals("owned 文本", controller.state.value.text!!.text)
            main { controller.retry() }; settled(controller)
            main { controller.close() }
            assertEquals(2, reader.reads); assertEquals(0, authority.requests); assertEquals(0, authority.leases)
            assertEquals(0, revoked); assertEquals(0, recorded)
            lease.release(); assertEquals(1, revoked)
        } finally { main { controller.close() }; lease.release(); scope.cancel() }
    }

    @Test fun borrowedInputsStillRequireDownloadPermissionBudgetsAndLocalCapabilities(): Unit = runBlocking {
        for (scenario in listOf("denied", "text-limit", "pdf-limit", "remote")) {
            val authority = Authority(download = scenario != "denied"); val owner = authority.session(scenario)
            val reader = Reader("owned".toByteArray()); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            var revoked = 0
            val lease = PreviewLease(if (scenario == "remote") "https://fixture.invalid/stream/private-token" else "http://127.0.0.1:12345/stream/$scenario", owner.api.id) { revoked++ }
            val document = when (scenario) {
                "text-limit" -> file("notes.txt", DOCUMENT_TEXT_LIMIT + 1)
                "pdf-limit" -> file("notes.pdf", DOCUMENT_PDF_LIMIT + 1)
                else -> file("notes.txt", 5)
            }
            val controller = DocumentPreviewController(context, scope, { it === owner }, reader = reader)
            try {
                main { controller.bind(owner); controller.openAsset(document, lease, owner.api.id) }; settled(controller)
                assertNotNull(controller.state.value.error); assertEquals(0, reader.reads)
                assertEquals(0, authority.requests); assertEquals(0, authority.leases); assertEquals(0, revoked)
                assertFalse(controller.state.value.error!!.contains("private-token"))
            } finally { main { controller.close() }; lease.release(); scope.cancel() }
        }
    }

    @Test fun lateBorrowedContentAndOldUiScopeCannotCrossAccounts(): Unit = runBlocking {
        val oldAuthority = Authority(); val newAuthority = Authority()
        val old = oldAuthority.session("old"); val fresh = newAuthority.session("fresh"); var current = old
        val reader = Reader("old".toByteArray()); val release = CompletableDeferred<Unit>(); reader.hold = release
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main); var revoked = 0
        val oldLease = PreviewLease("http://127.0.0.1:12345/stream/old", old.api.id) { revoked++ }
        val newLease = PreviewLease("http://127.0.0.1:12345/stream/new", fresh.api.id) { revoked++ }
        val controller = DocumentPreviewController(context, scope, { it === current }, reader = reader)
        try {
            main { controller.bind(old); controller.openAsset(file("notes.txt", 3), oldLease, old.api.id) }
            withTimeout(5000) { reader.entered.await() }
            reader.hold = null; reader.bytes = "new".toByteArray()
            main { current = fresh; controller.bind(fresh); controller.openAsset(file("notes.txt", 3), newLease, fresh.api.id) }; settled(controller)
            main { controller.openAsset(file("stale.txt", 3), oldLease, old.api.id) }
            release.complete(Unit); withTimeout(5000) { reader.blockedFinished.await() }; withContext(Dispatchers.Main) { yield() }
            assertEquals(fresh.owner, controller.state.value.scope); assertEquals("new", controller.state.value.text!!.text)
            assertEquals("notes.txt", controller.state.value.file!!.name); assertEquals(0, revoked)
        } finally { release.complete(Unit); main { controller.close() }; oldLease.release(); newLease.release(); scope.cancel() }
    }

    @Test fun borrowedPdfKeepsRootLeaseButReleasesExistingViewerWorkOnClose(): Unit = runBlocking {
        val bytes = ByteArrayOutputStream().also { output ->
            val document = PdfDocument()
            try { val page = document.startPage(PdfDocument.PageInfo.Builder(320, 480, 1).create()); page.canvas.drawColor(Color.BLUE); document.finishPage(page); document.writeTo(output) }
            finally { document.close() }
        }.toByteArray()
        val authority = Authority(); val owner = authority.session("pdf"); val reader = Reader(bytes)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main); var revoked = 0; var recorded = 0
        val lease = PreviewLease("http://127.0.0.1:12345/stream/pdf", owner.api.id) { revoked++ }
        val controller = DocumentPreviewController(context, scope, { it === owner }, { _, _ -> recorded++ }, reader)
        try {
            main { controller.bind(owner); controller.openAsset(file("inside.pdf", bytes.size.toLong()), lease, owner.api.id) }; settled(controller)
            assertEquals(1, controller.state.value.pageCount); assertNotNull(controller.state.value.pageImage)
            val working = reader.working!!; main { controller.close() }
            withTimeout(5000) { while (working.exists()) delay(10) }
            assertEquals(0, revoked); assertEquals(0, recorded); assertEquals(0, authority.requests)
        } finally { main { controller.close() }; lease.release(); scope.cancel() }
    }
}
