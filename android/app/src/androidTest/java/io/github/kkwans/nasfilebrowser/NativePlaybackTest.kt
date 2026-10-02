package io.github.kkwans.nasfilebrowser

import android.util.Base64
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import io.github.kkwans.nasfilebrowser.app.ClientModel
import io.github.kkwans.nasfilebrowser.app.ResourceRef
import io.github.kkwans.nasfilebrowser.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Actual libVLC -> localhost Go lease -> HTTP fixture, not a player mock. */
@RunWith(AndroidJUnit4::class)
class NativePlaybackTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)

    @Test fun mkvResumeSeekPauseRecentAndReplacementUseRealNativePlayer() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val media = instrumentation.context.assets.open("media/fixture.mkv").use { it.readBytes() }
        val source = Fixture(media)
        val storage = ProfileStore(ClientDatabase.get(instrumentation.targetContext), CredentialVault(instrumentation.targetContext))
        val profile = storage.save(ServerProfile(name = "Native playback fixture", address = source.url))
        lateinit var model: ClientModel
        activity.scenario.onActivity { model = ViewModelProvider(it)[ClientModel::class.java] }
        val file = ResourceRef("/fixture.mkv", "/fixture.mkv", "Native playback fixture.mkv", false, "video", media.size.toLong())
        suspend fun onMain(action: () -> Unit) = withContext(Dispatchers.Main) { action() }
        suspend fun waitUntil(predicate: () -> Boolean) = withTimeout(25_000) { while (!predicate()) delay(100) }
        try {
            onMain { model.selectProfile(profile); model.connectDraft(profile.name, source.url, BackendKind.NAS, "fixture", "fixture-only", "direct") }
            waitUntil { model.state.value.connected && !model.state.value.busy }
            onMain { model.open(file) }
            waitUntil { model.player.state.value.let { it.playing && it.seekable && it.durationMs in 11_500..12_500 && it.positionMs >= 2800 && it.width == 320 } }
            assertTrue("Native audio track must be discovered", model.player.state.value.audio.any { it.id >= 0 })
            onMain { model.player.seek(7000) }
            waitUntil { model.player.state.value.positionMs in 6500..9500 && model.player.state.value.phase != "正在跳转" }
            onMain { model.pausePlayback() }
            waitUntil { !model.player.state.value.playing && source.position >= 6.5 }
            val account = storage.accounts(profile).single()
            val history = PlaybackHistory(ClientDatabase.get(instrumentation.targetContext))
            val saved = withTimeout(10_000) { history.recent(account).first { entries -> entries.any { it.positionMs >= 6500 && it.sync == ProgressSync.SYNCED } }.first() }
            assertEquals("fixture-v1", saved.identity)
            assertTrue(source.rawRequests.get() > 0)
            assertEquals(0, source.unexpected.get())
            val device = UiDevice.getInstance(instrumentation)
            device.executeShellCommand("mkdir -p /sdcard/Download/nfb-client-acceptance")
            device.executeShellCommand("screencap -p /sdcard/Download/nfb-client-acceptance/player-fixture.png")
            onMain { model.leavePlayer(); model.tab("recent") }
            waitUntil { model.recent.value.any { it.resourceKey == file.wirePath } }
            instrumentation.waitForIdleSync()
            device.executeShellCommand("screencap -p /sdcard/Download/nfb-client-acceptance/recent-fixture.png")
            onMain { model.openRecent(saved) }
            waitUntil { model.player.state.value.playing && model.player.state.value.positionMs >= saved.positionMs - 1500 }
            onMain { model.pausePlayback() }
            waitUntil { !model.player.state.value.playing && model.state.value.progressStatus == "续播已同步" }
            source.stallNextRead.set(true)
            onMain { model.togglePlayback() }
            assertTrue(withContext(Dispatchers.IO) { source.resumeRead.await(5, TimeUnit.SECONDS) })
            activity.scenario.moveToState(Lifecycle.State.CREATED)
            source.releaseRead.countDown()
            activity.scenario.moveToState(Lifecycle.State.RESUMED)
            delay(500)
            assertFalse("A late resume response must not play after backgrounding", model.player.state.value.playing)
            assertFalse(model.state.value.busy)
            source.identity = "fixture-v2"
            onMain { model.togglePlayback() }
            waitUntil { model.state.value.selected == null && model.state.value.error?.contains("文件已变化") == true }
            assertFalse("Replacement must not resume an old media lease", model.player.state.value.playing)
        } finally {
            source.releaseRead.countDown()
            onMain { model.disconnect() }
            storage.remove(profile)
            source.close()
        }
    }

    private class Fixture(private val media: ByteArray) : Closeable {
        private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        private val sockets = ConcurrentHashMap.newKeySet<Socket>()
        val rawRequests = AtomicInteger()
        val unexpected = AtomicInteger()
        val stallNextRead = AtomicBoolean()
        val resumeRead = CountDownLatch(1)
        val releaseRead = CountDownLatch(1)
        @Volatile var position = 3.0
        @Volatile var identity = "fixture-v1"
        @Volatile private var updated = 1L
        private val acceptor = Thread({
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: Exception) { break }
                sockets.add(socket)
                Thread({ try { serve(socket) } catch (_: Exception) { /* canceled transport */ } finally { sockets.remove(socket); socket.close() } }, "nfb-native-media").apply { isDaemon = true; start() }
            }
        }, "nfb-native-source").apply { isDaemon = true; start() }
        val url = "http://127.0.0.1:${server.localPort}"
        private fun serve(socket: Socket) {
            socket.soTimeout = 15_000
            val reader = socket.getInputStream().bufferedReader(Charsets.UTF_8)
            val request = (reader.readLine() ?: return).split(' ')
            val endpoint = request[1]
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = reader.readLine() ?: return
                if (line.isEmpty()) break
                headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
            }
            val body = CharArray(headers["content-length"]?.toIntOrNull() ?: 0)
            var count = 0
            while (count < body.size) { val size = reader.read(body, count, body.size - count); if (size < 0) return; count += size }
            fun send(bytes: ByteArray, type: String = "application/json", status: Int = 200, extra: String = "") {
                socket.getOutputStream().apply {
                    write("HTTP/1.1 $status OK\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n$extra\r\n".toByteArray(Charsets.US_ASCII))
                    if (request[0] != "HEAD") write(bytes)
                    flush()
                }
            }
            if (endpoint != "/api/login" && headers["x-auth"].isNullOrEmpty()) { send(ByteArray(0), status = 403); return }
            when {
                endpoint == "/api/login" -> {
                    val payload = JSONObject().put("user", JSONObject().put("id", 71).put("username", "fixture"))
                    val token = "header." + Base64.encodeToString(payload.toString().toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING) + ".signature"
                    send(token.toByteArray(), "text/plain")
                }
                endpoint == "/api/resources/" -> send(JSONObject().put("items", JSONArray().put(JSONObject().put("path", "/fixture.mkv").put("wirePath", "/fixture.mkv").put("name", "Native playback fixture.mkv").put("type", "video").put("size", media.size))).toString().toByteArray())
                endpoint.startsWith("/api/media/playback") -> {
                    if (request[0] == "GET" && stallNextRead.compareAndSet(true, false)) { resumeRead.countDown(); releaseRead.await(10, TimeUnit.SECONDS) }
                    if (request[0] == "PUT") { position = JSONObject(String(body)).getDouble("position"); updated = System.currentTimeMillis() }
                    send(JSONObject().put("identity", identity).put("position", position).put("duration", 12.0).put("updatedAt", updated).put("exists", true).toString().toByteArray())
                }
                endpoint.startsWith("/api/raw/fixture.mkv") -> {
                    check(!endpoint.contains("inline=true"))
                    rawRequests.incrementAndGet()
                    val range = headers["range"]?.removePrefix("bytes=")
                    if (range == null) send(media, "video/x-matroska", extra = "Accept-Ranges: bytes\r\n")
                    else {
                        val start = range.substringBefore('-').toInt()
                        val end = range.substringAfter('-').toIntOrNull()?.coerceAtMost(media.lastIndex) ?: media.lastIndex
                        if (start > end) send(ByteArray(0), status = 416, extra = "Content-Range: bytes */${media.size}\r\n")
                        else send(media.copyOfRange(start, end + 1), "video/x-matroska", 206, "Accept-Ranges: bytes\r\nContent-Range: bytes $start-$end/${media.size}\r\n")
                    }
                }
                else -> { unexpected.incrementAndGet(); send(ByteArray(0), status = 404) }
            }
        }
        override fun close() { server.close(); sockets.forEach { runCatching { it.close() } }; acceptor.join(1000) }
    }
}
