package io.github.kkwans.nasfilebrowser

import android.app.Application
import android.util.Base64
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.kkwans.nasfilebrowser.app.ClientModel
import io.github.kkwans.nasfilebrowser.app.ResourceRef
import io.github.kkwans.nasfilebrowser.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class ClientSearchTest {
    @Test fun savedLayoutRestoresAfterAccountSwitchAndNewViewModel(): Unit = runBlocking {
        Harness().use { h ->
            h.connect()
            val profile = h.model.state.value.profile!!
            h.main { fileLayout(FileLayout.LIST); fileLayout(FileLayout.DETAIL); fileLayout(FileLayout.COMPACT) }
            withTimeout(5000) { h.model.state.first { it.fileLayout == FileLayout.COMPACT } }
            h.main { connectDraft(profile.name, h.fixture.url, BackendKind.NAS, "two", "fixture-only", "direct") }
            withTimeout(10_000) { h.model.state.first { it.connected && !it.busy && it.accountName == "two" } }
            assertEquals(FileLayout.COVER, h.model.state.value.fileLayout)
            h.main { fileLayout(FileLayout.LIST) }
            withTimeout(5000) { h.model.state.first { it.fileLayout == FileLayout.LIST } }
            h.main { connectDraft(profile.name, h.fixture.url, BackendKind.NAS, "one", "fixture-only", "direct") }
            withTimeout(10_000) { h.model.state.first { it.connected && !it.busy && it.accountName == "one" } }
            assertEquals(FileLayout.COMPACT, h.model.state.value.fileLayout)
            assertEquals("/library", h.model.state.value.path)
            h.main { disconnect() }
            val holder = ViewModelStore()
            val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
            val fresh = withContext(Dispatchers.Main) { ClientModel(app).also { holder.put("layout-restart", it) } }
            try {
                withContext(Dispatchers.Main) { fresh.selectProfile(profile); fresh.connectDraft(profile.name, h.fixture.url, BackendKind.NAS, "one", "fixture-only", "direct") }
                withTimeout(10_000) { fresh.state.first { it.connected && !it.busy } }
                assertEquals(FileLayout.COMPACT, fresh.state.value.fileLayout)
                assertEquals("/library", fresh.state.value.path)
            } finally { withContext(Dispatchers.Main) { holder.clear() } }
        }
    }

    @Test fun incrementalSearchCancelReplaceAndRetryUseRealNativeHttp(): Unit = runBlocking {
        Harness().use { h ->
            h.connect()
            h.main { openSearch(); search.query("slow"); search.submit() }
            val early = withTimeout(5000) { h.model.search.state.first { it.items.isNotEmpty() } }
            assertTrue(early.running)
            assertEquals("/library", early.basePath)
            h.main { search.cancel() }
            assertTrue(withContext(Dispatchers.IO) { h.fixture.canceled.await(5, TimeUnit.SECONDS) })
            assertEquals(SearchEnding.CANCELED, h.model.search.state.value.ending)
            assertEquals(1, h.model.search.state.value.items.size)
            h.main { search.query("late-search"); search.submit() }
            assertTrue(withContext(Dispatchers.IO) { h.fixture.searchStarted.await(5, TimeUnit.SECONDS) })
            h.main { search.query("fresh"); search.submit() }
            h.fixture.releaseSearch.countDown()
            val fresh = withTimeout(5000) { h.model.search.state.first { it.ending == SearchEnding.COMPLETED } }
            assertEquals(listOf("fresh"), fresh.items.map { it.name })
            delay(150)
            assertEquals(listOf("fresh"), h.model.search.state.value.items.map { it.name })
            h.main { search.query("retry"); search.submit() }
            val failed = withTimeout(5000) { h.model.search.state.first { it.ending == SearchEnding.FAILED } }
            assertEquals("retry", failed.query)
            assertEquals(1, failed.items.size)
            h.main { search.submit() }
            val retried = withTimeout(5000) { h.model.search.state.first { it.ending == SearchEnding.COMPLETED } }
            assertEquals(1, retried.items.size)
            assertEquals(2, h.fixture.retryAttempts.get())
            h.main { assertTrue(back()) }
            assertFalse(h.model.search.state.value.open)
            assertEquals("/library", h.model.state.value.path)
        }
    }

    @Test fun metadataIdentityIsRequiredAndCanceledSelectionCannotNavigate(): Unit = runBlocking {
        Harness().use { h ->
            h.connect()
            suspend fun result(query: String): SearchResult {
                h.main { if (!search.state.value.open) openSearch(); search.query(query); search.submit() }
                return withTimeout(5000) { h.model.search.state.first { it.ending == SearchEnding.COMPLETED } }.items.single()
            }
            val ambiguous = result("ambiguous")
            h.main { search.openResult(ambiguous) }
            assertTrue(h.model.search.state.value.message.orEmpty().contains("编码"))
            assertEquals(0, h.fixture.metadataReads.get())
            val moved = result("moved")
            h.main { search.openResult(moved) }
            withTimeout(5000) { h.model.search.state.first { it.openingPath == null && it.message != null } }
            assertEquals("/library", h.model.state.value.path)
            assertTrue(h.model.search.state.value.message.orEmpty().contains("来源已变化"))
            val changedType = result("changed-type")
            h.main { search.openResult(changedType) }
            withTimeout(5000) { h.model.search.state.first { it.openingPath == null && it.message != null } }
            assertEquals("/library", h.model.state.value.path)
            assertTrue(h.model.search.state.value.message.orEmpty().contains("类型已变化"))
            val late = result("late-folder")
            h.main { search.openResult(late) }
            assertTrue(withContext(Dispatchers.IO) { h.fixture.metadataStarted.await(5, TimeUnit.SECONDS) })
            h.main { search.cancel() }
            h.fixture.releaseMetadata.countDown()
            delay(250)
            assertEquals("/library", h.model.state.value.path)
            assertNull(h.model.search.state.value.openingPath)
            val folder = result("找到目录+100%")
            h.main { search.openResult(folder) }
            val opened = withTimeout(5000) { h.model.state.first { !it.busy && it.path == "/library/找到目录+100%" } }
            assertEquals("/library/%e6%89%be%e5%88%b0%e7%9b%ae%e5%bd%95%2b100%25", opened.wirePath)
            assertFalse(h.model.search.state.value.open)
            h.main { assertTrue(back()) }
            withTimeout(5000) { h.model.state.first { !it.busy && it.path == "/library" } }
        }
    }

    @Test fun accountSwitchDiscardsLateSearchAndBackgroundCancelsPartialSearch(): Unit = runBlocking {
        Harness().use { h ->
            h.connect()
            h.main { openSearch(); search.query("late-search"); search.submit() }
            assertTrue(withContext(Dispatchers.IO) { h.fixture.searchStarted.await(5, TimeUnit.SECONDS) })
            h.main { connectDraft("Search fixture", h.fixture.url, BackendKind.NAS, "two", "fixture-only", "direct") }
            withTimeout(10_000) { h.model.state.first { it.connected && !it.busy && it.serverLabel.endsWith("two") } }
            h.fixture.releaseSearch.countDown()
            delay(250)
            assertFalse(h.model.search.state.value.open)
            assertTrue(h.model.search.state.value.items.isEmpty())
            assertEquals("/", h.model.state.value.path)
            h.main { openSearch(); search.query("slow"); search.submit() }
            withTimeout(5000) { h.model.search.state.first { it.items.isNotEmpty() } }
            h.main { foreground(false) }
            assertEquals(SearchEnding.CANCELED, h.model.search.state.value.ending)
            assertTrue(withContext(Dispatchers.IO) { h.fixture.canceled.await(5, TimeUnit.SECONDS) })
            h.main { foreground(true) }
            assertFalse(h.model.search.state.value.running)
        }
    }

    private class Harness : Closeable {
        private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        private val store = ProfileStore(ClientDatabase.get(app), CredentialVault(app))
        private val holder = ViewModelStore()
        val fixture = Fixture()
        val model = runBlocking(Dispatchers.Main) { ClientModel(app).also { holder.put("search", it) } }
        private var profile: ServerProfile? = null
        suspend fun main(action: ClientModel.() -> Unit) = withContext(Dispatchers.Main) { model.action() }
        suspend fun connect() {
            profile = store.save(ServerProfile(name = "Search fixture", address = fixture.url))
            main { selectProfile(profile); foreground(true); connectDraft("Search fixture", fixture.url, BackendKind.NAS, "one", "fixture-only", "direct") }
            withTimeout(10_000) { model.state.first { it.connected && !it.busy } }
            main { open(ResourceRef("/library", "/library", "Library", true, "", 0)) }
            withTimeout(5000) { model.state.first { !it.busy && it.path == "/library" } }
        }
        override fun close() {
            fixture.releaseMetadata.countDown(); fixture.releaseSearch.countDown()
            runBlocking { withContext(Dispatchers.Main) { holder.clear() }; profile?.let { store.remove(it) } }
            fixture.close()
        }
    }

    internal class Fixture(private val directoryItems: List<String> = emptyList(), private val previewBody: ByteArray? = null,
        private val modified: String = "") : Closeable {
        private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        private val sockets = ConcurrentHashMap.newKeySet<Socket>()
        val url = "http://127.0.0.1:${server.localPort}"
        val canceled = CountDownLatch(1)
        val searchStarted = CountDownLatch(1); val releaseSearch = CountDownLatch(1)
        val metadataStarted = CountDownLatch(1); val releaseMetadata = CountDownLatch(1)
        val playbackStarted = CountDownLatch(1); val releasePlayback = CountDownLatch(1)
        val retryAttempts = AtomicInteger(); val metadataReads = AtomicInteger()
        val previewSeen = CountDownLatch(1)
        val previewPaths = ConcurrentHashMap.newKeySet<String>()
        val searchRequests = ConcurrentHashMap.newKeySet<String>()
        private val acceptor = Thread({
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: Exception) { break }
                sockets.add(socket)
                Thread({ try { serve(socket) } catch (_: Exception) { /* canceled fixture request */ }
                    finally { sockets.remove(socket); socket.close() } }, "nfb-search-model-http").apply { isDaemon = true; start() }
            }
        }, "nfb-search-model-listener").apply { isDaemon = true; start() }
        private fun serve(socket: Socket) {
            socket.soTimeout = 10_000
            val reader = socket.getInputStream().bufferedReader(Charsets.UTF_8)
            val request = reader.readLine() ?: return
            val uri = URI(request.split(' ')[1])
            val headers = mutableMapOf<String, String>()
            while (true) { val line = reader.readLine() ?: return; if (line.isEmpty()) break
                headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim() }
            val body = CharArray(headers["content-length"]?.toIntOrNull() ?: 0)
            var read = 0
            while (read < body.size) { val count = reader.read(body, read, body.size - read); if (count < 0) return; read += count }
            if (uri.path == "/api/login") {
                val name = JSONObject(String(body)).getString("username")
                val payload = JSONObject().put("user", JSONObject().put("id", if (name == "two") 2 else 1).put("username", name))
                reply(socket, "header." + Base64.encodeToString(payload.toString().toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING) + ".signature", "text/plain")
                return
            }
            check(headers["x-auth"].orEmpty().count { it == '.' } == 2)
            if (uri.path.startsWith("/api/preview/thumb/")) {
                previewPaths.add(uri.path)
                previewSeen.countDown()
                val bytes = previewBody ?: byteArrayOf()
                val status = if (previewBody == null) "404 Not Found" else "200 OK"
                socket.getOutputStream().apply {
                    write("HTTP/1.1 $status\r\nContent-Type: image/png\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    write(bytes); flush()
                }
                return
            }
            if (uri.path.startsWith("/api/search")) {
                searchRequests.add(uri.rawPath + "?" + uri.rawQuery)
                val query = uri.rawQuery.split('&').first { it.startsWith("query=") }.substringAfter('=')
                    .let { URLDecoder.decode(it, "UTF-8") }
                if (query == "late-search") { searchStarted.countDown(); releaseSearch.await(10, TimeUnit.SECONDS) }
                val output = socket.getOutputStream()
                output.write("HTTP/1.1 200 OK\r\nContent-Type: application/x-ndjson\r\nConnection: close\r\n\r\n".toByteArray())
                val path = if (query == "ambiguous") "bad\uFFFDname" else query
                val item = JSONObject().put("path", path).put("name", query).put("dir", !query.endsWith(".mkv")).put("size", if (query.endsWith(".mkv")) 104857600 else 0).put("modified", modified.ifEmpty { "2026-10-01T00:00:00Z" }).put("riskLevel", "low")
                output.write((JSONObject().put("type", "result").put("item", item).toString() + "\n").toByteArray()); output.flush()
                if (query == "slow") { if (socket.getInputStream().read() == -1) canceled.countDown(); return }
                if (query == "retry" && retryAttempts.incrementAndGet() == 1) return
                output.write((JSONObject().put("type", "summary").put("reason", "completed").put("count", 1).toString() + "\n").toByteArray()); output.flush()
                return
            }
            if (uri.path == "/api/media/playback") {
                playbackStarted.countDown(); releasePlayback.await(10, TimeUnit.SECONDS)
                reply(socket, JSONObject().put("identity", "search-movie-v1").put("position", 0).put("duration", 12).put("exists", false).toString())
                return
            }
            check(uri.path.startsWith("/api/resources/"))
            if (uri.rawQuery == "metadata=1") {
                metadataReads.incrementAndGet()
                if (uri.path.endsWith("/late-folder")) { metadataStarted.countDown(); releaseMetadata.await(10, TimeUnit.SECONDS) }
                val path = uri.path.substringAfter("/api/resources")
                val wire = if (path.endsWith("/moved")) "/library/another" else Regex("%[0-9A-Fa-f]{2}").replace(SearchResult.encodePath(path)) { it.value.lowercase() }
                reply(socket, JSONObject().put("path", path).put("wirePath", wire).put("name", path.substringAfterLast('/')).put("isDir", !path.endsWith("/changed-type") && !path.endsWith(".mkv")).put("size", 0).put("type", "").put("modified", modified).toString())
            } else {
                val items = JSONArray()
                if (uri.path == "/api/resources/") directoryItems.forEach { name ->
                    val image = name.endsWith(".png")
                    items.put(JSONObject().put("name", name).put("path", "/$name").put("wirePath", SearchResult.encodePath("/$name"))
                        .put("isDir", !image && !name.endsWith(".mkv")).put("type", if (image) "image" else if (name.endsWith(".mkv")) "video" else "").put("size", 104857600).put("modified", modified))
                }
                reply(socket, JSONObject().put("items", items).toString())
            }
        }
        private fun reply(socket: Socket, body: String, type: String = "application/json") {
            val bytes = body.toByteArray()
            socket.getOutputStream().apply { write("HTTP/1.1 200 OK\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray()); write(bytes); flush() }
        }
        override fun close() { releasePlayback.countDown(); server.close(); sockets.forEach { runCatching { it.close() } }; acceptor.join(1000) }
    }
}
