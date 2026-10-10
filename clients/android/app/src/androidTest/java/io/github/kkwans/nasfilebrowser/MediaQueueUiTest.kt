package io.github.kkwans.nasfilebrowser

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import io.github.kkwans.nasfilebrowser.app.ClientModel
import io.github.kkwans.nasfilebrowser.app.FileLayout
import io.github.kkwans.nasfilebrowser.data.*
import io.github.kkwans.nasfilebrowser.player.PlaybackTraceAction
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** Owned-emulator fixtures: real authenticated image bytes, gallery pixels and queue controls.
 * The cache-off case changes disposable cache state; do not run it on a personal installation. */
@RunWith(AndroidJUnit4::class)
class MediaQueueUiTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device get() = UiDevice.getInstance(instrumentation)
    private suspend fun main(action: () -> Unit) = withContext(Dispatchers.Main) { action() }
    private fun model(): ClientModel {
        lateinit var result: ClientModel
        activity.scenario.onActivity { result = ViewModelProvider(it)[ClientModel::class.java] }
        return result
    }
    private fun store() = ProfileStore(ClientDatabase.get(instrumentation.targetContext), CredentialVault(instrumentation.targetContext))
    private fun original(): ByteArray {
        val bitmap = Bitmap.createBitmap(1600, 1200, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.eraseColor(Color.rgb(25, 170, 80))
            ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        } finally { bitmap.recycle() }
    }
    private suspend fun renderedColor(red: Int, green: Int, blue: Int) = withTimeout(15_000) {
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
    private fun capture(name: String) {
        device.executeShellCommand("mkdir -p /sdcard/Download/nfb-client-acceptance")
        device.executeShellCommand("screencap -p /sdcard/Download/nfb-client-acceptance/$name.png")
        // Use this instrumentation's UiAutomation connection; a concurrent shell
        // uiautomator dump would steal the connection and invalidate the test.
        device.dumpWindowHierarchy(File(instrumentation.targetContext.getExternalFilesDir(null), "$name.xml"))
    }

    private suspend fun awaitLightBars(light: Boolean) = withTimeout(5000) {
        while (true) {
            var matches = false
            activity.scenario.onActivity {
                val bars = WindowCompat.getInsetsController(it.window, it.window.decorView)
                matches = bars.isAppearanceLightStatusBars == light && bars.isAppearanceLightNavigationBars == light
            }
            if (matches) break
            delay(50)
        }
    }

    private suspend fun lightIconsOnDarkStatusBackground() = withTimeout(5000) {
        while (true) {
            var height = 0
            activity.scenario.onActivity {
                height = ViewCompat.getRootWindowInsets(it.window.decorView)?.getInsets(WindowInsetsCompat.Type.statusBars())?.top ?: 0
            }
            if (height > 0) {
                val shot = instrumentation.uiAutomation.takeScreenshot() ?: error("Screenshot unavailable")
                val bitmap = shot.copy(Bitmap.Config.ARGB_8888, false)
                val visible = try {
                    var bright = 0; var dark = 0; var total = 0
                    for (y in 0 until height.coerceAtMost(bitmap.height) step 2) for (x in 0 until bitmap.width step 2) {
                        val pixel = bitmap.getPixel(x, y); total++
                        if (minOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel)) > 180) bright++
                        if (maxOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel)) < 60) dark++
                    }
                    bright >= 32 && dark > total * .7
                } finally { bitmap.recycle(); shot.recycle() }
                if (visible) break
            }
            delay(100)
        }
    }

    @Test fun galleryPreservesPreviewCancelsOriginalPagesAndRestoresListPosition(): Unit = runBlocking {
        val names = (1..24).map { "%02d.png".format(it) }
        val bytes = original()
        val source = ClientSearchTest.Fixture(names + "movie.mkv", ownedPreviewPng(), imageBodies = names.associateWith { bytes })
        source.heldImage = "12.png"
        val model = model(); val store = store()
        val profile = store.save(ServerProfile(name = "Gallery fixture", address = source.url))
        model.cache.awaitReady(); val originalSettings = model.cache.state.value.settings
        try {
            main { model.selectProfile(profile); model.connectDraft(profile.name, source.url, BackendKind.NAS, "one", "fixture-only", "direct") }
            withTimeout(10_000) { model.state.first { it.connected && !it.busy } }
            main { model.fileLayout(FileLayout.LIST); model.cache.save(originalSettings.copy(imageQuality = ImageQuality.ORIGINAL)) }
            withTimeout(5000) { model.state.first { it.fileLayout == FileLayout.LIST }; model.cache.state.first { !it.busy && it.settings.imageQuality == ImageQuality.ORIGINAL } }
            repeat(8) {
                val area = device.findObject(By.desc("文件列表"))
                val target = device.findObject(By.text("12.png"))
                if (target == null || area == null || target.visibleBounds.bottom >= area.visibleBounds.bottom - 8) area?.scroll(Direction.DOWN, .5f)
            }
            val item = device.wait(Until.findObject(By.text("12.png")), 5000) ?: error("Gallery entry missing")
            val beforeTop = item.visibleBounds.top
            item.click()
            withTimeout(5000) { model.state.first { it.image?.name == "12.png" } }
            assertEquals(24, model.state.value.mediaQueue!!.items.size)
            assertTrue(withContext(Dispatchers.IO) { source.imageStarted.await(10, TimeUnit.SECONDS) })
            renderedColor(30, 180, 220)
            assertEquals(setOf("12.png"), source.rawImages.toSet())
            capture("gallery-preview-loading")
            device.findObject(By.text("取消读取")).click()
            assertTrue(device.wait(Until.hasObject(By.text("已保留预览图")), 5000))
            assertNotNull(model.state.value.image)
            source.releaseImage.countDown()
            renderedColor(30, 180, 220)
            device.findObject(By.desc("下一张图片")).click()
            withTimeout(5000) { model.state.first { it.image?.name == "13.png" } }
            renderedColor(25, 170, 80)
            assertEquals(setOf("12.png", "13.png"), source.rawImages.toSet())
            capture("gallery-original")
            val x = device.displayWidth / 2; val y = device.displayHeight / 2
            device.click(x, y); delay(80); device.click(x, y); delay(500)
            device.swipe(device.displayWidth * 4 / 5, y, device.displayWidth / 5, y, 20)
            delay(300)
            assertEquals("Zoomed panning must not turn the page", "13.png", model.state.value.image?.name)
            device.pressBack()
            assertTrue(device.wait(Until.hasObject(By.desc("文件列表")), 5000))
            val returned = device.wait(Until.findObject(By.text("12.png")), 5000) ?: error("List position was lost")
            assertTrue("Returning from media must preserve the list offset", abs(returned.visibleBounds.top - beforeTop) <= 4)
        } finally {
            source.releaseImage.countDown()
            main { model.closeImage(); model.cache.save(originalSettings) }
            withTimeout(5000) { model.cache.state.first { !it.busy && it.settings == originalSettings } }
            main { model.disconnect() }; store.remove(profile); source.close()
        }
    }

    @Test fun originalGifAdvancesFramesInTheActualViewer(): Unit = runBlocking {
        // Owned 16x16 solid red/blue frames, 300/450 ms, looping. No external media.
        val gif = android.util.Base64.decode("R0lGODlhEAAQAIEAAOYoPAAAAAAAAAAAACH/C05FVFNDQVBFMi4wAwEAAAAh+QQAHgAAACwAAAAAEAAQAAAIHQABCBxIsKDBgwgTKlzIsKHDhxAjSpxIsaLFgQEBACH5BAAtAAAALAAAAAAQABAAgR5a5gAAAAAAAAAAAAgdAAEIHEiwoMGDCBMqXMiwocOHECNKnEixosWBAQEAOw==", android.util.Base64.DEFAULT)
        val source = ClientSearchTest.Fixture(listOf("animated.gif"), ownedPreviewPng(), imageBodies = mapOf("animated.gif" to gif))
        val database = ClientDatabase.get(instrumentation.targetContext)
        val previousSession = database.profiles().activeSession()
        val model = model(); val store = store()
        val profile = store.save(ServerProfile(name = "Animated image fixture", address = source.url))
        model.cache.awaitReady(); val original = model.cache.state.value.settings
        try {
            main { model.selectProfile(profile); model.connectDraft(profile.name, source.url, BackendKind.NAS, "one", "fixture-only", "direct") }
            withTimeout(10_000) { model.state.first { it.connected && !it.busy } }
            main { model.cache.save(original.copy(imageQuality = ImageQuality.ORIGINAL)) }
            withTimeout(5000) { model.cache.state.first { !it.busy && it.settings.imageQuality == ImageQuality.ORIGINAL } }
            main { model.open(model.state.value.files.single()) }
            renderedColor(230, 40, 60)
            renderedColor(30, 90, 230)
            renderedColor(230, 40, 60)
            assertEquals(setOf("animated.gif"), source.rawImages.toSet())
            capture("gallery-animated-gif")
        } finally {
            withContext(NonCancellable) {
                try {
                    main { model.closeImage(); model.cache.save(original) }
                    withTimeout(5000) { model.cache.state.first { !it.busy && it.settings == original } }
                } finally {
                    try { main { model.disconnect() }; store.remove(profile) } finally {
                        try { source.close() } finally {
                            previousSession?.let { if (database.profiles().account(it.accountKey) != null) database.profiles().saveActiveSession(it) }
                        }
                    }
                }
            }
        }
    }

    @Test fun cacheDisabledOriginalUsesOwnedWorkingFileAndRemovesItOnExit(): Unit = runBlocking {
        val source = ClientSearchTest.Fixture(listOf("photo.png"), ownedPreviewPng(), imageBodies = mapOf("photo.png" to original()))
        val model = model(); val store = store(); val profile = store.save(ServerProfile(name = "Temporary image fixture", address = source.url))
        model.cache.awaitReady(); val old = model.cache.state.value.settings
        val oldTheme = withTimeout(5000) { model.appearance.state.first { it.loaded } }.theme
        val work = File(instrumentation.targetContext.cacheDir, "image-viewer-work")
        try {
            main { model.appearance.save(AppTheme.LIGHT) }
            withTimeout(5000) { model.appearance.state.first { it.theme == AppTheme.LIGHT && !it.saving } }
            main { model.selectProfile(profile); model.connectDraft(profile.name, source.url, BackendKind.NAS, "one", "fixture-only", "direct") }
            withTimeout(10_000) { model.state.first { it.connected && !it.busy } }
            main { model.cache.save(old.copy(imageMB = 0, imageQuality = ImageQuality.ORIGINAL)) }
            withTimeout(5000) { model.cache.state.first { !it.busy && it.settings.imageMB == 0L && it.settings.imageQuality == ImageQuality.ORIGINAL } }
            awaitLightBars(true)
            main { model.open(model.state.value.files.single()) }
            renderedColor(25, 170, 80)
            awaitLightBars(false)
            lightIconsOnDarkStatusBackground()
            capture("gallery-system-bars-dark")
            assertTrue(work.listFiles().orEmpty().any { it.isFile && it.length() > 0 })
            assertTrue(model.cache.cachedImages(model.cacheAccount()).isEmpty())
            main { model.closeImage() }
            awaitLightBars(true)
            withTimeout(5000) { while (work.listFiles().orEmpty().any { it.name.startsWith("viewer-") }) delay(50) }
        } finally {
            main { model.closeImage(); model.cache.save(old); model.appearance.save(oldTheme) }
            withTimeout(5000) { model.cache.state.first { !it.busy && it.settings == old } }
            withTimeout(5000) { model.appearance.state.first { it.theme == oldTheme && !it.saving } }
            main { model.disconnect() }; store.remove(profile); source.close()
        }
    }

    /** Validates paused queue binding and late metadata isolation, not decoded output. */
    @Test fun videoQueueControlsKeepSnapshotAndRejectSupersededOpen(): Unit = runBlocking {
        val media = instrumentation.context.assets.open("media/fixture.mkv").use { it.readBytes() }
        val source = NativePlaybackTest.Fixture(media, videos = listOf("one.mkv", "two.mkv"))
        val database = ClientDatabase.get(instrumentation.targetContext)
        val previousSession = database.profiles().activeSession()
        val model = model(); val store = store(); val profile = store.save(ServerProfile(name = "Video queue fixture", address = source.url))
        fun freshNodes() = freshAccessibilityBounds(instrumentation, Rect(0, 0, device.displayWidth, device.displayHeight))
        suspend fun findFresh(label: String, enabled: Boolean = true, checked: Boolean? = null, hud: Boolean = false): FreshAccessibilityNodeBounds = withTimeout(5_000) {
            while (true) {
                freshNodes()?.singleOrNull {
                    it.visible && it.description == label && it.enabled == enabled &&
                        (!hud || "播放详情" !in it.ancestorDescriptions) &&
                        (checked == null || (it.checkable && it.checked == checked))
                }?.let { return@withTimeout it }
                delay(25)
            }
            @Suppress("UNREACHABLE_CODE") error("Queue control is not reachable: $label")
        }
        fun tap(node: FreshAccessibilityNodeBounds) {
            assertTrue("Queue control must be enabled: ${node.description}", node.enabled)
            // Compose omits AX ACTION_CLICK for the selected RadioButton;
            // its enabled pointer target still closes the current-item panel.
            val selectedRadio = node.checkable && node.checked && node.className == "android.widget.RadioButton"
            assertTrue("Queue control must expose a click or the selected radio state: ${node.description}",
                node.clickable || selectedRadio)
            assertFalse("Queue control must have visible bounds: ${node.description}", node.bounds.isEmpty)
            OwnedTouchInput.tap(instrumentation, node.bounds, tracePrefix = "queue-panel")
        }
        // Portrait also has a details shortcut; exercise the unique HUD button.
        suspend fun openQueue() { tap(findFresh("播放列表", hud = true)) }
        suspend fun chooseQueue(index: Int, name: String) {
            tap(findFresh("第 ${index + 1} 项，$name"))
            withTimeout(5_000) {
                while (freshNodes()?.none { it.visible && it.description == "播放设置" } != true) delay(25)
            }
        }
        try {
            main { model.selectProfile(profile); model.connectDraft(profile.name, source.url, BackendKind.NAS, "fixture", "fixture-only", "direct") }
            withTimeout(10_000) { model.state.first { it.connected && !it.busy } }
            main { model.foreground(false); model.open(model.state.value.files.first()) }
            withTimeout(10_000) { model.state.first { it.selected?.name == "one.mkv" && !it.busy } }
            val snapshot = model.state.value.mediaQueue!!.snapshotId
            val orderedPaths = model.state.value.mediaQueue!!.items.map { it.wirePath }
            assertFalse(model.player.state.value.playing)
            assertEquals(0, model.state.value.mediaQueue!!.index)
            val next = findFresh("下一个视频")
            assertTrue("Next remains a real enabled button", next.clickable)
            val controls = checkNotNull(freshNodes()) { "Queue HUD accessibility sample is incomplete" }
            assertFalse("The main controls no longer expose a previous-item button", controls.any { it.visible && it.description == "上一个视频" })
            assertFalse(controls.any { it.visible && it.description == "上一集" })
            val removedSeekAction = Regex("""^(后退|快退|快进)\s*(10|十)\s*秒$""")
            assertFalse("Explicit +/-10 second buttons must not return",
                controls.any { it.visible && it.clickable && it.description?.let(removedSeekAction::matches) == true })
            capture("video-queue-first")
            val generation = model.player.state.value.mediaGeneration
            val opens = model.player.diagnosticSnapshot().count { it.action == PlaybackTraceAction.OPEN_REQUEST }
            val mediaSets = model.player.diagnosticSnapshot().count { it.action == PlaybackTraceAction.MEDIA_SET }
            openQueue()
            val currentRow = findFresh("第 1 项，one.mkv", checked = true)
            assertTrue("Current item must expose its selected state", currentRow.checkable && currentRow.checked)
            chooseQueue(0, "one.mkv")
            assertEquals("Selecting the current item must not reopen media", opens,
                model.player.diagnosticSnapshot().count { it.action == PlaybackTraceAction.OPEN_REQUEST })
            assertEquals(generation, model.player.state.value.mediaGeneration)
            assertEquals("Selecting the current item must not set native media again", mediaSets,
                model.player.diagnosticSnapshot().count { it.action == PlaybackTraceAction.MEDIA_SET })
            assertEquals(snapshot, model.state.value.mediaQueue!!.snapshotId)
            assertEquals(orderedPaths, model.state.value.mediaQueue!!.items.map { it.wirePath })
            source.stallNextRead.set(true)
            tap(findFresh("下一个视频"))
            assertTrue(withContext(Dispatchers.IO) { source.resumeRead.await(5, TimeUnit.SECONDS) })
            main { model.cancel() }
            assertFalse(model.state.value.busy)
            assertEquals("Canceling a queue transition must keep the current queue item", "two.mkv", model.state.value.selected?.name)
            assertEquals(snapshot, model.state.value.mediaQueue!!.snapshotId)
            assertTrue(model.state.value.error.orEmpty().contains("已取消打开"))
            openQueue()
            chooseQueue(0, "one.mkv")
            source.releaseRead.countDown()
            withTimeout(10_000) { model.state.first { it.selected?.name == "one.mkv" && !it.busy } }
            delay(300)
            assertEquals(snapshot, model.state.value.mediaQueue!!.snapshotId)
            assertEquals(orderedPaths, model.state.value.mediaQueue!!.items.map { it.wirePath })
            assertEquals("one.mkv", model.state.value.selected?.name)
            openQueue()
            chooseQueue(1, "two.mkv")
            withTimeout(10_000) { model.state.first { it.selected?.name == "two.mkv" && !it.busy } }
            assertEquals(snapshot, model.state.value.mediaQueue!!.snapshotId)
            assertEquals(orderedPaths, model.state.value.mediaQueue!!.items.map { it.wirePath })
            assertFalse(findFresh("下一个视频", enabled = false).enabled)
            assertFalse(model.player.state.value.playing)
        } finally { withContext(NonCancellable) {
            source.releaseRead.countDown()
            try { main { model.leavePlayer(); model.foreground(true); model.disconnect() } }
            finally {
                try { store.remove(profile) }
                finally {
                    try { source.close() }
                    finally { previousSession?.let { if (database.profiles().account(it.accountKey) != null) database.profiles().saveActiveSession(it) } }
                }
            }
        } }
    }
}
