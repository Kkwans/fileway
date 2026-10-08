package io.github.kkwans.nasfilebrowser

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.LocalServerSocket
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.media3.ui.SubtitleView
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import io.github.kkwans.nasfilebrowser.data.NasSession
import io.github.kkwans.nasfilebrowser.data.ServerProfile
import io.github.kkwans.nasfilebrowser.player.NativePlayer
import io.github.kkwans.nasfilebrowser.player.PlayerViewport
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Explicit real-source diagnostic. No Room, credential file or history writer.
 * The token arrives only over an ADB-forwarded local socket and remains in memory.
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class RealNasMediaTest {
    @get:Rule val activity = ActivityScenarioRule(EngineProbeActivity::class.java)
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private suspend fun <T> main(block: () -> T): T = withContext(Dispatchers.Main) { block() }
    private suspend fun configuration(): JSONObject = withContext(Dispatchers.IO) {
        val name = "fileway-real-${UUID.randomUUID()}"
        val server = LocalServerSocket(name)
        try {
            instrumentation.sendStatus(2, Bundle().apply { putString("stream", "REAL_MEDIA_SOCKET=$name\n") })
            val socket = server.accept()
            try {
                check(socket.peerCredentials.uid in setOf(0, 2000)) { "Configuration must come from the authorized ADB host" }
                socket.soTimeout = 20_000
                val bytes = java.io.ByteArrayOutputStream()
                while (true) {
                    val value = socket.inputStream.read()
                    check(value >= 0) { "Incomplete private configuration" }
                    if (value == 10) break
                    check(bytes.size() < 65_536) { "Configuration is too large" }
                    bytes.write(value)
                }
                val result = JSONObject(bytes.toString("UTF-8"))
                socket.outputStream.write("OK\n".toByteArray())
                result
            } finally { socket.close() }
        } finally { server.close() }
    }
    private fun subtitlePixels(): Int {
        var count = 0
        activity.scenario.onActivity { host ->
            fun inspect(view: View) {
                if (view is SubtitleView && view.width > 0 && view.height > 0) {
                    val bitmap = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)
                    try {
                        val canvas = Canvas(bitmap); canvas.scale(640f / view.width, 360f / view.height); view.draw(canvas)
                        val pixels = IntArray(640 * 360); bitmap.getPixels(pixels, 0, 640, 0, 0, 640, 360)
                        count += pixels.count { Color.alpha(it) > 32 }
                    } finally { bitmap.recycle() }
                }
                if (view is ViewGroup) for (index in 0 until view.childCount) inspect(view.getChildAt(index))
            }
            inspect(host.viewport)
        }
        return count
    }
    @ExternalNetworkAcceptance
    @Test fun directNasMovieRendersEveryEmbeddedPgsTrack(): Unit = runBlocking {
        require(Build.DEVICE == "houji" && InstrumentationRegistry.getArguments().getString("nfbRealMedia") == "true")
        val config = configuration()
        val token = config.getString("token")
        val profile = ServerProfile(name = "Direct NAS media diagnostic", address = config.getString("baseUrl"))
        val session = NasSession.restore(profile, token, NasSession.parseIdentity(token).id)
        config.remove("token")
        var player: NativePlayer? = null
        val results = JSONArray()
        try {
            val path = config.getString("path"); val wire = config.getString("wirePath")
            val metadata = session.request("GET", "/api/resources$wire?metadata=1")
            check(!metadata.getBoolean("isDir") && metadata.getLong("size") == config.getLong("size")) { "Real source changed" }
            val lease = session.lease(path, wire)
            activity.scenario.onActivity { host ->
                val view = PlayerViewport(host)
                host.viewport.addView(view, FrameLayout.LayoutParams(-1, -1))
                player = NativePlayer(host).also { it.attach(view); it.open(lease) }
            }
            val native = requireNotNull(player)
            withTimeout(20_000) { native.state.first { it.firstFrameRendered && it.playing && it.seekable } }
            val tracks = native.state.value.subtitles.filter { it.id >= 0 }
            assertEquals(config.getInt("subtitleCount"), tracks.size)
            val at = config.optLong("positionMs", 69_000)
            main { native.pause(); native.seek(at) }
            withTimeout(20_000) { native.state.first { !it.playing && !it.waitingForBuffer && it.positionMs in (at - 500)..(at + 500) && it.phase != "正在跳转" } }
            // Let normal read-ahead fill; otherwise a tiny initial window can
            // conceal premature eviction of full-canvas PGS display sets.
            withTimeout(20_000) { native.state.first { it.bufferedPositionMs >= at + 15_000 } }
            for ((index, track) in tracks.withIndex()) {
                main { native.subtitle(track.id) }
                withTimeout(5000) { native.state.first { it.selectedSubtitle == track.id && it.pendingSubtitle == null } }
                var pixels = 0
                val visible = withTimeoutOrNull(5000) {
                    while (true) { pixels = subtitlePixels(); if (pixels > 20) break; delay(100) }
                    true
                } == true
                results.put(JSONObject().put("track", index).put("pixels", pixels).put("visible", visible)
                    .put("positionMs", native.state.value.positionMs).put("bufferedMs", native.state.value.bufferedPositionMs))
                if (index == 0 || index == tracks.lastIndex) UiDevice.getInstance(instrumentation)
                    .executeShellCommand("screencap -p /sdcard/Download/nfb-client-acceptance/real-nas-pgs-$index.png")
            }
            instrumentation.addResults(Bundle().apply { putString("filewayRealMedia", JSONObject()
                .put("width", native.state.value.width).put("height", native.state.value.height)
                .put("durationMs", native.state.value.durationMs).put("tracks", results).toString()) })
            assertTrue("Real PGS display results: $results", (0 until results.length()).all { results.getJSONObject(it).getBoolean("visible") })
            suspend fun rendered(visible: Boolean) = withTimeout(5000) {
                while ((subtitlePixels() > 20) != visible) delay(100)
            }
            main { native.subtitle(-1) }
            rendered(false)
            main { native.subtitle(tracks.first().id) }
            rendered(true)
            // Shift before the beginning, then restore while paused: no stale
            // display set may remain when the effective subtitle time is negative.
            main { native.subtitleDelay(at + 1000) }
            rendered(false)
            main { native.subtitleDelay(0) }
            rendered(true)
            for (target in listOf(at + 120_000, at)) {
                main { native.seek(target) }
                withTimeout(20_000) { native.state.first {
                    !it.playing && !it.waitingForBuffer && it.positionMs in (target - 500)..(target + 500) && it.phase != "正在跳转"
                } }
            }
            rendered(true)
            assertFalse("Subtitle/seek commands must retain the paused intent", native.state.value.playing)
            instrumentation.addResults(Bundle().apply { putString("filewayRealMediaTransitions", "off,on,delay,restore,forward,backward:PASS") })
        } finally { withContext(NonCancellable) { main { player?.release() }; session.close() } }
    }
}
