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

/** A real decoder reads an incomplete owned file; no upstream exists during playback. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class DownloadOfflineIndexTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)
    @Test fun mkvStartsFromSavedPrefixWithoutNetwork(): Unit = runBlocking { verify("fixture.mkv") }
    @Test fun frontIndexedMp4StartsFromSavedPrefixWithoutNetwork(): Unit = runBlocking { verify("fixture-front.mp4") }
    @Test fun tailIndexedMp4StartsFromSavedPrefixWithoutNetwork(): Unit = runBlocking { verify("fixture-tail.mp4") }
    private suspend fun verify(asset: String) {
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
        try {
            val account = store.saveLogin(profile, session.identity.id, session.identity.username, session.token())
            val id = UUID.randomUUID().toString(); val prefix = bytes.size / 2
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
        } finally { withContext(NonCancellable) {
            withContext(Dispatchers.Main) { player?.release() }
            owned?.let { target.delete(it); database.downloads().removeRecord(it.id) }
            session.close(); source.close(); store.remove(profile)
        } }
    }
}
