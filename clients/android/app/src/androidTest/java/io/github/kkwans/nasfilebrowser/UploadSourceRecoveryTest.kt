package io.github.kkwans.nasfilebrowser

import android.net.Uri
import android.provider.DocumentsContract
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
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class UploadSourceRecoveryTest {
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
            assertThrows(IllegalStateException::class.java) { sources.reauthorize(row, uri) }
            assertEquals(4L, dao.get(id)!!.uploaded)
            assertEquals(1, dao.command(id, "queued", 4)); assertEquals(1, dao.claim(id, 5))
            assertEquals(0, dao.reauthorize(id, dao.get(id)!!.generation, source.uri, 6))
        } finally { target.delete(saved); room.close() }
    }
}
