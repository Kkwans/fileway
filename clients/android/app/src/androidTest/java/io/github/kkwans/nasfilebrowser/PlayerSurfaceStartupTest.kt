package io.github.kkwans.nasfilebrowser

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import io.github.kkwans.nasfilebrowser.app.*
import io.github.kkwans.nasfilebrowser.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.videolan.libvlc.LibVLC

/** Repeated real input starts with a laid-out video Surface, not metadata-only readiness. */
class PlayerSurfaceStartupTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)
    private suspend fun hasVideo(): Boolean {
        fun find(view: View): SurfaceView? {
            if (view is SurfaceView && runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull() == "surface_video") return view
            if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
            return null
        }
        var surface: SurfaceView? = null
        activity.scenario.onActivity { surface = find(it.window.decorView) }
        val view = surface ?: return false
        if (view.width <= 0 || view.height <= 0 || !view.holder.surface.isValid) return false
        val bitmap = Bitmap.createBitmap(144, 81, Bitmap.Config.ARGB_8888)
        val result = CompletableDeferred<Int>(); var requested = false
        try {
            withContext(Dispatchers.Main) { PixelCopy.request(view, bitmap, { result.complete(it) }, Handler(Looper.getMainLooper())); requested = true }
            if (result.await() != PixelCopy.SUCCESS) return false
            val pixels = IntArray(144 * 81); bitmap.getPixels(pixels, 0, 144, 0, 0, 144, 81)
            return pixels.count { maxOf(Color.red(it), Color.green(it), Color.blue(it)) - minOf(Color.red(it), Color.green(it), Color.blue(it)) > 30 } > pixels.size / 5
        } finally { if (!requested || result.isCompleted) bitmap.recycle() else result.invokeOnCompletion { bitmap.recycle() } }
    }
    @Test fun repeatedOpenHasDecodedVideoWithPositiveWindowBounds(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val media = instrumentation.context.assets.open("media/fixture.mkv").use { it.readBytes() }
        val source = NativePlaybackTest.Fixture(media)
        val store = ProfileStore(ClientDatabase.get(instrumentation.targetContext), CredentialVault(instrumentation.targetContext))
        val profile = store.save(ServerProfile(name = "Surface startup acceptance", address = source.url))
        lateinit var model: ClientModel
        activity.scenario.onActivity { model = ViewModelProvider(it)[ClientModel::class.java] }
        try {
            android.util.Log.i("FilewayNativeGate", "libVLC=${LibVLC.version()} changeset=${LibVLC.changeset()} verbose=${BuildConfig.NATIVE_VERBOSE}")
            withContext(Dispatchers.Main) { model.selectProfile(profile); model.connectDraft(profile.name, source.url, BackendKind.NAS, "fixture", "fixture-only", "direct") }
            withTimeout(10_000) { model.state.first { it.connected && !it.busy } }
            repeat(3) { iteration ->
                withContext(Dispatchers.Main) { model.open(ResourceRef("/fixture.mkv", "/fixture.mkv", "Owned startup.mkv", false, "video", media.size.toLong())) }
                try {
                    withTimeout(20_000) { model.player.state.first { it.playing && it.positionMs > 0 && it.durationMs > 0 }; while (!hasVideo()) delay(100) }
                } catch (e: TimeoutCancellationException) {
                    val s = model.player.state.value
                    throw AssertionError("Open $iteration stalled: phase=${s.phase}, playing=${s.playing}, position=${s.positionMs}, buffer=${s.buffering}, rawReads=${source.rawRequests.get()}, trace=${model.player.diagnosticSnapshot()}", e)
                }
                withContext(Dispatchers.Main) { model.leavePlayer() }
                delay(250)
            }
            assertEquals(0, source.unexpected.get())
        } finally { withContext(Dispatchers.Main) { model.disconnect() }; store.remove(profile); source.close() }
    }
}
