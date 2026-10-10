package io.github.kkwans.nasfilebrowser

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.widget.FrameLayout
import androidx.lifecycle.ViewModelStore
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.kkwans.nasfilebrowser.app.ClientModel
import io.github.kkwans.nasfilebrowser.data.*
import io.github.kkwans.nasfilebrowser.download.*
import io.github.kkwans.nasfilebrowser.player.NativePlayer
import io.github.kkwans.nasfilebrowser.player.PlayerViewport
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.sin

/** Real owned media, actual ClientModel open/pause/leave/save/reopen and Room.
 * No PlayerState injection, real account edits, cache clearing or new product API.
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class DownloadResumeProgressTest {
    @get:Rule val activity = ActivityScenarioRule(EngineProbeActivity::class.java)
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private suspend fun <T> main(action: () -> T): T = withContext(Dispatchers.Main) { action() }
    private suspend fun await(stage: String, condition: suspend () -> Boolean) {
        try { withTimeout(20_000) { while (!condition()) delay(50) } }
        catch (failure: TimeoutCancellationException) { throw AssertionError("Owned resume stage timed out: $stage", failure) }
    }
    private fun engine(player: NativePlayer): ExoPlayer = NativePlayer::class.java.getDeclaredField("engine")
        .apply { isAccessible = true }.get(player) as ExoPlayer
    private suspend fun picture(view: PlayerViewport): Boolean {
        val bitmap = Bitmap.createBitmap(64, 36, Bitmap.Config.ARGB_8888)
        try {
            val copied = CompletableDeferred<Int>()
            main { PixelCopy.request(view.video, bitmap, { copied.complete(it) }, Handler(Looper.getMainLooper())) }
            if (withTimeout(5000) { copied.await() } != PixelCopy.SUCCESS) return false
            val pixels = IntArray(64 * 36); bitmap.getPixels(pixels, 0, 64, 0, 0, 64, 36)
            return pixels.count { pixel ->
                val channels = listOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel))
                channels.max() > 80 && channels.max() - channels.min() > 40
            } > pixels.size / 5
        } finally { bitmap.recycle() }
    }
    private suspend fun owned(bytes: ByteArray, extension: String, partial: Boolean,
        block: suspend (ClientModel, DownloadRecord, PlayerViewport, DownloadIndex.Info?, AtomicInteger) -> Unit) {
        val database = ClientDatabase.get(context); val dao = database.downloads()
        val previous = database.profiles().activeSession()
        val originalRows = dao.observe().first()
        val store = ProfileStore(database, CredentialVault(context))
        val source = NativePlaybackTest.Fixture(bytes, download = true)
        val profile = store.save(ServerProfile(name = "Owned resume progress", address = source.url))
        val session = NasSession.login(profile, "fixture", "fixture-only")
        val holder = ViewModelStore(); val target = DownloadTarget(context)
        var record: DownloadRecord? = null; var model: ClientModel? = null; var mounted: PlayerViewport? = null
        try {
            val account = store.saveLogin(profile, session.identity.id, session.identity.username, session.token())
            val id = UUID.randomUUID().toString(); val prefix = if (partial) bytes.size / 2 else bytes.size
            val draft = DownloadRecord(id, dao.lastJobId() + 1, account.key, profile.id, profile.sourceRevision,
                "/fixture.mkv", "/fixture.mkv", "owned-resume-$id.$extension", if (extension == "wav") "audio" else "video",
                bytes.size.toLong(), "owned-download-v1", "${bytes.size}/owned-download-v1", "Owned resume progress", "",
                status = if (partial) "paused" else "completed", downloaded = prefix.toLong(), createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis())
            val uri = target.allocate(draft); val item = draft.copy(localUri = uri.toString()); record = item
            context.contentResolver.openOutputStream(uri, "w")!!.use { it.write(bytes, 0, prefix) }
            dao.insert(item)
            val index = if (partial) withContext(Dispatchers.IO) { DownloadIndex.get(context).prepare(item) } else null
            if (index != null) check(index.availableMs(item.downloaded) in 1500 until index.durationUs / 1000 - 1500)
            // Definite loopback refusal is immediate and becomes the product's
            // real DownloadPendingException, without a slow/ambiguous Internet outage.
            source.close()
            val pending = AtomicInteger()
            val actual = main {
                ClientModel(context.applicationContext as Application).also {
                    holder.put("owned-resume", it)
                    it.selectProfile(profile) // Cancels automatic restore before it can bind a real account.
                    it.foreground(true)
                }
            }
            model = actual
            activity.scenario.onActivity { host ->
                val view = PlayerViewport(host); host.viewport.addView(view, FrameLayout.LayoutParams(-1, -1))
                mounted = view; actual.player.attach(view)
            }
            main { actual.openDownload(item) }
            await("owned player engine") { main { runCatching { engine(actual.player) }.isSuccess } }
            main { engine(actual.player).addAnalyticsListener(object : AnalyticsListener {
                override fun onLoadError(eventTime: AnalyticsListener.EventTime, info: LoadEventInfo, data: MediaLoadData, error: IOException, wasCanceled: Boolean) {
                    if (!wasCanceled && generateSequence<Throwable>(error) { it.cause }.take(12).any { it is DownloadPendingException }) pending.incrementAndGet()
                }
            }) }
            block(actual, item, requireNotNull(mounted), index, pending)
            assertTrue("Owned local playback must not replace the active account", previous == database.profiles().activeSession())
        } finally { withContext(NonCancellable) {
            main { model?.leavePlayer() }
            // Await only our already-existing progress/close jobs before removing
            // our row; no personal session/cache/global scheduler reset.
            for (field in listOf("mediaClosing", "localProgressPending")) {
                val job = model?.let { ClientModel::class.java.getDeclaredField(field).apply { isAccessible = true }.get(it) as? Job }
                job?.join()
            }
            main { model?.disconnect(); holder.clear(); mounted?.let { (it.parent as? android.view.ViewGroup)?.removeView(it) } }
            session.close(); source.close()
            record?.let { check(target.delete(it)) { "Owned resume fixture file cleanup failed; record retained" }; DownloadIndex.get(context).remove(it); dao.removeRecord(it.id) }
            store.remove(profile)
            previous?.let { if (database.profiles().account(it.accountKey) != null) database.profiles().saveActiveSession(it) }
            assertTrue("Existing download records must remain unchanged", originalRows == dao.observe().first())
        } }
    }

    @Test fun pendingSeekExitRetainsSavedResumeAndReadySeekCanSave(): Unit = runBlocking {
        val bytes = instrumentation.context.assets.open("media/fixture.mkv").use { it.readBytes() }
        owned(bytes, "mkv", partial = true) { model, item, view, index, pending ->
            val dao = ClientDatabase.get(context).downloads(); val native = model.player
            await("cached native playback and actual picture") { native.state.value.let { it.playing && it.positionMs > 400 && it.seekable } && picture(view) }
            main { model.pausePlayback() }
            await("paused-ready saves real position") { main { engine(native).playbackState == Player.STATE_READY } &&
                !native.state.value.playing && abs(dao.get(item.id)!!.positionMs - native.state.value.positionMs) <= 100 && dao.get(item.id)!!.positionMs > 300 }
            val saved = dao.get(item.id)!!.positionMs
            val available = requireNotNull(index).availableMs(item.downloaded)
            val missing = (available + 2000).coerceAtMost(index.durationUs / 1000 - 1000)
            check(missing > available && missing > saved + 1000)
            pending.set(0)
            main { native.toggle(); native.seek(missing) }
            await("real pending read at requested uncached seek") { pending.get() > 0 && native.state.value.let { it.waitingForBuffer && it.error == null && abs(it.positionMs - missing) < 500 } }
            main { model.leavePlayer() }
            // The model's real close job awaits local saves then stops the engine.
            await("leave closed native media") { main { runCatching { engine(native) }.isFailure } }
            assertEquals("Unrendered waiting seek must not replace the saved position", saved, dao.get(item.id)!!.positionMs)

            val reopened = requireNotNull(dao.get(item.id))
            main { model.openDownload(reopened) }
            await("reopened saved range produces pixels") { native.state.value.let { it.playing && it.positionMs in saved..(saved + 1000) } && picture(view) }
            main { model.pausePlayback(); native.seek(1000) }
            await("paused seek reaches actual READY frame") { main { engine(native).playbackState == Player.STATE_READY } &&
                !native.state.value.playing && native.state.value.positionMs in 900..1100 && picture(view) }
            main { model.pausePlayback() }
            await("confirmed READY seek saves") { dao.get(item.id)!!.positionMs in 900..1100 }
        }
    }

    @Test fun audioPausedReadyAndNaturalEndSaveWithoutAnyVideoFrame(): Unit = runBlocking {
        val samples = 48_000 * 3
        val bytes = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + samples * 2); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(1); putInt(48_000); putInt(96_000); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(samples * 2)
            repeat(samples) { putShort((sin(2 * Math.PI * 440 * it / 48_000) * 1000).toInt().toShort()) }
        }.array()
        owned(bytes, "wav", partial = false) { model, item, _, _, _ ->
            val dao = ClientDatabase.get(context).downloads(); val native = model.player
            await("owned PCM clock") { native.state.value.let { it.playing && it.positionMs > 300 && it.durationMs in 2950..3050 } }
            assertFalse(native.state.value.firstFrameRendered)
            main { model.pausePlayback() }
            await("audio paused-ready persisted") { !native.state.value.playing && dao.get(item.id)!!.positionMs > 250 }
            main { native.seek(1800) }
            await("paused audio seek READY") { main { engine(native).playbackState == Player.STATE_READY } && native.state.value.positionMs in 1700..1900 }
            main { model.pausePlayback() }
            await("paused audio seek persisted") { dao.get(item.id)!!.positionMs in 1700..1900 }
            main { model.togglePlayback() }
            await("natural audio end checkpoint persisted") { main { engine(native).playbackState == Player.STATE_ENDED } && dao.get(item.id)!!.positionMs >= 2900 }
            assertFalse(native.state.value.firstFrameRendered)
        }
    }
}
