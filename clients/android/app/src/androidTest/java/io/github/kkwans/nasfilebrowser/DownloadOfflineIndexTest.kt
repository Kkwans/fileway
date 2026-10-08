package io.github.kkwans.nasfilebrowser

import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
import java.util.UUID
import org.junit.Assume.assumeTrue

/** A real decoder reads an incomplete owned file; no upstream exists during playback. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class DownloadOfflineIndexTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)
    @Test fun mkvStartsFromSavedPrefixWithoutNetwork(): Unit = runBlocking { verify("fixture.mkv") }
    @Test fun frontIndexedMp4StartsFromSavedPrefixWithoutNetwork(): Unit = runBlocking { verify("fixture-front.mp4") }
    @Test fun tailIndexedMp4StartsFromSavedPrefixWithoutNetwork(): Unit = runBlocking { verify("fixture-tail.mp4") }
    @Test fun offlineReadAheadKeepsPlayingSavedRangeAndResumesWhenFileGrows(): Unit = runBlocking { verify("fixture.mkv", boundary = true) }
    @Test fun manualPauseAtDownloadBoundarySurvivesDataArrival(): Unit = runBlocking { verify("fixture.mkv", boundary = true, pauseAtBoundary = true) }
    @ExternalNetworkAcceptance // Requires explicit host process orchestration, not UTP discovery.
    @Test fun prepareOwnedPartialForProcessRestart(): Unit = runBlocking {
        assumeTrue("Requires the two-process host workflow", InstrumentationRegistry.getArguments().getString("nfbOfflineRestart") == "true")
        val prefs = InstrumentationRegistry.getInstrumentation().targetContext.getSharedPreferences("fileway-owned-offline-restart", 0)
        check(!prefs.contains("id")) { "Previous owned restart probe must be completed before another is prepared" }
        verify("fixture.mkv", persist = true)
    }
    @ExternalNetworkAcceptance
    @Test fun reopenOwnedPartialInFreshProcessWithoutNetwork(): Unit = runBlocking {
        assumeTrue("Requires the two-process host workflow", InstrumentationRegistry.getArguments().getString("nfbOfflineRestart") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("fileway-owned-offline-restart", 0)
        val id = requireNotNull(prefs.getString("id", null))
        assertNotEquals("The cache must be reopened in another process", prefs.getInt("pid", -1), android.os.Process.myPid())
        val database = ClientDatabase.get(context)
        val record = requireNotNull(database.downloads().get(id))
        check(record.name == "owned-$id-fixture.mkv" && record.sourceLabel == "Owned fixture" && record.profileId == prefs.getString("profile", null))
        val store = ProfileStore(database, CredentialVault(context))
        val profile = requireNotNull(store.profile(record.profileId))
        check(profile.name == "Owned offline index" && java.net.URI(profile.address).host == "127.0.0.1")
        var player: NativePlayer? = null
        try {
            val info = withContext(Dispatchers.IO) { requireNotNull(DownloadIndex.get(context).info(record)) }
            activity.scenario.onActivity { host ->
                val view = PlayerViewport(host); host.setContentView(view)
                player = NativePlayer(host).also { it.attach(view); it.open("fileway-download://$id/fixture.mkv", dataSourceFactory = DownloadDataSource.Factory(context, networkAllowed = false)) }
            }
            val native = requireNotNull(player)
            withTimeout(20_000) { native.state.first { it.firstFrameRendered && it.playing && it.positionMs > 300 } }
            val seek = (info.availableMs(record.downloaded) - 1000).coerceAtLeast(500)
            withContext(Dispatchers.Main) { native.seek(seek) }
            withTimeout(10_000) { native.state.first { it.playing && it.positionMs > seek + 300 && it.phase != "正在跳转" } }
            assertFalse(record.complete)
            assertNull(native.state.value.error)
        } finally { withContext(NonCancellable) {
            withContext(Dispatchers.Main) { player?.release() }
            DownloadTarget(context).delete(record); database.downloads().removeRecord(id); store.remove(profile)
            check(prefs.edit().clear().commit())
        } }
    }
    private suspend fun verify(asset: String, boundary: Boolean = false, pauseAtBoundary: Boolean = false, persist: Boolean = false) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val bytes = instrumentation.context.assets.open("media/$asset").use { it.readBytes() }
        val source = NativePlaybackTest.Fixture(bytes, download = true)
        val database = ClientDatabase.get(context)
        val store = ProfileStore(database, CredentialVault(context))
        val profile = store.save(ServerProfile(name = "Owned offline index", address = source.url))
        val session = NasSession.login(profile, "fixture", "fixture-only")
        val target = DownloadTarget(context)
        var owned: DownloadRecord? = null
        var player: NativePlayer? = null
        var retained = false
        try {
            val account = store.saveLogin(profile, session.identity.id, session.identity.username, session.token())
            val id = UUID.randomUUID().toString(); val prefix = if (boundary) bytes.size * 3 / 4 else bytes.size / 2
            val record = DownloadRecord(id, database.downloads().lastJobId() + 1, account.key, profile.id, profile.sourceRevision,
                "/fixture.mkv", "/fixture.mkv", "owned-$id-$asset", "video", bytes.size.toLong(), "owned-download-v1", "${bytes.size}/owned-download-v1", "Owned fixture", "",
                status = "paused", downloaded = prefix.toLong(), createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis())
            val uri = target.allocate(record); owned = record.copy(localUri = uri.toString())
            context.contentResolver.openOutputStream(uri, "w")!!.use { it.write(bytes, 0, prefix) }
            database.downloads().insert(requireNotNull(owned))
            val info = withContext(Dispatchers.IO) { DownloadIndex.get(context).prepare(requireNotNull(owned)) }
            assertTrue(info.durationUs > 0)
            assertTrue("An index must identify some fully downloaded time", info.availableMs(prefix.toLong()) > 0)
            assertTrue(info.availableMs(prefix.toLong()) < info.durationUs / 1000)
            if (persist) {
                check(context.getSharedPreferences("fileway-owned-offline-restart", 0).edit().putString("id", id)
                    .putString("profile", profile.id).putInt("pid", android.os.Process.myPid()).commit())
                retained = true
                return
            }
            source.close() // Even an accidental upstream attempt now fails.
            val requests = source.rawRequests.get()
            activity.scenario.onActivity { host ->
                val view = PlayerViewport(host); host.setContentView(view)
                player = NativePlayer(host).also { it.attach(view); it.open("fileway-download://$id/$asset", dataSourceFactory = DownloadDataSource.Factory(context, networkAllowed = false)) }
            }
            val native = requireNotNull(player)
            withTimeout(20_000) { native.state.first { it.firstFrameRendered && it.playing && it.positionMs > 300 } }
            assertEquals(requests, source.rawRequests.get())
            assertFalse(database.downloads().get(id)!!.complete)
            assertEquals(prefix.toLong(), database.downloads().get(id)!!.downloaded)
            if (boundary) {
                val available = info.availableMs(prefix.toLong())
                val reached = withTimeout(20_000) { native.state.first { it.error != null || it.positionMs >= available - 250 } }
                assertTrue("Read-ahead must not stop saved playback early: available=$available actual=${reached.positionMs} error=${reached.error}", reached.positionMs >= available - 250)
                val waiting = withTimeout(15_000) { native.state.first { it.error != null || it.waitingForBuffer } }
                assertNull("Missing bytes must remain recoverable while offline", waiting.error)
                if (pauseAtBoundary) withContext(Dispatchers.Main) { native.pause() }
                context.contentResolver.openOutputStream(uri, "wa")!!.use { it.write(bytes, prefix, bytes.size - prefix) }
                val dao = database.downloads()
                dao.command(id, "queued", System.currentTimeMillis()); dao.claim(id, System.currentTimeMillis())
                val generation = requireNotNull(dao.get(id)).generation
                dao.progress(id, generation, bytes.size.toLong(), System.currentTimeMillis())
                dao.finish(id, generation, "completed", "", System.currentTimeMillis())
                if (pauseAtBoundary) {
                    withTimeout(15_000) { native.state.first { it.bufferedPositionMs > waiting.positionMs + 300 && !it.waitingForBuffer } }
                    assertFalse("Data arrival must not override manual pause", native.state.value.playing)
                    assertTrue(kotlin.math.abs(native.state.value.positionMs - waiting.positionMs) < 250)
                    withContext(Dispatchers.Main) { native.toggle() }
                }
                withTimeout(15_000) { native.state.first { it.playing && it.positionMs > waiting.positionMs + 300 } }
                assertNull(native.state.value.error)
            }
        } finally { withContext(NonCancellable) {
            withContext(Dispatchers.Main) { player?.release() }
            if (!retained) owned?.let { target.delete(it); database.downloads().removeRecord(it.id) }
            session.close(); source.close(); if (!retained) store.remove(profile)
        } }
    }
}
