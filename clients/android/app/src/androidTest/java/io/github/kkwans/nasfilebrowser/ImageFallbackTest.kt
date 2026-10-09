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
import io.github.kkwans.nasfilebrowser.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** Own loopback media only. Changes quality, never cache sizes or personal files. */
@RunWith(AndroidJUnit4::class)
class ImageFallbackTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device get() = UiDevice.getInstance(instrumentation)
    private suspend fun main(action: () -> Unit) = withContext(Dispatchers.Main) { action() }

    // Same owned 16x16 red/blue, 300/450 ms looping GIF as the original-viewer regression.
    private fun gif() = android.util.Base64.decode("R0lGODlhEAAQAIEAAOYoPAAAAAAAAAAAACH/C05FVFNDQVBFMi4wAwEAAAAh+QQAHgAAACwAAAAAEAAQAAAIHQABCBxIsKDBgwgTKlzIsKHDhxAjSpxIsaLFgQEBACH5BAAtAAAALAAAAAAQABAAgR5a5gAAAAAAAAAAAAgdAAEIHEiwoMGDCBMqXMiwocOHECNKnEixosWBAQEAOw==", android.util.Base64.DEFAULT)
    private fun preview(): ByteArray {
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.eraseColor(Color.rgb(30, 180, 220))
            ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        } finally { bitmap.recycle() }
    }

    private suspend fun pixels(red: Int, green: Int, blue: Int) = withTimeout(15_000) {
        while (true) {
            val shot = instrumentation.uiAutomation.takeScreenshot() ?: error("Screenshot unavailable")
            val bitmap = shot.copy(Bitmap.Config.ARGB_8888, false)
            val matches = try {
                var hit = 0; var count = 0
                for (y in bitmap.height * 2 / 5 until bitmap.height * 3 / 5 step 16) {
                    for (x in bitmap.width / 4 until bitmap.width * 3 / 4 step 16) {
                        val pixel = bitmap.getPixel(x, y); count++
                        if (abs(Color.red(pixel) - red) < 10 && abs(Color.green(pixel) - green) < 10 && abs(Color.blue(pixel) - blue) < 10) hit++
                    }
                }
                hit > count * .7
            } finally { bitmap.recycle(); shot.recycle() }
            if (matches) break
            delay(100)
        }
    }

    private fun click(text: String) {
        val action = device.wait(Until.findObject(By.text(text)), 5000) ?: error("Missing image action: $text")
        action.click()
    }

    private suspend fun withLowFixture(source: ClientSearchTest.Fixture, test: suspend (ClientModel) -> Unit) {
        val database = ClientDatabase.get(instrumentation.targetContext)
        val previousSession = database.profiles().activeSession()
        val store = ProfileStore(database, CredentialVault(instrumentation.targetContext))
        val profile = store.save(ServerProfile(name = "Image fallback fixture", address = source.url))
        lateinit var model: ClientModel
        activity.scenario.onActivity { model = ViewModelProvider(it)[ClientModel::class.java] }
        model.cache.awaitReady(); val previousSettings = model.cache.state.value.settings
        try {
            main { model.selectProfile(profile); model.connectDraft(profile.name, source.url, BackendKind.NAS, "one", "fixture-only", "direct") }
            withTimeout(10_000) { model.state.first { it.connected && !it.busy } }
            main { model.cache.save(previousSettings.copy(imageQuality = ImageQuality.LOW)) }
            withTimeout(5000) { model.cache.state.first { !it.busy && it.settings.imageQuality == ImageQuality.LOW } }
            test(model)
        } catch (error: Throwable) {
            val evidence = instrumentation.targetContext.getExternalFilesDir(null)!!
            runCatching { device.takeScreenshot(java.io.File(evidence, "image-fallback-failure.png")) }
            runCatching { device.dumpWindowHierarchy(java.io.File(evidence, "image-fallback-failure.xml")) }
            instrumentation.sendStatus(2, android.os.Bundle().apply {
                putString("stream", "IMAGE_FALLBACK_FAILURE metadata=${source.imageMetadataRequests.get()} " +
                    "raw=${source.rawImages.sorted()} previewFailures=${source.previewFailures.get()} " +
                    "heldReplies=${source.heldImageMetadataFinished.get()} selected=${model.state.value.image?.name}\n")
            })
            throw error
        } finally {
            withContext(NonCancellable) {
                source.releaseMetadata.countDown()
                try {
                    main { model.closeImage(); model.cache.save(previousSettings) }
                    withTimeout(5000) { model.cache.state.first { !it.busy && it.settings == previousSettings } }
                } finally {
                    try { main { model.disconnect() }; store.remove(profile) }
                    finally {
                        try { source.close() } finally {
                            previousSession?.let { if (database.profiles().account(it.accountKey) != null) database.profiles().saveActiveSession(it) }
                        }
                    }
                }
            }
        }
    }

    @Test fun unsupportedLowPreviewCanExplicitlyOpenOriginalGifWithoutChangingPreference(): Unit = runBlocking {
        val source = ClientSearchTest.Fixture(listOf("animated.gif"), imageBodies = mapOf("animated.gif" to gif()),
            previewStatusCodes = mapOf("animated.gif" to 501))
        withLowFixture(source) { model ->
            main { model.open(model.state.value.files.single()) }
            assertTrue(device.wait(Until.hasObject(By.textContains("图片读取失败")), 5000))
            assertTrue(source.previewFailures.get() > 0)
            assertTrue("No automatic original fetch after a 501", source.rawImages.isEmpty())
            assertTrue(device.hasObject(By.text("重试")))
            click("查看原图")
            pixels(230, 40, 60); pixels(30, 90, 230); pixels(230, 40, 60)
            assertEquals(setOf("animated.gif"), source.rawImages.toSet())
            assertEquals(ImageQuality.LOW, model.cache.state.value.settings.imageQuality)
            assertTrue(device.wait(Until.gone(By.text("查看原图")), 5000))
        }
    }

    @Test fun canceledLowReadCanTryOriginalAndLateReplyCannotReplaceNextPage(): Unit = runBlocking {
        val source = ClientSearchTest.Fixture(listOf("00-held.gif", "01-animated.gif"), preview(),
            imageBodies = mapOf("00-held.gif" to gif(), "01-animated.gif" to gif()),
            previewStatusCodes = mapOf("01-animated.gif" to 501))
        source.heldImageMetadata = "00-held.gif"
        withLowFixture(source) { model ->
            main { model.open(model.state.value.files.single { it.name == "00-held.gif" }) }
            assertTrue(withContext(Dispatchers.IO) { source.metadataStarted.await(5, TimeUnit.SECONDS) })
            pixels(30, 180, 220)
            click("取消读取")
            assertTrue(device.wait(Until.hasObject(By.text("已保留预览图")), 5000))
            pixels(30, 180, 220)
            click("查看原图")
            withTimeout(5000) { while (source.imageMetadataRequests.get() < 2) delay(25) }
            val next = device.wait(Until.findObject(By.desc("下一张图片")), 5000) ?: error("Next image action missing")
            next.click()
            withTimeout(5000) { model.state.first { it.image?.name == "01-animated.gif" } }
            assertTrue(device.wait(Until.hasObject(By.textContains("图片读取失败")), 5000))
            click("查看原图")
            pixels(230, 40, 60)
            assertEquals("Superseded reads must remain held until the new page renders", 0, source.heldImageMetadataFinished.get())
            source.releaseMetadata.countDown()
            withTimeout(5000) { while (source.heldImageMetadataFinished.get() < 2) delay(25) }
            withContext(Dispatchers.IO) { instrumentation.waitForIdleSync() }
            assertEquals("01-animated.gif", model.state.value.image?.name)
            assertFalse(device.hasObject(By.text("重试")))
            pixels(30, 90, 230); pixels(230, 40, 60)
            assertEquals(ImageQuality.LOW, model.cache.state.value.settings.imageQuality)
        }
    }

    @Test fun deniedOriginalRetainsPermissionErrorAndRetry(): Unit = runBlocking {
        val statuses = java.util.concurrent.ConcurrentHashMap<String, Int>().apply { put("denied.gif", 403) }
        val source = ClientSearchTest.Fixture(listOf("denied.gif"), imageBodies = mapOf("denied.gif" to gif()),
            previewStatusCodes = mapOf("denied.gif" to 501), rawStatusCodes = statuses)
        withLowFixture(source) { model ->
            main { model.open(model.state.value.files.single()) }
            assertTrue(device.wait(Until.hasObject(By.textContains("图片读取失败")), 5000))
            click("查看原图")
            assertTrue(device.wait(Until.hasObject(By.textContains("没有读取这张图片的权限")), 5000))
            assertTrue(device.hasObject(By.text("重试")))
            assertFalse(device.hasObject(By.text("查看原图")))
            assertEquals(setOf("denied.gif"), source.rawImages.toSet())
            assertEquals(ImageQuality.LOW, model.cache.state.value.settings.imageQuality)
            statuses["denied.gif"] = 200
            click("重试")
            pixels(230, 40, 60); pixels(30, 90, 230); pixels(230, 40, 60)
            assertTrue("Retry must disappear after the original renders", device.wait(Until.gone(By.text("重试")), 5000))
            assertEquals(ImageQuality.LOW, model.cache.state.value.settings.imageQuality)
        }
    }
}
