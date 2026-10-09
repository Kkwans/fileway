package io.github.kkwans.nasfilebrowser

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.kkwans.nasfilebrowser.data.*
import io.github.kkwans.nasfilebrowser.download.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Real native broker + owned loopback HTTP + uniquely owned MediaStore rows.
 * Never selects/changes the user's active account or download directory. */
@RunWith(AndroidJUnit4::class)
class ZipExportRuntimeTest {
    private class Authority : AutoCloseable {
        val body = ByteArrayOutputStream().also { output -> ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("comma,name.txt")); zip.write("owned ZIP runtime payload".toByteArray()); zip.closeEntry()
        } }.toByteArray()
        val token = "owned." + android.util.Base64.encodeToString("{\"user\":{\"id\":7777,\"username\":\"owned-zip\",\"perm\":{\"download\":true}}}".toByteArray(),
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP) + ".fixture"
        private val server = ServerSocket(0, 10, java.net.InetAddress.getByName("127.0.0.1"))
        private val sockets = CopyOnWriteArrayList<Socket>()
        val ranges = CopyOnWriteArrayList<String>()
        @Volatile var truncate = false
        val url get() = "http://127.0.0.1:${server.localPort}"
        private val acceptor = Thread {
            while (!server.isClosed) try {
                val socket = server.accept(); sockets.add(socket)
                Thread { socket.use { runCatching { serve(it) } }; sockets.remove(socket) }.apply { isDaemon = true; start() }
            } catch (_: Exception) { if (server.isClosed) break }
        }.apply { isDaemon = true; start() }
        private fun serve(socket: Socket) {
            val reader = socket.getInputStream().bufferedReader()
            val request = reader.readLine() ?: return
            val uri = URI(request.split(' ')[1]); val headers = mutableMapOf<String, String>()
            while (true) { val line = reader.readLine() ?: return; if (line.isEmpty()) break; headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim() }
            check(headers["x-auth"] == token)
            val payload: ByteArray; val type: String
            if (uri.path == "/api/resources/") {
                payload = JSONObject().put("path", "/").put("wirePath", "/").put("name", "Owned fixture").put("isDir", true).put("size", 0).put("modified", "2026-10-09T00:00:00Z").toString().toByteArray()
                type = "application/json"
            } else {
                check(uri.path == "/api/raw/" && uri.rawQuery.contains("fileWirePath="))
                ranges.add(headers["range"].orEmpty())
                payload = if (truncate) body.copyOf(body.size - 8) else body
                type = "application/zip; fileway-selection=wire-v1"
            }
            socket.getOutputStream().apply { write("HTTP/1.1 200 OK\r\nContent-Type: $type\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n\r\n".toByteArray()); write(payload); flush() }
        }
        override fun close() { server.close(); sockets.forEach { runCatching { it.close() } }; acceptor.join(1000) }
    }

    @Test fun ownedInterruptedZipRestartsAtZeroAndTruncatedResponseCannotComplete(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = ClientDatabase.get(context); val dao = database.downloads()
        val store = ProfileStore(database, CredentialVault(context)); val target = DownloadTarget(context); val runtime = DownloadRuntime.get(context)
        val authority = Authority(); val ids = mutableListOf<String>(); var profile: ServerProfile? = null
        try {
            val saved = store.save(ServerProfile(name = "Owned ZIP runtime ${UUID.randomUUID()}", address = authority.url)); profile = saved
            val verified = NasSession.restore(saved, authority.token, 7777)
            try { assertTrue(verified.request("GET", "/api/resources/?metadata=1").getBoolean("isDir")) } finally { verified.close() }
            val account = store.saveLogin(saved, 7777, "owned-zip", authority.token)
            for (truncated in listOf(false, true)) {
                authority.truncate = truncated
                val id = UUID.randomUUID().toString(); val now = System.currentTimeMillis()
                var row = DownloadRecord(id, dao.lastJobId() + 1, account.key, saved.id, saved.sourceRevision, "/", "/", "fileway-owned-zip-$id.zip", ZIP_EXPORT_TYPE,
                    -1, "", JSONObject().put("version", 1).put("wires", JSONArray().put("/comma%2Cname.txt")).toString(), "Owned ZIP fixture", "",
                    status = if (truncated) "queued" else "interrupted", downloaded = if (truncated) 0 else 17, createdAt = now, updatedAt = now)
                val uri = target.allocate(row); row = row.copy(localUri = uri.toString()); ids.add(id)
                if (!truncated) context.contentResolver.openOutputStream(uri, "w")!!.use { it.write(ByteArray(17) { 88 }) }
                dao.insert(row); assertTrue(runtime.launch(id) {})
                val done = withTimeout(15_000) { dao.observe().first { rows -> rows.any { it.id == id && it.status in setOf("completed", "failed") } }.single { it.id == id } }
                withTimeout(5000) { runtime.awaitStopped(id) }
                if (truncated) {
                    assertEquals("failed", done.status); assertEquals(-1L, done.expectedSize)
                    assertTrue(done.error.contains("ZIP"))
                } else {
                    assertEquals("completed", done.status); assertEquals(authority.body.size.toLong(), done.expectedSize)
                    val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                    assertArrayEquals(authority.body, bytes)
                }
            }
            assertEquals(2, authority.ranges.size); assertTrue(authority.ranges.all { it.isEmpty() })
        } finally { withContext(NonCancellable) {
            ids.forEach(runtime::cancel); authority.close()
            for (id in ids) { withTimeout(5000) { runtime.awaitStopped(id) }; dao.get(id)?.let { row -> if (row.localUri.isNotEmpty()) target.delete(row); dao.removeRecord(id) } }
            profile?.let { store.remove(it) }
        } }
    }
}
