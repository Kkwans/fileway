package io.github.kkwans.nasfilebrowser

import android.Manifest
import android.content.pm.PackageManager
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@SdkSuppress(minSdkVersion = 37)
@RunWith(AndroidJUnit4::class)
class LocalNetworkPermissionTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)
    @Test fun deniedPermissionProvidesRecoveryAndGrantedPermissionUpdatesUi() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK))
        assertTrue(device.wait(Until.hasObject(By.text("允许访问")), 15_000))
        device.findObject(By.text("允许访问")).click()
        val deny = device.wait(Until.findObject(By.res("com.android.permissioncontroller", "permission_deny_button")), 5_000)
        assertNotNull("System permission prompt must appear", deny)
        deny.click()
        assertTrue(device.wait(Until.hasObject(By.text("打开设置")), 5_000))
        assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK))
        device.findObject(By.text("允许访问")).click()
        val allow = device.wait(Until.findObject(By.res("com.android.permissioncontroller", "permission_allow_button")), 5_000)
        assertNotNull("User can retry after denial", allow)
        allow.click()
        assertTrue(device.wait(Until.gone(By.text("本地网络访问")), 5_000))
        assertEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK))
        // No node or NAS identity is contacted. The ephemeral runner discards
        // this package and its grants after instrumentation completes.
    }
}
