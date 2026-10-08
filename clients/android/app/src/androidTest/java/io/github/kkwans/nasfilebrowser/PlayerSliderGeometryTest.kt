package io.github.kkwans.nasfilebrowser

import android.graphics.Color
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import io.github.kkwans.nasfilebrowser.ui.ClientTheme
import io.github.kkwans.nasfilebrowser.ui.PlayerSlider
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.rules.RuleChain
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlayerSliderGeometryTest {
    private val activity = ActivityScenarioRule(MainActivity::class.java)
    @get:Rule val rules: RuleChain = RuleChain.outerRule(OwnedUiTraceRule()).around(activity)
    @Test fun actualThumbAndTrackShareVerticalCentreAtFivePositionsAndStillDrag(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val values = mutableStateListOf(0f, 25f, 50f, 75f, 100f)
        activity.scenario.onActivity { host -> host.setContent {
            ClientTheme(darkTheme = true) {
                Column(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black).statusBarsPadding().padding(16.dp)) {
                    Text("播放器进度条校准")
                    values.indices.forEach { index ->
                        PlayerSlider(values[index], 100f, "进度校准$index", true, { values[index] = it }, downloadedValue = 75f)
                    }
                }
            }
        } }
        assertTrue(device.wait(Until.hasObject(By.desc("进度校准4")), 5000))
        device.waitForIdle()
        val shot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val pixels = shot.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
        val density = instrumentation.targetContext.resources.displayMetrics.density
        try {
            for (index in values.indices) {
                val bounds = device.findObject(By.desc("进度校准$index")).visibleBounds
                val spans = (bounds.left until bounds.right).mapNotNull { x ->
                    val ys = (bounds.top until bounds.bottom).filter { y ->
                        val color = pixels.getPixel(x, y)
                        maxOf(Color.red(color), Color.green(color), Color.blue(color)) > 40
                    }
                    if (ys.isEmpty()) null else ys.last() - ys.first() + 1 to (ys.first() + ys.last()) / 2f
                }
                val thumb = spans.maxByOrNull { it.first } ?: error("Thumb not rendered")
                assertTrue("Thumb must retain its visible diameter", thumb.first >= 8 * density)
                val track = spans.filter { it.first in (2 * density).toInt()..(4 * density).toInt() }.map { it.second }.sorted()
                assertTrue("Track must be visibly rendered", track.size > bounds.width() / 2)
                assertEquals("Thumb/track vertical centre at ${values[index]}%", track[track.size / 2], thumb.second, 1f)
            }
            val range = device.findObject(By.desc("进度校准2")).visibleBounds
            val downloaded = pixels.getPixel(range.left + range.width() * 7 / 10, range.centerY())
            val unavailable = pixels.getPixel(range.left + range.width() * 9 / 10, range.centerY())
            assertTrue("Downloaded time range must be visibly distinct from unavailable track", Color.red(downloaded) > Color.red(unavailable) + 40)
        } finally { pixels.recycle(); shot.recycle() }
        device.executeShellCommand("mkdir -p /sdcard/Download/nfb-client-acceptance")
        device.executeShellCommand("screencap -p /sdcard/Download/nfb-client-acceptance/slider-centres.png")
        withTimeout(5000) {
            while (true) {
                var focused = false
                activity.scenario.onActivity { focused = it.hasWindowFocus() }
                if (focused) break
                delay(50)
            }
        }
        device.waitForIdle()
        val bounds = device.findObject(By.desc("进度校准2")).visibleBounds
        OwnedUiTraceRule.trace("slider-drag bounds=$bounds before=${values[2]}")
        assertTrue(device.swipe(bounds.centerX(), bounds.centerY(), bounds.left + bounds.width() * 3 / 4, bounds.centerY(), 40))
        val changed = withTimeoutOrNull(5000) { while (values[2] < 65f) delay(50); true } == true
        assertTrue("Physical drag must change slider value; bounds=$bounds actual=${values[2]}", changed)
    }
}
