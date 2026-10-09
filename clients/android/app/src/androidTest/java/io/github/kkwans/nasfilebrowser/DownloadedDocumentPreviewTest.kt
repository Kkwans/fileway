package io.github.kkwans.nasfilebrowser

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.kkwans.nasfilebrowser.app.*
import io.github.kkwans.nasfilebrowser.data.*
import io.github.kkwans.nasfilebrowser.download.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

internal fun ownedDocumentPdf(): ByteArray {
    val document = PdfDocument()
    try {
        for ((index, color) in listOf(Color.BLUE, Color.GREEN).withIndex()) {
            val page = document.startPage(PdfDocument.PageInfo.Builder(240, 320, index + 1).create())
            page.canvas.drawColor(color); document.finishPage(page)
        }
        return ByteArrayOutputStream().also(document::writeTo).toByteArray()
    } finally { document.close() }
}

internal suspend fun ownedDownloadedDocument(context: Context, name: String, bytes: ByteArray): DownloadRecord {
    val id = UUID.randomUUID().toString()
    val now = System.currentTimeMillis()
    val fileName = "owned-d04-$id-$name"
    val row = DownloadRecord(id, 0, "removed-account-$id", "removed-profile-$id", 0, "/same-original/$name", "/same-original/$name", fileName,
        if (name.endsWith(".pdf")) "pdf" else "text", bytes.size.toLong(), "owned-original", "owned-original", "Owned removed source", "",
        status = "completed", downloaded = bytes.size.toLong(), createdAt = now, updatedAt = now)
    val target = DownloadTarget(context)
    val uri = target.allocate(row)
    val saved = row.copy(localUri = uri.toString())
    try {
        context.contentResolver.openOutputStream(uri, "w")!!.use { it.write(bytes) }; target.complete(saved)
        val database = ClientDatabase.get(context)
        return database.withTransaction { saved.copy(jobId = database.downloads().lastJobId() + 1).also { database.downloads().insert(it) } }
    } catch (error: Throwable) { target.delete(saved); throw error }
}

/** An actual kernel read-only descriptor behind a revocable in-process provider.
 * This tests resolver contracts; system SAF selection is a separate device gate. */
private class ReadOnlyDocumentProvider(private val source: File) : ContentProvider() {
    @Volatile var revoked = false
    @Volatile var blockOpen = false
    val entered = CountDownLatch(1); val release = CountDownLatch(1)
    val opened = AtomicInteger(); val closed = AtomicInteger(); val mutations = AtomicInteger()
    val modes = java.util.Collections.synchronizedList(arrayListOf<String>())
    override fun onCreate() = true
    override fun getType(uri: Uri) = "text/plain"
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? { mutations.incrementAndGet(); error("Read-only fixture") }
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int { mutations.incrementAndGet(); error("Read-only fixture") }
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int { mutations.incrementAndGet(); error("Original URI must never be deleted by the viewer") }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor = openFile(uri, mode, null)
    override fun openFile(uri: Uri, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        modes.add(mode); check(mode == "r") { "Writing was requested" }
        if (revoked) throw SecurityException("Owned read grant revoked")
        if (blockOpen) {
            signal?.setOnCancelListener { release.countDown() }
            entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
            signal?.throwIfCanceled()
        }
        return ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY, Handler(Looper.getMainLooper())) { closed.incrementAndGet() }
            .also { opened.incrementAndGet() }
    }
}

@RunWith(AndroidJUnit4::class)
class DownloadedDocumentPreviewTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private suspend fun main(action: () -> Unit) = withContext(Dispatchers.Main) { action() }
    private suspend fun loaded(controller: DocumentPreviewController) = withTimeout(10_000) {
        controller.state.first { !it.loading && (it.error != null || it.text != null || it.pageImage != null) }
    }
    private suspend fun remove(row: DownloadRecord) { runCatching { DownloadTarget(context).delete(row) }; ClientDatabase.get(context).downloads().removeRecord(row.id) }

    @Test fun completedTextReadsAndSearchesOfflineAfterItsProfileWasRemoved(): Unit = runBlocking {
        val bytes = "Owned needle\n中文 needle".toByteArray()
        val row = ownedDownloadedDocument(context, "notes.txt", bytes)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        var recorded = 0
        val controller = DocumentPreviewController(context, scope, { false }, { _, _ -> recorded++ })
        try {
            assertNull(ClientDatabase.get(context).profiles().profile(row.profileId))
            main { controller.openLocal(row) }
            assertEquals(bytes.toString(Charsets.UTF_8), loaded(controller).text!!.text)
            main { controller.search("needle") }
            withTimeout(5000) { controller.state.first { !it.searching && it.search.matches.size == 2 } }
            main { controller.nextMatch(1) }; assertEquals(1, controller.state.value.matchIndex)
            main { controller.retry() }; assertEquals(bytes.toString(Charsets.UTF_8), loaded(controller).text!!.text)
            main { controller.close() }
            assertEquals(0, recorded)
            assertEquals(row, ClientDatabase.get(context).downloads().get(row.id))
            assertArrayEquals(bytes, context.contentResolver.openInputStream(Uri.parse(row.localUri))!!.use { it.readBytes() })
        } finally { main { controller.close() }; scope.cancel(); remove(row) }
    }

    @Test fun localPdfRendersActualPagesWithoutAnyOtherNasCallsAndOnlyDeletesItsCopy(): Unit = runBlocking {
        val bytes = ownedDocumentPdf(); val row = ownedDownloadedDocument(context, "pages.pdf", bytes)
        val profile = ServerProfile(name = "Other current NAS", address = "https://owned.invalid")
        val token = "owned." + android.util.Base64.encodeToString("{\"user\":{\"id\":1,\"username\":\"other\",\"perm\":{\"download\":false,\"modify\":true}}}".toByteArray(), android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP) + ".fixture"
        var remoteCalls = 0; var recorded = 0; var copy: File? = null
        val api = NasSession.restore(profile, token, 1) { command ->
            if (command.getString("op") == "open") "owned-other" else { remoteCalls++; error("Local preview must never contact a NAS") }
        }
        val owner = SessionContext(profile, AccountRecord("other", profile.id, 0, 1, "other", "fixture-only", 0), api, 1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val reader = object : DocumentPreviewReader by NativeDocumentReader {
            override suspend fun pdf(context: Context, uri: Uri, expected: Long): DocumentWorkingFile = NativeDocumentReader.pdf(context, uri, expected).also { copy = it.file }
        }
        val controller = DocumentPreviewController(context, scope, { it === owner }, { _, _ -> recorded++ }, reader)
        try {
            main { controller.bind(owner); controller.openLocal(row) }
            val first = loaded(controller); assertNull(first.error); assertEquals(2, first.pageCount)
            first.pageImage!!.borrow()!!.use { image ->
                val pixel = image.bitmap.getPixel(image.bitmap.width / 2, image.bitmap.height / 2)
                assertTrue(Color.blue(pixel) > 200 && Color.red(pixel) < 80 && Color.green(pixel) < 80)
            }
            main { controller.selectPage(1); controller.zoom(150) }
            val second = withTimeout(10_000) { controller.state.first { !it.rendering && it.page == 1 && it.pageImage != null } }
            second.pageImage!!.borrow()!!.use { image ->
                val pixel = image.bitmap.getPixel(image.bitmap.width / 2, image.bitmap.height / 2)
                assertTrue(Color.green(pixel) > 200 && Color.red(pixel) < 80 && Color.blue(pixel) < 80)
            }
            assertTrue(requireNotNull(copy).exists())
            main { controller.close() }
            withTimeout(5000) { while (requireNotNull(copy).exists()) delay(10) }
            assertEquals(0, remoteCalls); assertEquals(0, recorded)
            assertEquals(row, ClientDatabase.get(context).downloads().get(row.id))
            assertArrayEquals(bytes, context.contentResolver.openInputStream(Uri.parse(row.localUri))!!.use { it.readBytes() })
        } finally { main { controller.close() }; scope.cancel(); remove(row) }
    }

    @Test fun readOnlyProviderRevocationDeletionAndCancellationAreDistinctAndNeverMutateUri(): Unit = runBlocking {
        val file = File(context.cacheDir, "owned-d04-provider-${UUID.randomUUID()}.txt").apply { writeText("owned") }
        val provider = ReadOnlyDocumentProvider(file)
        provider.attachInfo(context, ProviderInfo().apply { authority = "owned-d04"; exported = false })
        val resolver = ContentResolver.wrap(provider)
        val local = object : ContextWrapper(context) { override fun getContentResolver() = resolver }
        val uri = Uri.parse("content://owned-d04/document")
        try {
            assertArrayEquals("owned".toByteArray(), NativeDocumentReader.text(local, uri, 5))
            provider.revoked = true
            assertTrue(runCatching { NativeDocumentReader.text(local, uri, 5) }.exceptionOrNull()!!.message.orEmpty().contains("读取授权已失效"))
            provider.revoked = false; provider.blockOpen = true
            val read = async(Dispatchers.IO) { NativeDocumentReader.text(local, uri, 5) }
            assertTrue(withContext(Dispatchers.IO) { provider.entered.await(5, TimeUnit.SECONDS) })
            read.cancelAndJoin(); provider.blockOpen = false
            assertEquals("owned", file.readText())
            file.delete()
            assertTrue(runCatching { NativeDocumentReader.text(local, uri, 5) }.exceptionOrNull()!!.message.orEmpty().contains("已删除、移动或不可用"))
            withTimeout(5000) { while (provider.closed.get() != provider.opened.get()) delay(10) }
            assertTrue(provider.modes.all { it == "r" }); assertEquals(0, provider.mutations.get())
        } finally { provider.release.countDown(); file.delete() }
    }

    @Test fun changedLengthBrokenPdfAndLimitsKeepTheDownloadRecordAndOriginal(): Unit = runBlocking {
        val text = ownedDownloadedDocument(context, "change.txt", "owned".toByteArray())
        val broken = ownedDownloadedDocument(context, "broken.pdf", "not a PDF".toByteArray())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val controller = DocumentPreviewController(context, scope, { false })
        try {
            context.contentResolver.openOutputStream(Uri.parse(text.localUri), "wa")!!.use { it.write(1) }
            main { controller.openLocal(text) }; assertTrue(loaded(controller).error.orEmpty().contains("长度已变化"))
            main { controller.openLocal(broken) }; assertTrue(loaded(controller).error.orEmpty().contains("PDF 已损坏"))
            val largePdf = broken.copy(expectedSize = DOCUMENT_PDF_LIMIT + 1, downloaded = DOCUMENT_PDF_LIMIT + 1)
            ClientDatabase.get(context).openHelper.writableDatabase.execSQL("UPDATE downloads SET expectedSize = ?, downloaded = ? WHERE id = ?", arrayOf<Any>(largePdf.expectedSize, largePdf.downloaded, largePdf.id))
            main { controller.openLocal(largePdf) }; assertTrue(loaded(controller).error.orEmpty().contains("64 MiB"))
            val large = text.copy(expectedSize = DOCUMENT_TEXT_LIMIT + 1, downloaded = DOCUMENT_TEXT_LIMIT + 1)
            ClientDatabase.get(context).openHelper.writableDatabase.execSQL("UPDATE downloads SET expectedSize = ?, downloaded = ? WHERE id = ?", arrayOf<Any>(large.expectedSize, large.downloaded, large.id))
            main { controller.openLocal(large) }; assertTrue(loaded(controller).error.orEmpty().contains("10 MiB"))
            assertEquals(large, ClientDatabase.get(context).downloads().get(large.id))
            assertEquals(largePdf, ClientDatabase.get(context).downloads().get(broken.id))
            assertArrayEquals("not a PDF".toByteArray(), context.contentResolver.openInputStream(Uri.parse(broken.localUri))!!.use { it.readBytes() })
        } finally { main { controller.close() }; scope.cancel(); remove(text); remove(broken) }
    }

    @Test fun lateLocalPdfReadCannotReplaceAnotherDocumentAndReleasesItsWorkingCopy(): Unit = runBlocking {
        val pdf = ownedDownloadedDocument(context, "late.pdf", ownedDocumentPdf())
        val text = ownedDownloadedDocument(context, "current.txt", "current document".toByteArray())
        val gate = CompletableDeferred<Unit>(); val entered = CompletableDeferred<Unit>(); val returned = CompletableDeferred<Unit>()
        var copy: File? = null
        val reader = object : DocumentPreviewReader by NativeDocumentReader {
            override suspend fun pdf(context: Context, uri: Uri, expected: Long): DocumentWorkingFile {
                val working = NativeDocumentReader.pdf(context, uri, expected); copy = working.file
                entered.complete(Unit)
                withContext(NonCancellable) { gate.await() }
                returned.complete(Unit); return working
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val controller = DocumentPreviewController(context, scope, { false }, reader = reader)
        try {
            main { controller.openLocal(pdf) }; withTimeout(5000) { entered.await() }
            main { controller.close(); controller.openLocal(text) }; assertEquals("current document", loaded(controller).text!!.text)
            gate.complete(Unit); withTimeout(5000) { returned.await() }
            withTimeout(5000) { while (requireNotNull(copy).exists()) delay(10) }
            assertEquals(text.id, controller.state.value.file!!.downloadId)
            assertEquals("current document", controller.state.value.text!!.text)
            assertEquals(0, controller.state.value.pageCount)
        } finally { gate.complete(Unit); main { controller.close() }; scope.cancel(); remove(pdf); remove(text) }
    }

    @Test fun retryRechecksRoomUriAndCompletedStateBeforeReadingAnotherSource(): Unit = runBlocking {
        val first = ownedDownloadedDocument(context, "same.txt", "original local text".toByteArray())
        val other = ownedDownloadedDocument(context, "same.txt", "other local text".toByteArray())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val controller = DocumentPreviewController(context, scope, { false })
        val dao = ClientDatabase.get(context).downloads()
        try {
            main { controller.openLocal(first) }; assertEquals("original local text", loaded(controller).text!!.text)
            ClientDatabase.get(context).openHelper.writableDatabase.execSQL("UPDATE downloads SET localUri = ?, generation = generation + 1 WHERE id = ?", arrayOf(other.localUri, first.id))
            main { controller.retry() }; assertTrue(loaded(controller).error.orEmpty().contains("下载来源已变化"))
            assertNull(controller.state.value.text)
            ClientDatabase.get(context).openHelper.writableDatabase.execSQL("UPDATE downloads SET status = 'paused' WHERE id = ?", arrayOf(first.id))
            val paused = requireNotNull(dao.get(first.id))
            main { controller.openLocal(paused) }; assertTrue(loaded(controller).error.orEmpty().contains("下载完成后"))
            assertArrayEquals("other local text".toByteArray(), context.contentResolver.openInputStream(Uri.parse(other.localUri))!!.use { it.readBytes() })
        } finally {
            main { controller.close() }; scope.cancel()
            // URI mutation belongs to this probe only. Delete each original
            // allocated URI exactly once, rather than following the changed row.
            remove(first); remove(other)
        }
    }
}
