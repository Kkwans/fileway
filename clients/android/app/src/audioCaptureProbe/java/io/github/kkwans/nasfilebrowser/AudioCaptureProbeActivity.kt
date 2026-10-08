package io.github.kkwans.nasfilebrowser

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.FrameLayout

/** Optional debug-only host; never constructs ClientModel or touches saved accounts. */
class AudioCaptureProbeActivity : Activity() {
    lateinit var viewport: FrameLayout
        private set
    var captureDenied = false
        private set
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        viewport = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        setContentView(viewport)
    }
    fun requestOwnedPlaybackCapture() {
        check(ownedAudioProbeDevice()) { "Explicitly supported owned test device required" }
        check(checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
        val manager = getSystemService(MediaProjectionManager::class.java)
        val request = if (Build.VERSION.SDK_INT >= 34) manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay()) else manager.createScreenCaptureIntent()
        @Suppress("DEPRECATION")
        startActivityForResult(request, 417)
    }
    @Deprecated("Optional diagnostic platform result bridge")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 417) return
        if (resultCode != RESULT_OK || data == null) { captureDenied = true; return }
        startForegroundService(Intent(this, PlaybackCaptureProbeService::class.java).putExtra("resultCode", resultCode).putExtra("projection", data))
    }
    override fun onDestroy() { stopService(Intent(this, PlaybackCaptureProbeService::class.java)); super.onDestroy() }
}

internal fun ownedAudioProbeDevice(): Boolean = Build.DEVICE == "houji" ||
    Build.HARDWARE in setOf("ranchu", "goldfish") && Build.MODEL.startsWith("sdk_")
