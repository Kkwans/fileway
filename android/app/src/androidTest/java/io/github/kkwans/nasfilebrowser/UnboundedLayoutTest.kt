package io.github.kkwans.nasfilebrowser

import android.graphics.Bitmap
import android.graphics.Color
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import io.github.kkwans.nasfilebrowser.app.ClientModel
import io.github.kkwans.nasfilebrowser.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
class UnboundedLayoutTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    @Test fun portraitPreviewAndFullFilenameHaveNoFixedHeightOrLineClamp(): Unit = runBlocking { verify(50, 160, "portrait") }
    @Test fun widePreviewAndFullFilenameHaveNoFixedHeightOrLineClamp(): Unit = runBlocking { verify(160, 50, "wide") }

    private suspend fun verify(width: Int, height: Int, variant: String) {
        val name = "无界网格文件名必须完整展示不省略".repeat(5) + "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789.png"
        val image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val bytes = try {
            image.eraseColor(Color.rgb(30, 180, 220))
            ByteArrayOutputStream().also { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }.toByteArray()
        } finally { image.recycle() }
        val names = if (variant == "wide") listOf("参照.png", name, "短名.png", "第四.png") else listOf("参照.png", name)
        val source = ClientSearchTest.Fixture(names, bytes)
        val store = ProfileStore(ClientDatabase.get(instrumentation.targetContext), CredentialVault(instrumentation.targetContext))
        val profile = store.save(ServerProfile(name = "Unbounded fixture", address = source.url))
        lateinit var model: ClientModel
        activity.scenario.onActivity { model = ViewModelProvider(it)[ClientModel::class.java] }
        try {
            withContext(Dispatchers.Main) { model.selectProfile(profile); model.connectDraft(profile.name, source.url, BackendKind.NAS, "one", "fixture-only", "direct") }
            withTimeout(10_000) { model.state.first { it.connected && !it.busy && it.files.size == names.size } }
            withContext(Dispatchers.Main) { model.fileLayout(FileLayout.UNBOUNDED) }
            withTimeout(5000) { model.state.first { it.fileLayout == FileLayout.UNBOUNDED } }
            assertTrue(device.wait(Until.hasObject(By.desc("无界文件网格")), 5000))
            withTimeout(10_000) {
                while (true) {
                    val frame = instrumentation.uiAutomation.takeScreenshot() ?: error("Screenshot missing")
                    val bitmap = frame.copy(Bitmap.Config.ARGB_8888, false)
                    val ready = try {
                        val pixels = IntArray(bitmap.width * bitmap.height)
                        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                        names.all { imageName ->
                        val bounds = device.findObject(By.desc("$imageName 预览"))?.visibleBounds
                        if (bounds == null) false else {
                            var minX = bitmap.width; var maxX = -1; var minY = bitmap.height; var maxY = -1; var count = 0
                            for (y in bounds.top until bounds.bottom) for (x in bounds.left until bounds.right) {
                                val pixel = pixels[y * bitmap.width + x]
                                if (Color.red(pixel) in 25..35 && Color.green(pixel) in 175..185 && Color.blue(pixel) in 215..225) {
                                    count++; minX = minOf(minX, x); maxX = maxOf(maxX, x); minY = minOf(minY, y); maxY = maxOf(maxY, y)
                                }
                            }
                            if (count < 400) false else {
                                val renderedWidth = maxX - minX + 1; val renderedHeight = maxY - minY + 1
                                val expectedHeight = renderedWidth.toFloat() * height / width
                                kotlin.math.abs(expectedHeight - renderedHeight) <= 3f && kotlin.math.abs(renderedWidth - bounds.width()) <= 3
                            }
                        }
                        }
                    } finally { bitmap.recycle(); frame.recycle() }
                    if (ready) break
                    delay(100)
                }
            }
            val area = device.findObject(By.desc("无界文件网格")) ?: error("Waterfall missing")
            var title = device.wait(Until.findObject(By.clazz("android.widget.TextView").text(name)), 5000) ?: error("Full name missing")
            if (title.visibleBounds.bottom >= area.visibleBounds.bottom - 3) {
                area.scroll(Direction.DOWN, .5f); instrumentation.waitForIdleSync()
                title = device.findObject(By.clazz("android.widget.TextView").text(name)) ?: error("Scrolled full name missing")
            }
            val short = device.wait(Until.findObject(By.clazz("android.widget.TextView").text("参照.png")), 5000) ?: error("Reference title missing")
            assertTrue("Full filename must visibly wrap beyond two lines", title.visibleBounds.height() > short.visibleBounds.height() * 3)
            if (variant == "wide") {
                val third = device.wait(Until.findObject(By.clazz("android.widget.TextView").text("短名.png")), 5000) ?: error("Third item missing")
                val fourth = device.wait(Until.findObject(By.clazz("android.widget.TextView").text("第四.png")), 5000) ?: error("Fourth item missing")
                assertEquals("Short cards should continue in the shorter column", short.visibleBounds.left, third.visibleBounds.left)
                assertEquals("Waterfall must not wait for a tall neighboring filename", short.visibleBounds.left, fourth.visibleBounds.left)
                assertTrue(fourth.visibleBounds.top < title.visibleBounds.bottom)
            }
            device.executeShellCommand("mkdir -p /sdcard/Download/nfb-client-acceptance")
            device.executeShellCommand("screencap -p /sdcard/Download/nfb-client-acceptance/unbounded-full-$variant.png")
            title.longClick()
            assertTrue(device.wait(Until.hasObject(By.text("文件详情")), 5000))
        } catch (error: Throwable) {
            device.executeShellCommand("mkdir -p /sdcard/Download/nfb-client-acceptance")
            device.executeShellCommand("screencap -p /sdcard/Download/nfb-client-acceptance/unbounded-failure-$variant.png")
            throw error
        } finally { withContext(Dispatchers.Main) { model.disconnect() }; store.remove(profile); source.close() }
    }
}
