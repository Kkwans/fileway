package io.github.kkwans.nasfilebrowser

import android.content.Context
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.kkwans.nasfilebrowser.data.*
import io.github.kkwans.nasfilebrowser.download.DownloadPendingException
import io.github.kkwans.nasfilebrowser.download.DownloadRecord
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Exercises Remote -> DownloadRuntime -> JNI/authenticated HTTP, not a copied
 * status classifier. Only owned profiles/vault records; no download/session reset.
 * Uses only existing product APIs so the new test APK can run against old debug.
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class DownloadPendingRecoveryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val database get() = ClientDatabase.get(context)
    private val store get() = ProfileStore(database, CredentialVault(context))
    private val transientStatuses = listOf(503, 408, 429, 500, 599)

    private inner class OwnedSource(metadataFailure: Int, rangeFailure: Int) {
        val bytes = ByteArray(96) { (it * 17 + 3).toByte() }
        val metadataStatus = AtomicInteger(metadataFailure)
        val rawStatuses = ConcurrentHashMap<String, Int>().apply { put("owned.bin", rangeFailure) }
        val modified = AtomicReference("owned-pending-v1")
        val metadataReads = AtomicInteger(); val rawReads = AtomicInteger()
        private val library = LibraryFixtureData()
        val source = ClientSearchTest.Fixture(imageBodies = mapOf("owned.bin" to bytes), library = library,
            rawStatusCodes = rawStatuses, responseOverride = { method, uri, _ ->
                when {
                    method == "GET" && uri.path == "/api/resources/owned.bin" && uri.rawQuery == "metadata=1" -> {
                        metadataReads.incrementAndGet()
                        if (metadataStatus.get() != 200) "{}" to metadataStatus.get() else
                            JSONObject().put("path", "/owned.bin").put("wirePath", "/owned.bin").put("name", "owned.bin")
                                .put("isDir", false).put("type", "video").put("size", bytes.size).put("modified", modified.get()).toString() to 200
                    }
                    method == "GET" && uri.path == "/api/raw/owned.bin" -> { rawReads.incrementAndGet(); null }
                    else -> null
                }
            })
        var profile: ServerProfile? = null
        lateinit var record: DownloadRecord
        suspend fun start() {
            val saved = store.save(ServerProfile(name = "Owned pending recovery", address = source.url)); profile = saved
            val session = NasSession.login(saved, "one", "fixture-only")
            try {
                val account = store.saveLogin(saved, session.identity.id, session.identity.username, session.token())
                record = DownloadRecord(UUID.randomUUID().toString(), 1, account.key, saved.id, saved.sourceRevision,
                    "/owned.bin", "/owned.bin", "owned.bin", "video", bytes.size.toLong(), "owned-pending-v1", "${bytes.size}/owned-pending-v1",
                    "Owned pending recovery", "", status = "paused", createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis())
            } finally { session.close() }
        }
        fun reader(): DataSource = Class.forName("io.github.kkwans.nasfilebrowser.download.DownloadIndex\$Remote")
            .getDeclaredConstructor(Context::class.java, DownloadRecord::class.java).apply { isAccessible = true }
            .newInstance(context, record) as DataSource
        fun spec(): DataSpec = DataSpec.Builder().setUri("fileway-download://${record.id}/owned.bin")
            .setPosition(7).setLength(23).build()
        suspend fun close() { source.close(); profile?.let { store.remove(it) } }
    }
    private suspend fun owned(metadataStatus: Int, rangeStatus: Int, block: suspend (OwnedSource) -> Unit) {
        val previous = database.profiles().activeSession()
        val source = OwnedSource(metadataStatus, rangeStatus)
        try { source.start(); block(source) }
        finally { withContext(NonCancellable) {
            source.close()
            assertTrue("Owned reader must not change the original active session", previous == database.profiles().activeSession())
        } }
    }
    private suspend fun openFailure(source: OwnedSource): Throwable {
        val reader = source.reader()
        try {
            return withContext(Dispatchers.IO) {
                runCatching { reader.open(source.spec()) }.exceptionOrNull()
                    ?: throw AssertionError("Fault injection did not reach the actual Remote reader")
            }
        } finally { withContext(Dispatchers.IO) { reader.close() } }
    }
    private suspend fun recoveredBytes(source: OwnedSource) {
        source.metadataStatus.set(200); source.rawStatuses["owned.bin"] = 200
        val reader = source.reader()
        try { withContext(Dispatchers.IO) {
            reader.open(source.spec())
            val actual = ByteArray(23); var read = 0
            while (read < actual.size) {
                val count = reader.read(actual, read, actual.size - read)
                assertTrue("Recovered reader must return original bytes", count > 0); read += count
            }
            assertEquals(C.RESULT_END_OF_INPUT, reader.read(ByteArray(1), 0, 1))
            assertArrayEquals(source.bytes.copyOfRange(7, 30), actual)
        } } finally { withContext(Dispatchers.IO) { reader.close() } }
        assertTrue("Recovery must actually reach owned metadata", source.metadataReads.get() > 0)
        assertTrue("Recovery must actually reach owned raw bytes", source.rawReads.get() > 0)
    }

    @Test fun transientMetadataStatusesBecomePendingAndRecoverOriginalBytes(): Unit = runBlocking {
        for (status in transientStatuses) owned(status, 200) { source ->
            val failure = openFailure(source)
            assertTrue("Metadata HTTP $status must remain recoverable: ${failure.javaClass.simpleName}", failure is DownloadPendingException)
            assertTrue(failure.cause is ServiceException)
            assertEquals(status, (failure.cause as ServiceException).status)
            assertEquals("Metadata failure must not read raw media", 0, source.rawReads.get())
            recoveredBytes(source)
        }
    }

    @Test fun transientRangeStatusesBecomePendingAndRecoverOriginalBytes(): Unit = runBlocking {
        for (status in transientStatuses) owned(200, status) { source ->
            val failure = openFailure(source)
            assertTrue("Range HTTP $status must remain recoverable: ${failure.javaClass.simpleName}", failure is DownloadPendingException)
            assertTrue(failure.cause is HttpDataSource.InvalidResponseCodeException)
            assertEquals(status, (failure.cause as HttpDataSource.InvalidResponseCodeException).responseCode)
            recoveredBytes(source)
        }
    }

    @Test fun permanentStatusesAndChangedIdentityOrSourceDoNotBecomePending(): Unit = runBlocking {
        for (status in listOf(401, 403, 404)) {
            owned(status, 200) { source ->
                val failure = openFailure(source)
                assertFalse("Metadata HTTP $status must not wait forever", failure is DownloadPendingException)
                assertTrue(failure is ServiceException); assertEquals(status, (failure as ServiceException).status)
                assertEquals(0, source.rawReads.get())
            }
            owned(200, status) { source ->
                val failure = openFailure(source)
                assertFalse("Range HTTP $status must not wait forever", failure is DownloadPendingException)
                assertTrue(failure is HttpDataSource.InvalidResponseCodeException)
                assertEquals(status, (failure as HttpDataSource.InvalidResponseCodeException).responseCode)
            }
        }
        owned(200, 200) { source ->
            source.modified.set("owned-changed-identity")
            val failure = openFailure(source)
            assertTrue("A changed original file must stay an explicit failure", failure is IllegalStateException)
            assertEquals(0, source.rawReads.get())
        }
        owned(200, 200) { source ->
            store.save(requireNotNull(source.profile).copy(address = "http://127.0.0.1:1"))
            val failure = openFailure(source)
            assertTrue("A changed source revision must not be retried as missing data", failure is IllegalStateException)
            assertEquals(0, source.metadataReads.get())
            assertEquals(0, source.rawReads.get())
        }
    }
}
