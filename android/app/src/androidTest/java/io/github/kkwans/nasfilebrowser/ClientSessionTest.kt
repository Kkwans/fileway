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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class ClientSessionTest {
    @Test fun nativeHttpLoginSwitchAndRestorationKeepAccountsAndLateResponsesIsolated() = runBlocking {
        val application = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val storage = ProfileStore(ClientDatabase.get(application), CredentialVault(application))
        val a = Fixture("source-a"); val b = Fixture("source-b")
        val profileA = storage.save(ServerProfile(name = "Fixture A", address = a.url))
        val profileB = storage.save(ServerProfile(name = "Fixture B", address = b.url))
        val holder = ViewModelStore()
        val model = withContext(Dispatchers.Main) { ClientModel(application).also { holder.put("fixture", it) } }
        suspend fun waitReady(profile: ServerProfile, path: String = "/") = withTimeout(15_000) {
            model.state.first { it.connected && !it.busy && it.profile?.id == profile.id && it.path == path }
        }
        try {
            withContext(Dispatchers.Main) { model.selectProfile(profileA); model.connectDraft(profileA.name, a.url, BackendKind.NAS, "one", "test-fixture", "direct") }
            waitReady(profileA)
            withContext(Dispatchers.Main) { model.open(ResourceRef("/a", "/a", "A", true, "", 0)) }
            waitReady(profileA, "/a")
            val accountOne = storage.accounts(profileA).single()
            withContext(Dispatchers.Main) { model.open(ResourceRef("/late", "/late", "Late", true, "", 0)) }
            assertTrue(withContext(Dispatchers.IO) { a.lateStarted.await(5, TimeUnit.SECONDS) })
            withContext(Dispatchers.Main) { model.selectProfile(profileB); model.connectDraft(profileB.name, b.url, BackendKind.NAS, "one", "test-fixture", "direct") }
            a.releaseLate.countDown()
            val stateB = waitReady(profileB)
            assertEquals("source-b", stateB.files.single().name)
            assertEquals("/a", storage.directory(accountOne)?.path)
            withContext(Dispatchers.Main) { model.selectProfile(profileA); model.restore(accountOne) }
            assertEquals("source-a", waitReady(profileA, "/a").files.single().name)
            withContext(Dispatchers.Main) { model.connectDraft(profileA.name, a.url, BackendKind.NAS, "two", "test-fixture", "direct") }
            waitReady(profileA)
            assertEquals(2, storage.accounts(profileA).size)
            assertEquals("/a", storage.directory(accountOne)?.path)
            withContext(Dispatchers.Main) { model.selectProfile(profileA); model.restore(accountOne) }
            waitReady(profileA, "/a")
            assertTrue(model.state.value.serverLabel.endsWith("one"))
        } finally {
            a.releaseLate.countDown()
            withContext(Dispatchers.Main) { holder.clear() }
            storage.remove(profileA); storage.remove(profileB)
            a.close(); b.close()
        }
    }

    internal class Fixture(private val label: String) : Closeable {
        val logins = AtomicInteger()
        @Volatile var rejectRequests = false
        private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        private val sockets = ConcurrentHashMap.newKeySet<Socket>()
        private val acceptor = Thread({
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: Exception) { break }
                sockets.add(socket)
                Thread({ try { serve(socket) } catch (_: Exception) { /* canceled client */ } finally { sockets.remove(socket); socket.close() } }, "nfb-fixture-request").apply { isDaemon = true; start() }
            }
        }, "nfb-fixture-server").apply { isDaemon = true; start() }
        val url = "http://127.0.0.1:${server.localPort}"
        val lateStarted = CountDownLatch(1)
        val releaseLate = CountDownLatch(1)
        private fun serve(socket: Socket) {
            socket.soTimeout = 10_000
            val reader = socket.getInputStream().bufferedReader(Charsets.UTF_8)
            val line = reader.readLine() ?: return
            val endpoint = line.split(' ')[1]
            val headers = mutableMapOf<String, String>()
            while (true) {
                val header = reader.readLine() ?: return
                if (header.isEmpty()) break
                headers[header.substringBefore(':').lowercase()] = header.substringAfter(':').trim()
            }
            val body = CharArray(headers["content-length"]?.toIntOrNull() ?: 0)
            var read = 0
            while (read < body.size) { val count = reader.read(body, read, body.size - read); if (count < 0) return; read += count }
            if (rejectRequests && endpoint != "/api/login") {
                socket.getOutputStream().apply { write("HTTP/1.1 401 Unauthorized\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); flush() }
                return
            }
            val response: String
            val type: String
            when {
                endpoint == "/api/login" -> {
                    logins.incrementAndGet()
                    val username = JSONObject(String(body)).getString("username")
                    val payload = JSONObject().put("user", JSONObject().put("id", if (username == "two") 2 else 1).put("username", username))
                    response = "header." + Base64.encodeToString(payload.toString().toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING) + ".signature"
                    type = "text/plain"
                }
                endpoint.startsWith("/api/resources/") -> {
                    check(headers["x-auth"].orEmpty().count { it == '.' } == 2)
                    if (endpoint == "/api/resources/late") { lateStarted.countDown(); releaseLate.await(10, TimeUnit.SECONDS) }
                    response = JSONObject().put("items", JSONArray().put(JSONObject().put("path", "/folder").put("wirePath", "/folder").put("name", label).put("isDir", true))).toString()
                    type = "application/json"
                }
                else -> error("unexpected fixture endpoint")
            }
            val bytes = response.toByteArray(Charsets.UTF_8)
            socket.getOutputStream().apply {
                write("HTTP/1.1 200 OK\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                write(bytes); flush()
            }
        }
        override fun close() { server.close(); sockets.forEach { runCatching { it.close() } }; acceptor.join(1000) }
    }
}
