package io.github.kkwans.nasfilebrowser

import android.graphics.Bitmap
import android.graphics.Color
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import io.github.kkwans.nasfilebrowser.app.ClientModel
import io.github.kkwans.nasfilebrowser.data.ClientDatabase
import io.github.kkwans.nasfilebrowser.download.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlin.math.abs

/** Own MediaStore files only, with nonexistent source/profile; no NAS or credentials. */
@RunWith(AndroidJUnit4::class)
class DownloadImageTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)
    @Test fun completedPngAndJpegRenderAndPageOffline(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext; val device = UiDevice.getInstance(instrumentation)
        val dao = ClientDatabase.get(context).downloads(); val target = DownloadTarget(context)
        val owned = mutableListOf<DownloadRecord>()
        lateinit var model: ClientModel
        activity.scenario.onActivity { model = ViewModelProvider(it)[ClientModel::class.java] }
        suspend fun main(action: () -> Unit) = withContext(Dispatchers.Main) { action() }
        suspend fun waitFor(stage: String, block: suspend () -> Unit) {
            try { withTimeout(5000) { block() } }
            catch (failure: TimeoutCancellationException) {
                device.executeShellCommand("mkdir -p /sdcard/Download/nfb-client-acceptance")
                device.executeShellCommand("screencap -p /sdcard/Download/nfb-client-acceptance/download-image-failure.png")
                device.dumpWindowHierarchy(java.io.File(context.getExternalFilesDir(null), "download-image-failure.xml"))
                throw AssertionError("$stage: image=${model.state.value.image?.downloadId}, busy=${model.state.value.busy}, clientError=${model.state.value.error}, downloadError=${model.downloads.state.value.error}", failure)
            }
        }
        suspend fun pixels(color: Int) {
            withTimeout(10_000) {
                while (true) {
                    val screen = instrumentation.uiAutomation.takeScreenshot()
                    if (screen != null) {
                        val sample = screen.getPixel(screen.width / 2, screen.height / 2)
                        screen.recycle()
                        if (abs(Color.red(sample) - Color.red(color)) < 15 && abs(Color.green(sample) - Color.green(color)) < 15 && abs(Color.blue(sample) - Color.blue(color)) < 15) break
                    }
                    delay(100)
                }
            }
        }
        try {
            val now = System.currentTimeMillis()
            for ((index, entry) in listOf(Triple("png", Bitmap.CompressFormat.PNG, Color.rgb(41, 165, 212)), Triple("jpg", Bitmap.CompressFormat.JPEG, Color.rgb(212, 41, 155))).withIndex()) {
                val bitmap = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888).apply { eraseColor(entry.third) }
                val output = ByteArrayOutputStream()
                bitmap.compress(entry.second, 95, output); bitmap.recycle()
                val bytes = output.toByteArray(); val id = UUID.randomUUID().toString()
                val name = "fileway-owned-offline-$id.${entry.first}"
                val item = DownloadRecord(id, dao.lastJobId() + 1, "owned-offline-image", "removed-owned-source", 0,
                    "/$name", "/$name", name, "image", bytes.size.toLong(), "owned", "${bytes.size}/owned", "Owned offline image", "",
                    status = "completed", downloaded = bytes.size.toLong(), createdAt = now - index, updatedAt = now)
                val uri = target.allocate(item)
                val saved = item.copy(localUri = uri.toString()); owned.add(saved)
                context.contentResolver.openOutputStream(uri, "w")!!.use { it.write(bytes) }
                target.complete(saved); dao.insert(saved)
            }
            main { model.disconnect(); model.openDownloads() }
            assertFalse(model.state.value.connected)
            waitFor("download rows") { model.downloads.state.first { state -> owned.all { item -> state.items.any { it.id == item.id } } } }
            assertTrue(device.wait(Until.hasObject(By.text("本机下载")), 5000))
            assertTrue(device.wait(Until.hasObject(By.desc(owned.first().name + " 预览")), 5000))
            val open = device.wait(Until.findObject(By.desc("打开下载：${owned.first().name}")), 5000) ?: error("Owned PNG open action missing")
            assertTrue(open.isClickable); open.click()
            waitFor("open PNG") { model.state.first { it.image?.downloadId == owned.first().id } }
            pixels(Color.rgb(41, 165, 212))
            device.executeShellCommand("mkdir -p /sdcard/Download/nfb-client-acceptance")
            device.executeShellCommand("screencap -p /sdcard/Download/nfb-client-acceptance/download-offline-image-phone.png")
            val next = device.wait(Until.findObject(By.desc("下一张图片")), 5000) ?: error("Image next action missing")
            next.click()
            waitFor("page JPEG") { model.state.first { it.image?.downloadId == owned.last().id } }
            pixels(Color.rgb(212, 41, 155))
            assertFalse(model.state.value.connected)
        } finally {
            withContext(NonCancellable) {
                main { model.closeImage() }
                owned.forEach { target.delete(it); dao.removeRecord(it.id) }
            }
        }
    }
}
