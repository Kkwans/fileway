package io.github.kkwans.nasfilebrowser

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import io.github.kkwans.nasfilebrowser.app.ClientModel
import io.github.kkwans.nasfilebrowser.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/** Real Windows server -> shared Go lease -> installed image/native player UI.
 * Only owned PNG/H.264 fixtures; this is not HDR/audio/long-film acceptance. */
@RunWith(AndroidJUnit4::class)
internal class RealWindowsConnectionTest : LibraryUiHarness() {
    private fun click(label: String) {
        var target = text(label)
        while (!target.isClickable) target = target.parent ?: error("Missing owned control")
        target.click()
    }

    private suspend fun assertNativePicture() {
        withTimeout(5000) {
            var picture = false
            while (!picture) {
                val surfaces = mutableListOf<SurfaceView>()
                activity.scenario.onActivity { owner ->
                    fun visit(view: View) {
                        if (view is SurfaceView && view.holder.surface.isValid) surfaces.add(view)
                        if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i))
                    }
                    visit(owner.window.decorView)
                }
                for (surface in surfaces) {
                    val bitmap = Bitmap.createBitmap(64, 36, Bitmap.Config.ARGB_8888)
                    try {
                        val result = suspendCoroutine<Int> { continuation ->
                            PixelCopy.request(surface, bitmap, { continuation.resume(it) }, Handler(Looper.getMainLooper()))
                        }
                        if (result == PixelCopy.SUCCESS) {
                            val pixels = IntArray(64 * 36)
                            bitmap.getPixels(pixels, 0, 64, 0, 0, 64, 36)
                            picture = picture || pixels.count { pixel ->
                                maxOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel)) -
                                    minOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel)) > 30
                            } > pixels.size / 5
                        }
                    } finally { bitmap.recycle() }
                }
                if (!picture) delay(100)
            }
        }
    }

    @ExternalNetworkAcceptance
    @Test fun windowsFormLoginImageVideoAndSavedAccountUseTheRealSharedServer(): Unit = runBlocking {
        require(android.os.Build.DEVICE == "houji" && androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("nfbRealWindows") == "true")
        val config = privateAdbConfiguration("REAL_WINDOWS_SOCKET", "fileway-windows-")
        val nonce = UUID.fromString(config.getString("nonce")).toString()
        val user = config.getString("username")
        require(user == "fileway-ui-win-${nonce.take(8)}")
        var password = config.getString("password"); config.remove("password")
        val png = config.getString("pngName"); val video = config.getString("videoName")
        require(png == "owned-$nonce +% #.png" && video == "owned-$nonce +% #.mp4")
        val database = ClientDatabase.get(instrumentation.targetContext)
        val store = ProfileStore(database, CredentialVault(instrumentation.targetContext))
        val previous = database.profiles().activeSession()
        val profile = store.save(ServerProfile(name = "Owned Windows $nonce", address = config.getString("baseUrl"), backend = BackendKind.WINDOWS))
        activity.scenario.onActivity { model = ViewModelProvider(it)[ClientModel::class.java] }
        try {
            main { model.selectProfile(profile) }
            withTimeout(5000) { model.state.first { !it.busy && it.profile?.id == profile.id } }
            text("Windows"); click("收起连接选项")
            assertTrue(device.wait(Until.gone(By.text("档案名称（选填）")), 5000))
            device.waitForIdle()
            val fields = device.findObjects(By.clazz("android.widget.EditText")).filter { !it.visibleBounds.isEmpty }
            assertEquals(3, fields.size)
            fields[1].text = user; fields[2].text = password
            activity.scenario.onActivity { WindowCompat.getInsetsController(it.window, it.window.decorView).hide(WindowInsetsCompat.Type.ime()) }
            click("连接服务器"); password = ""
            withTimeout(15_000) { model.state.first { it.connected && !it.busy && it.files.any { file -> file.name == png } } }
            assertEquals(BackendKind.WINDOWS, model.state.value.profile?.backend)
            assertEquals(user, model.state.value.accountName)
            val account = store.accounts(profile).single()
            assertEquals(account.key, store.active()?.second?.key)
            capture("windows-files")
            val api = NasSession.restore(profile, store.token(profile, account) ?: error("Saved Windows identity missing"), account.userId)
            val image = model.state.value.files.single { it.name == png }
            val lease = api.lease(image.path, image.wirePath)
            try {
                withContext(Dispatchers.IO) {
                    val request = URL(lease).openConnection() as HttpURLConnection
                    request.connectTimeout = 10_000; request.readTimeout = 10_000
                    request.setRequestProperty("Range", "bytes=0-7")
                    try {
                        assertEquals(206, request.responseCode)
                        assertArrayEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10), request.inputStream.use { it.readBytes() })
                    } finally { request.disconnect() }
                }
            } finally {
                io.github.kkwans.nasfilebrowser.core.NativeTransport.call(org.json.JSONObject().put("op", "revoke").put("url", lease))
                api.close()
            }
            click(png)
            withTimeout(10_000) {
                while (true) {
                    val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
                    val pixel = try { bitmap.getPixel(bitmap.width / 2, bitmap.height / 2) } finally { bitmap.recycle() }
                    if (Color.green(pixel) > 160 && Color.red(pixel) < 70 && Color.blue(pixel) in 110..150) break
                    delay(200)
                }
            }
            assertEquals(png, model.state.value.image?.name); capture("windows-image")
            device.pressBack()
            withTimeout(5000) { model.state.first { it.image == null } }
            click(video)
            withTimeout(20_000) { model.player.state.first { it.firstFrameRendered && it.positionMs > 600 && it.width == 320 && it.error == null } }
            assertNativePicture(); capture("windows-video")
            device.pressBack()
            withTimeout(5000) { model.state.first { it.selected == null } }
            main { model.selectProfile(profile) }
            withTimeout(5000) { model.state.first { !it.busy && it.accounts.any { saved -> saved.key == account.key } } }
            click("继续使用 $user")
            withTimeout(15_000) { model.state.first { it.connected && !it.busy && it.accountName == user } }
            assertEquals(account.key, store.active()?.second?.key)
            assertEquals(BackendKind.WINDOWS, model.state.value.profile?.backend)
            capture("windows-restored")
        } finally { withContext(NonCancellable) {
            password = ""
            main { model.disconnect() }; store.remove(profile)
            previous?.let { if (database.profiles().account(it.accountKey) != null) database.profiles().saveActiveSession(it) }
        } }
    }
}
