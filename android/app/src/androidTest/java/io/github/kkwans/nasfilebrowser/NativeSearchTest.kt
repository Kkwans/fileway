package io.github.kkwans.nasfilebrowser

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.kkwans.nasfilebrowser.core.NativeTransport
import io.github.kkwans.nasfilebrowser.data.NasSession
import io.github.kkwans.nasfilebrowser.data.ServerProfile
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Real JNI/Go/HTTP NDJSON metadata, without an activity/player or real identity. */
@RunWith(AndroidJUnit4::class)
class NativeSearchTest {
    @Test fun incrementalMetadataAndCancellationUseTheActualNativeBridge() = runBlocking {
        val fixture = SearchFixture()
        val profile = ServerProfile(name = "Owned search fixture", address = fixture.url)
        val session = try { NasSession.login(profile, "fixture", "fixture-only") }
            catch (error: Throwable) { fixture.close(); throw error }
        var handle: String? = null
        suspend fun start(query: String): String = NativeTransport.call(JSONObject().put("op", "search_start")
            .put("session", session.id).put("path", "/").put("query", query).put("scope", "current")) as String
        suspend fun poll(id: String): JSONObject = NativeTransport.call(JSONObject().put("op", "search_poll")
            .put("session", session.id).put("search", id)) as JSONObject
        suspend fun next(id: String): JSONObject = withTimeout(5000) {
            var batch = poll(id)
            while (batch.getJSONArray("items").length() == 0 && !batch.getBoolean("done")) {
                delay(25)
                batch = poll(id)
            }
            batch
        }
        try {
            handle = start("电影🎬 & + #")
            val first = next(handle)
            assertFalse("First result must arrive before the server completes", first.getBoolean("done"))
            assertEquals("电影🎬 # ?.mkv", first.getJSONArray("items").getJSONObject(0).getString("name"))
            assertEquals(1L, first.getLong("count"))
            fixture.release.set(true)
            val summary = next(handle)
            assertTrue(summary.getBoolean("done"))
            assertEquals("completed", summary.getJSONObject("summary").getString("reason"))
            handle = null

            fixture.release.set(false)
            handle = start("cancel")
            assertFalse(next(handle).getBoolean("done"))
            NativeTransport.call(JSONObject().put("op", "search_cancel").put("session", session.id).put("search", handle))
            assertTrue("Cancel must close the actual upstream HTTP request", fixture.canceled.await(5, TimeUnit.SECONDS))
            handle = null
            assertTrue("Fixture only accepts its search/login routes and auth", fixture.valid.get())
        } finally {
            handle?.let { NativeTransport.call(JSONObject().put("op", "search_cancel").put("session", session.id).put("search", it)) }
            session.close()
            fixture.close()
        }
    }

    private class SearchFixture : Closeable {
        private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        private val sockets = ConcurrentHashMap.newKeySet<Socket>()
        val release = AtomicBoolean()
        val canceled = CountDownLatch(1)
        val valid = AtomicBoolean(true)
        val url = "http://127.0.0.1:${server.localPort}"
        private val payload = JSONObject().put("user", JSONObject().put("id", 91).put("username", "fixture"))
        private val token = "header." + Base64.encodeToString(payload.toString().toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING) + ".signature"
        private val acceptor = Thread({
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: Exception) { break }
                sockets.add(socket)
                Thread({ try { serve(socket) } catch (_: Exception) { } finally { sockets.remove(socket); socket.close() } }, "nfb-search-fixture").apply { isDaemon = true; start() }
            }
        }, "nfb-search-accept").apply { isDaemon = true; start() }

        private fun serve(socket: Socket) {
            socket.soTimeout = 5000
            val input = socket.getInputStream().bufferedReader(Charsets.UTF_8)
            val request = (input.readLine() ?: return).split(' ')
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = input.readLine() ?: return
                if (line.isEmpty()) break
                headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
            }
            val output = socket.getOutputStream()
            if (request[1] == "/api/login") {
                val body = token.toByteArray()
                output.write("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                output.write(body); output.flush(); return
            }
            val endpoint = java.net.URI(request[1])
            val query = endpoint.rawQuery.split('&').associate { field -> field.substringBefore('=') to java.net.URLDecoder.decode(field.substringAfter('='), "UTF-8") }
            if (request[0] != "GET" || endpoint.path != "/api/search/" || query["scope"] != "current" || query["query"] !in setOf("电影🎬 & + #", "cancel") || headers["x-auth"] != token) {
                valid.set(false); return
            }
            output.write("HTTP/1.1 200 OK\r\nContent-Type: application/x-ndjson; charset=utf-8\r\nConnection: close\r\n\r\n".toByteArray())
            val item = JSONObject().put("name", "电影🎬 # ?.mkv").put("path", "电影🎬 # ?.mkv").put("dir", false).put("size", 123)
            output.write(("\n" + JSONObject().put("type", "result").put("item", item).toString() + "\n").toByteArray(Charsets.UTF_8))
            output.flush()
            socket.soTimeout = 100
            val limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (!release.get() && System.nanoTime() < limit) {
                try {
                    if (input.read() == -1) { canceled.countDown(); return }
                } catch (_: SocketTimeoutException) { }
            }
            output.write((JSONObject().put("type", "summary").put("reason", "completed").put("count", 1).toString() + "\n").toByteArray(Charsets.UTF_8))
            output.flush()
        }

        override fun close() { release.set(true); server.close(); sockets.forEach { runCatching { it.close() } }; acceptor.join(1000) }
    }
}
