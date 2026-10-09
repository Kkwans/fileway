package io.github.kkwans.nasfilebrowser

import android.util.Base64
import io.github.kkwans.nasfilebrowser.data.SearchResult
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Original byte authority, including a late accepted chunk after cancellation. */
internal class OwnedUploadFixture : Closeable {
    private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    val files = ConcurrentHashMap<String, ByteArray>()
    val lengths = ConcurrentHashMap<String, Long>()
    val uploads = ConcurrentHashMap<String, String>()
    val published = ConcurrentHashMap<String, ByteArray>()
    val deletes = java.util.concurrent.atomic.AtomicInteger()
    @Volatile var durableCancellation = false
    @Volatile var includeTusVersion = true
    @Volatile var deleteStatus = 0
    @Volatile var holdSecondChunk = false
    @Volatile var beforeMetadata: ((String) -> Unit)? = null
    val held = CountDownLatch(1); val release = CountDownLatch(1); val lateAccepted = CountDownLatch(1)
    val url = "http://127.0.0.1:${server.localPort}"
    private val acceptor = Thread({
        while (!server.isClosed) {
            val socket = try { server.accept() } catch (_: Exception) { break }; sockets.add(socket)
            Thread({ try { serve(socket) } catch (_: Exception) { } finally { sockets.remove(socket); socket.close() } }, "fileway-owned-upload").apply { isDaemon = true; start() }
        }
    }, "fileway-owned-upload-source").apply { isDaemon = true; start() }
    private fun serve(socket: Socket) {
        socket.soTimeout = 20_000
        val input = socket.getInputStream().buffered()
        fun line(): String { val bytes = arrayListOf<Byte>(); while (true) { val value = input.read(); if (value < 0 || value == 10) break; if (value != 13) bytes.add(value.toByte()); require(bytes.size < 16384) }; return bytes.toByteArray().toString(Charsets.UTF_8) }
        val request = line().split(' '); if (request.size < 2) return
        val method = request[0]; val uri = URI(request[1]); val headers = linkedMapOf<String, String>()
        while (true) { val row = line(); if (row.isEmpty()) break; headers[row.substringBefore(':').lowercase()] = row.substringAfter(':').trim() }
        val body = ByteArray(headers["content-length"]?.toIntOrNull() ?: 0); var consumed = 0
        while (consumed < body.size) { val count = input.read(body, consumed, body.size - consumed); if (count < 0) return; consumed += count }
        fun reply(status: Int, value: String = "", extra: Map<String, String> = emptyMap()) {
            val bytes = value.toByteArray(); val lines = extra.entries.joinToString("") { "${it.key}: ${it.value}\r\n" }
            socket.getOutputStream().apply { write("HTTP/1.1 $status OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n$lines\r\n".toByteArray()); if (method != "HEAD") write(bytes); flush() }
        }
        if (uri.path == "/api/login") {
            val identity = JSONObject().put("user", JSONObject().put("id", 11).put("username", "fixture")
                .put("perm", JSONObject().put("create", true).put("modify", true).put("download", true).put("rename", true).put("delete", true)))
            reply(200, "owned." + Base64.encodeToString(identity.toString().toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING) + ".fixture"); return
        }
        require(headers["x-auth"].orEmpty().count { it == '.' } == 2)
        if (uri.path == "/api/tags" || uri.path == "/api/favorites") { reply(200, "[]"); return }
        if (uri.path == "/api/resources/" && uri.rawQuery == null) { reply(200, JSONObject().put("items", JSONArray()).toString()); return }
        if (uri.path.startsWith("/api/resources") && method == "GET") {
            val path = uri.path.removePrefix("/api/resources")
            beforeMetadata?.invoke(path)
            val bytes = if (durableCancellation) published[path] ?: files[path]?.takeIf { it.size.toLong() == lengths[path] } else files[path]
            if (path != "/" && bytes == null) { reply(404, "{}"); return }
            reply(200, JSONObject().put("path", path).put("wirePath", SearchResult.encodePath(path)).put("name", path.substringAfterLast('/'))
                .put("isDir", path == "/").put("size", bytes?.size ?: 0).put("modified", "owned-upload-v1").put("type", "blob").toString()); return
        }
        if (uri.path.startsWith("/api/tus/")) {
            val path = uri.path.removePrefix("/api/tus")
            if (durableCancellation && method != "POST" && uploads[path] != headers["x-transfer-id"]) { reply(404, extra = mapOf("Tus-Resumable" to "1.0.0")); return }
            val tus = if (includeTusVersion) mapOf("Tus-Resumable" to "1.0.0") else emptyMap()
            when (method) {
                "POST" -> {
                    if (files.containsKey(path) && !uri.rawQuery.orEmpty().contains("override=true")) { reply(409, "{}"); return }
                    files[path] = byteArrayOf(); lengths[path] = headers.getValue("upload-length").toLong(); uploads[path] = headers.getValue("x-transfer-id")
                    reply(201, extra = mapOf("Location" to uri.rawPath + "?transfer=" + headers.getValue("x-transfer-id"))); return
                }
                "HEAD" -> {
                    val saved = files[path]; if (saved == null) { reply(404); return }
                    reply(200, extra = tus + mapOf("Upload-Offset" to saved.size.toString(), "Upload-Length" to lengths.getValue(path).toString())); return
                }
                "DELETE" -> {
                    deletes.incrementAndGet()
                    if (deleteStatus != 0) { reply(deleteStatus, extra = tus); return }
                    val saved = files[path]
                    if (saved == null) { reply(404, extra = tus); return }
                    if (saved.size.toLong() == lengths[path]) { reply(409, extra = tus); return }
                    files.remove(path); uploads.remove(path); lengths.remove(path)
                    reply(204, extra = tus); return
                }
                "PATCH" -> {
                    val old = files[path] ?: run { reply(404); return }
                    if (headers.getValue("upload-offset").toInt() != old.size) { reply(409); return }
                    if (holdSecondChunk && old.size >= 2*1024*1024) {
                        holdSecondChunk = false; held.countDown(); check(release.await(10, TimeUnit.SECONDS))
                        files[path] = old + body; lateAccepted.countDown()
                    } else files[path] = old + body
                    reply(204, extra = mapOf("Upload-Offset" to files.getValue(path).size.toString())); return
                }
            }
        }
        if (uri.path.startsWith("/api/resources/") && method == "POST") { files[uri.path.removePrefix("/api/resources")] = body; reply(200, "200 OK"); return }
        reply(404, "{}");
    }
    override fun close() { release.countDown(); server.close(); sockets.forEach { runCatching { it.close() } }; acceptor.join(1000) }
}
