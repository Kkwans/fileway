package io.github.kkwans.nasfilebrowser

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.ContextWrapper
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.kkwans.nasfilebrowser.data.ClientDatabase
import io.github.kkwans.nasfilebrowser.download.*
import io.github.kkwans.nasfilebrowser.upload.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Controlled metadata cache over a real read-only kernel descriptor. This is
 * an in-process resolver fixture, not a system SAF/cloud-provider acceptance. */
private class UploadLengthProvider(private val file: File) : ContentProvider() {
    var declaredSize: Long? = file.length()
    var pipe = false
    var revoked = false
    val modes = arrayListOf<String>()
    private val writers = arrayListOf<ParcelFileDescriptor>()
    override fun onCreate() = true
    override fun getType(uri: Uri) = "application/octet-stream"
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns).apply {
            addRow(columns.map { column ->
                when (column) {
                    OpenableColumns.DISPLAY_NAME -> "owned-length.bin"
                    OpenableColumns.SIZE -> declaredSize
                    MediaStore.MediaColumns.DATE_MODIFIED -> 1_700_000_000L
                    MediaStore.MediaColumns.MIME_TYPE -> "application/octet-stream"
                    MediaStore.MediaColumns.OWNER_PACKAGE_NAME -> requireNotNull(context).packageName
                    else -> error("Unexpected owned projection")
                }
            })
        }
    }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        modes.add(mode); check(mode == "r") { "UploadSources must only inspect a read descriptor" }
        if (revoked) throw SecurityException("Owned provider grant revoked")
        if (!pipe) return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val ends = ParcelFileDescriptor.createPipe()
        writers.add(ends[1])
        return ends[0]
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = error("Read-only upload fixture")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = error("Read-only upload fixture")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = error("Upload source must be preserved")
    fun close() { writers.forEach { it.close() } }
}

@RunWith(AndroidJUnit4::class)
class UploadSourceRecoveryTest {
    private fun withOwnedLengthProvider(block: (UploadSources, Uri, UploadLengthProvider, File) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "owned-upload-length-${UUID.randomUUID()}.bin").apply { writeText("original") }
        val provider = UploadLengthProvider(file)
        provider.attachInfo(context, ProviderInfo().apply { authority = MediaStore.AUTHORITY; exported = false })
        val resolver = ContentResolver.wrap(provider)
        val local = object : ContextWrapper(context) { override fun getContentResolver() = resolver }
        // wrap() targets only this provider; it does not register or modify the
        // actual MediaStore. The authority exercises its existing timestamp and
        // owned-read-grant branch through the unchanged UploadSources API.
        val uri = Uri.parse("content://media/external/file/owned-upload-length")
        try { block(UploadSources(local), uri, provider, file) }
        finally { provider.close(); check(file.delete() || !file.exists()) }
    }

    @Test fun staleDeclaredLengthCannotAuthorizeChangedBytesIntoOldRemoteFragments() {
        withOwnedLengthProvider { sources, uri, provider, file ->
            val original = sources.read(uri)
            val row = UploadRecord(UUID.randomUUID().toString(), 7900002, "owned-account", "owned-profile", 2,
                original.uri, original.name, original.mime, original.size, original.modified,
                "/remote.bin", "/remote.bin", "/", "Owned", remoteCreated = true, status = "paused", uploaded = 4,
                createdAt = 1, updatedAt = 1)
            assertTrue(original.modified > 0)
            assertEquals(original, sources.reauthorize(row, uri))
            file.appendText(" changed bytes")
            assertEquals(8L, provider.declaredSize)
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { assertTrue(it.statSize > row.expectedSize) }
            // Old product code sees the same cached name/size/modified and
            // incorrectly succeeds here. No new product getter is required.
            assertThrows(IllegalStateException::class.java) { sources.reauthorize(row, uri) }
            assertEquals(file.length(), sources.read(uri).size)
            assertEquals(4L, row.uploaded); assertTrue(row.remoteCreated)
            assertEquals("original changed bytes", file.readText())
            assertTrue(provider.modes.all { it == "r" })
        }
    }

    @Test fun actualReadableLengthWinsOverMissingNegativeAndStaleProviderSizes() {
        withOwnedLengthProvider { sources, uri, provider, file ->
            for (declared in listOf<Long?>(null, -1, 0, file.length() + 50)) {
                provider.declaredSize = declared
                assertEquals(file.length(), sources.read(uri).size)
            }
            assertEquals("original", file.readText())
            file.writeText("")
            provider.declaredSize = 8
            assertEquals(0L, sources.read(uri).size)
            assertTrue(provider.modes.all { it == "r" })
        }
    }

    @Test fun nonSeekableProviderKeepsDeclaredLengthFallbackButUnknownLengthsReject() {
        withOwnedLengthProvider { sources, uri, provider, file ->
            provider.pipe = true
            provider.declaredSize = 1234
            provider.openFile(uri, "r").use { assertEquals(-1L, it.statSize) }
            assertEquals(1234L, sources.read(uri).size)
            provider.declaredSize = 0
            assertEquals(0L, sources.read(uri).size)
            for (declared in listOf<Long?>(null, -1)) {
                provider.declaredSize = declared
                assertThrows(IllegalStateException::class.java) { sources.read(uri) }
            }
            assertEquals("original", file.readText())
        }
    }

    @Test fun unreadableDescriptorCannotBeHiddenByCachedProviderLength() {
        withOwnedLengthProvider { sources, uri, provider, file ->
            provider.revoked = true
            assertThrows(SecurityException::class.java) { sources.read(uri) }
            provider.revoked = false
            assertTrue(file.delete())
            assertThrows(java.io.FileNotFoundException::class.java) { sources.read(uri) }
            assertTrue(provider.modes.all { it == "r" })
        }
    }

    @Test fun documentIdentityUsesOpaqueIdsAndRejectsSameNamesFromOtherSources() {
        val authority = "fileway.owned.invalid"
        val id = "volume:owned/folder +%/video%2Fname.mp4"
        val direct = DocumentsContract.buildDocumentUri(authority, id)
        val tree = DocumentsContract.buildDocumentUriUsingTree(DocumentsContract.buildTreeDocumentUri(authority, "volume:owned"), id)
        assertTrue(UploadSources.sameDocumentUri(tree, direct))
        for (other in listOf(DocumentsContract.buildDocumentUri(authority, id.replace("video%2F", "video/")),
            DocumentsContract.buildDocumentUri("another.owned.invalid", id),
            direct.buildUpon().appendQueryParameter("revision", "other").build(),
            direct.buildUpon().fragment("other").build(), Uri.parse("file:///owned/video.mp4"))) {
            assertFalse(UploadSources.sameDocumentUri(direct, other))
        }
    }

    @Test fun ownedSourceReauthorizationPreservesProgressAndFencesLatePickerResults(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val room = Room.inMemoryDatabaseBuilder(context, ClientDatabase::class.java).build()
        val id = UUID.randomUUID().toString()
        val target = DownloadTarget(context)
        val local = DownloadRecord(id, 0, "owned", "owned", 0, "/owned.bin", "/owned.bin", "fileway-owned-recovery-$id.bin", "blob", 8,
            "owned", "owned", "Owned source recovery", "", createdAt = 1, updatedAt = 1, relativeDirectory = "owned-upload-recovery/$id")
        val uri = target.allocate(local)
        val saved = local.copy(localUri = uri.toString(), status = "completed", downloaded = 8)
        try {
            val original = "original".toByteArray()
            context.contentResolver.openOutputStream(uri, "w")!!.use { it.write(original) }; target.complete(saved)
            val sources = UploadSources(context); val source = sources.read(uri)
            assertTrue(source.modified > 0)
            val row = UploadRecord(id, 7900001, "owned-account", "owned-profile", 2, source.uri, source.name, source.mime, source.size, source.modified,
                "/remote.bin", "/remote.bin", "/", "Owned", remoteCreated = true, status = "failed", uploaded = 4, createdAt = 1, updatedAt = 1)
            val dao = room.uploads(); dao.insert(row)
            assertEquals(source, sources.reauthorize(row, uri))
            assertEquals(1, dao.reauthorize(id, row.generation, source.uri, 2))
            val recovered = dao.get(id)!!
            assertEquals(4L, recovered.uploaded); assertTrue(recovered.remoteCreated)
            assertEquals(row.targetWire, recovered.targetWire); assertEquals(row.accountKey, recovered.accountKey)
            assertEquals("paused", recovered.status)
            assertEquals(0, dao.reauthorize(id, row.generation, "content://fixture.invalid/late", 3))
            assertEquals(source.uri, dao.get(id)!!.sourceUri)
            assertThrows(IllegalArgumentException::class.java) { sources.reauthorize(row, Uri.parse("content://another.owned.invalid/source")) }
            assertThrows(IllegalStateException::class.java) { sources.reauthorize(row.copy(sourceModified = source.modified - 1), uri) }
            context.contentResolver.openOutputStream(uri, "wa")!!.use { it.write(1) }
            assertEquals(original.size.toLong() + 1, context.contentResolver.openFileDescriptor(uri, "r")!!.use { it.statSize })
            assertThrows(IllegalStateException::class.java) { sources.reauthorize(row, uri) }
            assertEquals(original.size.toLong() + 1, sources.read(uri).size)
            assertEquals(recovered, dao.get(id)); assertEquals(4L, dao.get(id)!!.uploaded)
            assertArrayEquals(original + byteArrayOf(1), context.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
            assertEquals(1, dao.command(id, "queued", 4)); assertEquals(1, dao.claim(id, 5))
            assertEquals(0, dao.reauthorize(id, dao.get(id)!!.generation, source.uri, 6))
        } finally { target.delete(saved); room.close() }
    }
}
