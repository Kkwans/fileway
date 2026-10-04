package io.github.kkwans.nasfilebrowser

import android.graphics.Bitmap
import android.graphics.Color
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import io.github.kkwans.nasfilebrowser.app.ClientModel
import io.github.kkwans.nasfilebrowser.app.FileLayout
import io.github.kkwans.nasfilebrowser.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/** Real JNI/HTTP and composed pixels; generated PNG belongs to the test fixture. */
@RunWith(AndroidJUnit4::class)
class NativePreviewTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    @Test fun actualPreviewLeaseReturnsImageAndRevokes(): Unit = runBlocking {
        val bytes = ownedPreviewPng()
        ClientSearchTest.Fixture(previewBody = bytes).use { source ->
            val session = NasSession.login(ServerProfile(name = "Preview fixture", address = source.url), "one", "fixture-only")
            try {
                val asset = session.preview("/A.mkv", "/%41.mkv")
                try {
                    withContext(Dispatchers.IO) {
                        val connection = URL(asset.url).openConnection() as HttpURLConnection
                        connection.connectTimeout = 5000; connection.readTimeout = 5000
                        try {
                            assertEquals(200, connection.responseCode)
                            assertTrue(connection.contentType.startsWith("image/png"))
                            assertArrayEquals(bytes, connection.inputStream.use { it.readBytes() })
                        } finally { connection.disconnect() }
                    }
                } finally { asset.release() }
                withContext(Dispatchers.IO) {
                    val connection = URL(asset.url).openConnection() as HttpURLConnection
                    connection.connectTimeout = 5000; connection.readTimeout = 5000
                    try { assertEquals(410, connection.responseCode) } finally { connection.disconnect() }
                }
            } finally { session.close() }
        }
    }
    @Test fun actualAuthenticatedPreviewReachesComposedCard(): Unit = runBlocking { previewCard(false) }
    @Test fun actualSearchPreviewReachesComposedCard(): Unit = runBlocking { previewCard(true) }
    @Test fun compactGridDisplaysBothAuthenticatedImageAndVideoThumbnails(): Unit = runBlocking { previewCard(false, FileLayout.COMPACT) }
    private suspend fun previewCard(search: Boolean, layout: FileLayout = FileLayout.COVER) {
        val compact = layout == FileLayout.COMPACT
        val names = if (compact) listOf("A.mkv", "照片.png", "旅行") else listOf("A.mkv")
        val source = ClientSearchTest.Fixture(if (search) emptyList() else names, ownedPreviewPng())
        val store = ProfileStore(ClientDatabase.get(instrumentation.targetContext), CredentialVault(instrumentation.targetContext))
        val profile = store.save(ServerProfile(name = "Preview fixture", address = source.url))
        lateinit var model: ClientModel
        activity.scenario.onActivity { model = ViewModelProvider(it)[ClientModel::class.java] }
        try {
            withContext(Dispatchers.Main) { model.selectProfile(profile); model.connectDraft(profile.name, source.url, BackendKind.NAS, "one", "fixture-only", "direct") }
            withTimeout(10_000) { model.state.first { it.connected && !it.busy } }
            if (search) {
                withContext(Dispatchers.Main) { model.openSearch(); model.search.query("A.mkv"); model.search.submit() }
                withTimeout(10_000) { model.search.state.first { it.ending == SearchEnding.COMPLETED } }
            } else {
                assertEquals(names.size, model.state.value.files.size)
                if (compact) {
                    withContext(Dispatchers.Main) { model.fileLayout(layout) }
                    withTimeout(5000) { model.state.first { it.fileLayout == layout } }
                    val device = UiDevice.getInstance(instrumentation)
                    assertTrue(device.wait(Until.hasObject(By.desc("紧凑文件网格")), 5000))
                }
            }
            assertTrue(withContext(Dispatchers.IO) { source.previewSeen.await(10, TimeUnit.SECONDS) })
            withTimeout(10_000) {
                while (true) {
                    val screenshot = instrumentation.uiAutomation.takeScreenshot() ?: error("Screenshot unavailable")
                    val bitmap = screenshot.copy(Bitmap.Config.ARGB_8888, false)
                    val matches = try {
                        val pixels = IntArray(bitmap.width * bitmap.height)
                        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                        fun cyan(pixel: Int) = Color.red(pixel) in 25..35 && Color.green(pixel) in 175..185 && Color.blue(pixel) in 215..225
                        if (compact) {
                            val device = UiDevice.getInstance(instrumentation)
                            listOf("A.mkv", "照片.png").minOf { name ->
                                val bounds = device.findObject(By.desc("$name 预览"))?.visibleBounds
                                if (bounds == null) 0 else (bounds.top until bounds.bottom).sumOf { y ->
                                    (bounds.left until bounds.right).count { x -> cyan(pixels[y * bitmap.width + x]) }
                                }
                            }
                        } else pixels.count(::cyan)
                    } finally { bitmap.recycle(); screenshot.recycle() }
                    if (matches > 400) break
                    delay(100)
                }
            }
            if (compact) {
                assertTrue(source.previewPaths.contains("/api/preview/thumb/A.mkv"))
                assertTrue(source.previewPaths.contains("/api/preview/thumb/照片.png"))
                assertTrue(source.previewPaths.none { it.endsWith("/旅行") })
                val device = UiDevice.getInstance(instrumentation)
                device.executeShellCommand("mkdir -p /sdcard/Download/nfb-client-acceptance")
                device.executeShellCommand("screencap -p /sdcard/Download/nfb-client-acceptance/compact-image-video-thumbnails.png")
            }
        } finally { withContext(Dispatchers.Main) { model.disconnect() }; store.remove(profile); source.close() }
    }
}

internal fun ownedPreviewPng(): ByteArray {
    val bitmap = Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888)
    try {
        bitmap.eraseColor(Color.rgb(30, 180, 220))
        return ByteArrayOutputStream().also { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }.toByteArray()
    } finally { bitmap.recycle() }
}
