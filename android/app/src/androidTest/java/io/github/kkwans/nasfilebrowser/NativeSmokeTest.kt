package io.github.kkwans.nasfilebrowser

import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import io.github.kkwans.nasfilebrowser.core.NativeTransport
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class NativeSmokeTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)

    @Test fun installedAppLoadsNativeLibrariesAndRendersConnection() = runBlocking {
        val result = NativeTransport.call(JSONObject().put("op", "init")) as JSONObject
        assertEquals(1, result.getInt("protocol"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        assertTrue(device.wait(Until.hasObject(By.text("连接服务器")), 10_000))
        assertTrue(device.hasObject(By.text("服务器地址")))
        val folder = instrumentation.targetContext.getExternalFilesDir("acceptance")!!
        folder.mkdirs()
        assertTrue(device.takeScreenshot(File(folder, "connection.png")))
    }
}
