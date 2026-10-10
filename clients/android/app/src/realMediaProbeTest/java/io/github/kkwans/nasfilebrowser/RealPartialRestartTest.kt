package io.github.kkwans.nasfilebrowser

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.AtomicFile
import android.view.PixelCopy
import android.widget.FrameLayout
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.kkwans.nasfilebrowser.data.*
import io.github.kkwans.nasfilebrowser.download.*
import io.github.kkwans.nasfilebrowser.player.NativePlayer
import io.github.kkwans.nasfilebrowser.player.PlayerViewport
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Three explicit host invocations; host must stop only this App between prepare
 * and reopen. No UTP, production media edits, global cache deletion or key deletion.
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class RealPartialRestartTest {
    @get:Rule val activity = ActivityScenarioRule(EngineProbeActivity::class.java)
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val database get() = ClientDatabase.get(context)
    private val store get() = ProfileStore(database, CredentialVault(context))
    private val marker = "Owned real partial restart"
    private val prefixBytes = 16L * 1024 * 1024
    private var stage = "configuration"
    private suspend fun <T> main(block: () -> T): T = withContext(Dispatchers.Main) { block() }
    private suspend fun configuration(phase: String): JSONObject {
        require(Build.DEVICE == "houji" && InstrumentationRegistry.getArguments().getString("nfbRealMedia") == "true")
        return privateAdbConfiguration("REAL_MEDIA_SOCKET", "fileway-real-").also {
            check(it.optBoolean("authorizeOwnedPrefix") && it.getString("partialRestartPhase") == phase) { "Explicit owned-prefix phase authorization is required" }
        }
    }
    private fun manifestFile(id: String): File {
        check(UUID.fromString(id).toString() == id) { "Invalid owned UUID" }
        return File(File(context.noBackupFilesDir, "owned-real-partial-restart"), "$id.json")
    }
    private fun save(file: File, manifest: JSONObject) {
        check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
        val bytes = manifest.toString().toByteArray(Charsets.UTF_8)
        val atomic = AtomicFile(file); val output = atomic.startWrite()
        try {
            output.write(bytes); output.fd.sync(); atomic.finishWrite(output)
            check(file.isFile && file.readBytes().contentEquals(bytes)) { "Owned recovery manifest was not committed" }
        } catch (failure: Throwable) { atomic.failWrite(output); throw failure }
    }
    private fun load(config: JSONObject): Pair<File, JSONObject> {
        val id = config.getString("ownedId"); val file = manifestFile(id)
        val manifest = JSONObject(AtomicFile(file).readFully().toString(Charsets.UTF_8))
        check(manifest.getInt("version") == 1 && manifest.getString("ownedId") == id &&
            manifest.getString("marker") == "$marker:$id" && manifest.getString("profileId") == "owned-real-partial-$id") { "Owned recovery marker differs" }
        return file to manifest
    }
    private fun report(manifest: JSONObject, fields: JSONObject = JSONObject()) {
        fields.put("ownedId", manifest.getString("ownedId")).put("stage", stage).put("pid", Process.myPid())
        instrumentation.addResults(Bundle().apply { putString("filewayRealPartialRestart", fields.toString()) })
    }
    private suspend fun snapshot(excluding: String): String = JSONArray(database.downloads().observe().first()
        .filter { it.id != excluding }.sortedBy { it.id }.map { it.toString() }).toString()
    private suspend fun unchanged(manifest: JSONObject) {
        check(snapshot(manifest.getString("ownedId")) == manifest.getString("originalDownloads")) { "Existing download snapshot changed; recovery material retained" }
    }
    private fun previousSession(manifest: JSONObject): ActiveSession? = if (manifest.isNull("previousSession")) null else
        manifest.getJSONObject("previousSession").let { ActiveSession(it.getInt("id"), it.getString("accountKey"), it.getString("owner")) }
    private fun row(manifest: JSONObject): DownloadRecord {
        val value = manifest.getJSONObject("record")
        return DownloadRecord(manifest.getString("ownedId"), value.getInt("jobId"), value.getString("accountKey"),
            manifest.getString("profileId"), value.getLong("sourceRevision"), value.getString("path"), value.getString("wirePath"),
            value.getString("name"), "video", value.getLong("size"), value.getString("modified"), value.getString("identity"),
            manifest.getString("marker"), "", localUri = value.optString("localUri"), status = "paused", downloaded = prefixBytes,
            createdAt = value.getLong("createdAt"), updatedAt = value.getLong("createdAt"))
    }
    private fun spans(record: DownloadRecord): List<Pair<Long, Long>> {
        // Test-only observation of actual persisted spans, never a fabricated time map.
        val index = DownloadIndex.get(context)
        val cache = DownloadIndex::class.java.getDeclaredField("cache").apply { isAccessible = true }.get(index) as Cache
        return cache.getCachedSpans(record.id).map { it.position to it.length }.sortedBy { it.first }
    }
    private fun encodedSpans(record: DownloadRecord): String = JSONArray(spans(record).map { JSONArray(listOf(it.first, it.second)) }).toString()
    private fun cached(record: DownloadRecord, offset: Long): Boolean = offset in 0 until record.downloaded ||
        spans(record).any { (start, length) -> offset >= start && offset - start < length }
    private suspend fun source(config: JSONObject): NasSession {
        val address = config.getString("baseUrl").trimEnd('/')
        if (config.has("token")) {
            val token = config.getString("token"); config.remove("token")
            return NasSession.restore(ServerProfile(name = marker, address = address), token, NasSession.parseIdentity(token).id)
        }
        check(config.optBoolean("useSavedAndroidSession")) { "No authorized session source" }
        val bindings = store.profiles.first().filter { it.backend == BackendKind.NAS && it.address.trimEnd('/') == address }
            .flatMap { profile -> store.accounts(profile).map { profile to it } }
        val active = database.profiles().activeSession()?.accountKey
        val binding = bindings.singleOrNull { it.second.key == active } ?: bindings.singleOrNull()
            ?: error("Saved source account is ambiguous")
        val wire = config.optString("wirePath").ifEmpty { SearchResult.encodePath(config.getString("path")) }
        return restoreSavedSession(store, binding.first, binding.second, "/api/resources$wire?metadata=1").api
    }

    @ExternalNetworkAcceptance
    @Test fun prepareOwnedRealPrefixForRestart(): Unit = runBlocking {
        val config = configuration("prepare")
        val id = UUID.randomUUID().toString(); val file = manifestFile(id)
        check(!file.exists())
        val previous = database.profiles().activeSession()
        val manifest = JSONObject().put("version", 1).put("ownedId", id).put("marker", "$marker:$id")
            .put("profileId", "owned-real-partial-$id").put("preparePid", Process.myPid()).put("phase", "created")
            .put("originalDownloads", snapshot(id)).put("previousSession", previous?.let {
                JSONObject().put("id", it.id).put("accountKey", it.accountKey).put("owner", it.owner)
            } ?: JSONObject.NULL)
        save(file, manifest); report(manifest)
        var session: NasSession? = null
        try {
            stage = "metadata"; val access = source(config); session = access
            val wire = config.optString("wirePath").ifEmpty { SearchResult.encodePath(config.getString("path")) }
            val metadata = access.request("GET", "/api/resources$wire?metadata=1")
            val size = metadata.getLong("size")
            check(!metadata.getBoolean("isDir") && metadata.getString("path") == config.getString("path") &&
                size > prefixBytes && size == config.optLong("expectedSize", config.optLong("size", -1))) { "Real source identity differs" }
            val extension = metadata.optString("name", metadata.getString("path")).substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)
            check(extension in setOf("mkv", "mp4")) { "Only an actual MKV or MP4 source is authorized" }
            val cachedMs = config.getLong("cachedSeekMs"); val missingMs = config.getLong("missingSeekMs")
            check(cachedMs > 0 && missingMs > cachedMs)
            val profile = store.save(access.profile.copy(id = manifest.getString("profileId"), name = manifest.getString("marker"), sourceRevision = 0, updatedAt = 0))
            manifest.put("profileCreated", true); save(file, manifest)
            val account = store.saveLogin(profile, access.identity.id, access.identity.username, access.token())
            val modified = metadata.optString("modified")
            val value = JSONObject().put("jobId", database.downloads().lastJobId() + 1).put("accountKey", account.key)
                .put("sourceRevision", profile.sourceRevision).put("path", metadata.getString("path"))
                .put("wirePath", metadata.getString("wirePath")).put("name", "fileway-owned-real-prefix-$id.$extension")
                .put("size", size).put("modified", modified).put("identity", "$size/$modified").put("createdAt", System.currentTimeMillis())
            manifest.put("record", value).put("container", extension).put("cachedSeekMs", cachedMs).put("missingSeekMs", missingMs)
            save(file, manifest)
            val target = DownloadTarget(context); val uri = target.allocate(row(manifest))
            value.put("localUri", uri.toString()); save(file, manifest)
            stage = "write-owned-16MiB"
            val lease = access.lease(metadata.getString("path"), metadata.getString("wirePath"))
            withContext(Dispatchers.IO) {
                val input = DefaultHttpDataSource.Factory().createDataSource()
                try {
                    input.open(DataSpec.Builder().setUri(lease).setLength(prefixBytes).build())
                    context.contentResolver.openOutputStream(uri, "w")!!.use { output ->
                        val buffer = ByteArray(128 * 1024); var left = prefixBytes
                        while (left > 0) { val n = input.read(buffer, 0, minOf(left, buffer.size.toLong()).toInt()); check(n > 0); output.write(buffer, 0, n); left -= n }
                    }
                    context.contentResolver.openFileDescriptor(uri, "r")!!.use { check(it.statSize == prefixBytes) }
                } finally { input.close() }
            }
            database.downloads().insert(row(manifest))
            stage = "prepare-persisted-index"
            val info = withContext(Dispatchers.IO) { DownloadIndex.get(context).prepare(row(manifest)) }
            check(info.durationUs / 1000 > missingMs)
            val after = access.request("GET", "/api/resources${metadata.getString("wirePath")}?metadata=1")
            check(after.getLong("size") == size && after.optString("modified") == modified) { "Source changed during preparation" }
            manifest.put("durationUs", info.durationUs).put("spanSnapshot", encodedSpans(row(manifest))).put("phase", "prepared")
            unchanged(manifest); check(database.profiles().activeSession() == previous)
            save(file, manifest); stage = "prepared"
            report(manifest, JSONObject().put("prefixBytes", prefixBytes).put("container", extension).put("totalBytes", size).put("spanCount", spans(row(manifest)).size))
        } catch (failure: Throwable) {
            manifest.put("phase", "failed:$stage"); runCatching { save(file, manifest) }
            report(manifest, JSONObject().put("failureClass", failure.javaClass.simpleName))
            throw AssertionError("Owned real prefix prepare failed at $stage (${failure.javaClass.simpleName}); recovery manifest retained")
        } finally { withContext(NonCancellable) { session?.close() } }
    }

    private suspend fun frame(view: PlayerViewport): Boolean {
        val bitmap = Bitmap.createBitmap(128, 72, Bitmap.Config.ARGB_8888)
        try {
            val result = CompletableDeferred<Int>()
            main { PixelCopy.request(view.video, bitmap, { result.complete(it) }, Handler(Looper.getMainLooper())) }
            if (withTimeout(5000) { result.await() } != PixelCopy.SUCCESS) return false
            val pixels = IntArray(128 * 72); bitmap.getPixels(pixels, 0, 128, 0, 0, 128, 72)
            val luma = pixels.map { (Color.red(it) * 3 + Color.green(it) * 6 + Color.blue(it)) / 10 }
            return luma.count { it > 20 } > pixels.size / 10 && requireNotNull(luma.maxOrNull()) - requireNotNull(luma.minOrNull()) > 24
        } finally { bitmap.recycle() }
    }
    private suspend fun rendered(player: NativePlayer): Int = main {
        val engine = NativePlayer::class.java.getDeclaredField("engine").apply { isAccessible = true }.get(player) as ExoPlayer
        engine.videoDecoderCounters?.renderedOutputBufferCount ?: 0
    }
    private suspend fun awaitStage(name: String, predicate: suspend () -> Boolean) {
        stage = name
        withTimeout(20_000) { while (!predicate()) delay(100) }
    }
    private data class Gap(val offset: Long, val task: Long)

    @ExternalNetworkAcceptance
    @Test fun reopenOwnedRealPrefixWithoutNetwork(): Unit = runBlocking {
        val config = configuration("verify"); val (file, manifest) = load(config)
        check(manifest.getString("phase") in setOf("prepared", "verified") || manifest.getString("phase").startsWith("verify-failed:"))
        check(Process.myPid() != manifest.getInt("preparePid") && Process.myPid() != manifest.optInt("lastVerifyPid", -1)) { "Host must cold-stop only this App before verify" }
        val record = row(manifest)
        check(database.downloads().get(record.id) == record && record.status == "paused")
        check(store.profile(record.profileId)?.name == manifest.getString("marker"))
        unchanged(manifest); check(database.profiles().activeSession() == previousSession(manifest))
        check(DownloadIndex.get(context).info(record) != null && encodedSpans(record) == manifest.getString("spanSnapshot")) { "Persisted index/spans are unavailable after restart" }
        val cachedMs = config.optLong("cachedSeekMs", manifest.getLong("cachedSeekMs"))
        val missingMs = config.optLong("missingSeekMs", manifest.getLong("missingSeekMs"))
        check(cachedMs > 0 && missingMs > cachedMs && missingMs < manifest.getLong("durationUs") / 1000)
        manifest.put("lastVerifyPid", Process.myPid()); save(file, manifest)
        var player: NativePlayer? = null; lateinit var view: PlayerViewport; var mounted = false
        val gap = AtomicReference<Gap?>(); val taskIds = mutableSetOf<Long>(); var previousTasks = emptySet<Long>(); var seekingMissing = false
        val listener = object : AnalyticsListener {
            override fun onLoadStarted(eventTime: AnalyticsListener.EventTime, info: LoadEventInfo, data: MediaLoadData) { taskIds.add(info.loadTaskId) }
            override fun onLoadError(eventTime: AnalyticsListener.EventTime, info: LoadEventInfo, data: MediaLoadData, error: IOException, wasCanceled: Boolean) {
                // Only zero bytes makes DataSpec.position the exact failing read
                // offset; never add bytes accumulated across extractor jumps.
                if (seekingMissing && !wasCanceled && info.loadTaskId !in previousTasks && info.bytesLoaded == 0L &&
                    info.dataSpec.uri.scheme == "fileway-download" && info.dataSpec.uri.host == record.id &&
                    generateSequence<Throwable>(error) { it.cause }.take(12).any { it is DownloadPendingException })
                    gap.set(Gap(info.dataSpec.position, info.loadTaskId))
            }
        }
        try {
            activity.scenario.onActivity { host ->
                view = PlayerViewport(host); host.viewport.addView(view, FrameLayout.LayoutParams(-1, -1)); mounted = true
                player = NativePlayer(host).also {
                    it.attach(view)
                    // Preserve the concrete Factory: NativePlayer uses its type
                    // to install the recoverable DownloadPending error policy.
                    it.open("fileway-download://${record.id}/${record.name}", dataSourceFactory = DownloadDataSource.Factory(context, networkAllowed = false))
                    val engine = NativePlayer::class.java.getDeclaredField("engine").apply { isAccessible = true }.get(it) as ExoPlayer
                    engine.addAnalyticsListener(listener)
                }
            }
            val native = requireNotNull(player)
            awaitStage("cold-first-frame") { native.state.value.let { it.firstFrameRendered && it.playing && it.positionMs > 300 && it.error == null } && frame(view) }
            val firstPosition = native.state.value.positionMs; val firstCount = rendered(native)
            awaitStage("cold-frame-and-clock-advance") { native.state.value.positionMs > firstPosition + 300 && rendered(native) > firstCount + 2 && frame(view) }
            suspend fun cachedSeek(name: String) {
                val before = rendered(native)
                main { native.seek(cachedMs) }
                awaitStage(name) { native.state.value.let { it.error == null && it.playing && !it.waitingForBuffer && it.phase != "正在跳转" && it.positionMs in cachedMs..(cachedMs + 3000) } && rendered(native) > before && frame(view) }
            }
            cachedSeek("cached-seek-decodes-new-frame")
            main { previousTasks = taskIds.toSet(); seekingMissing = true; gap.set(null); native.seek(missingMs) }
            awaitStage("confirmed-missing-span-waits-recoverably") {
                val observed = gap.get()
                native.state.value.let { it.error == null && it.waitingForBuffer } && observed != null &&
                    observed.offset in prefixBytes until record.expectedSize && !cached(record, observed.offset)
            }
            val missingOffset = requireNotNull(gap.get()).offset
            main { seekingMissing = false }
            cachedSeek("return-to-cached-region-decodes-new-frame")
            check(database.downloads().get(record.id) == record)
            unchanged(manifest); check(database.profiles().activeSession() == previousSession(manifest))
            manifest.put("phase", "verified"); save(file, manifest); stage = "verified"
            report(manifest, JSONObject().put("networkAllowed", false).put("preparePid", manifest.getInt("preparePid"))
                .put("cachedSeekMs", cachedMs).put("missingCandidateMs", missingMs).put("confirmedMissingOffset", missingOffset)
                .put("renderedFrames", rendered(native)).put("firstFrame", true).put("positionMs", native.state.value.positionMs))
        } catch (failure: Throwable) {
            manifest.put("phase", "verify-failed:$stage"); runCatching { save(file, manifest) }
            report(manifest, JSONObject().put("failureClass", failure.javaClass.simpleName))
            throw AssertionError("Owned real prefix verify failed at $stage (${failure.javaClass.simpleName}); recovery manifest retained")
        } finally { withContext(NonCancellable) { main { player?.release(); if (mounted) (view.parent as? android.view.ViewGroup)?.removeView(view) } } }
    }

    @ExternalNetworkAcceptance
    @Test fun cleanupOwnedRealPrefix(): Unit = runBlocking {
        val config = configuration("cleanup"); val (file, manifest) = load(config)
        stage = "cleanup-ownership"
        val profile = store.profile(manifest.getString("profileId"))
        if (profile == null) {
            check((!manifest.optBoolean("profileCreated") && !manifest.has("record")) || manifest.optBoolean("ownedProfileDeleted")) { "Owned profile is missing; retain recovery manifest for review" }
            unchanged(manifest); check(database.profiles().activeSession() == previousSession(manifest))
            check(database.downloads().get(manifest.getString("ownedId")) == null)
            stage = "cleaned"; report(manifest, JSONObject().put("originalDownloadsUnchanged", true)); check(file.delete())
            return@runBlocking
        }
        check(profile.name == manifest.getString("marker") && profile.id == "owned-real-partial-${manifest.getString("ownedId")}")
        unchanged(manifest)
        val previous = previousSession(manifest); val current = database.profiles().activeSession()
        val record = manifest.optJSONObject("record")?.let { row(manifest) }
        check(current == previous || current == null || current.accountKey == record?.accountKey) { "Active session changed independently; cleanup refused" }
        if (previous != null) check(database.profiles().account(previous.accountKey) != null) { "Original account disappeared; cleanup refused" }
        try {
            record?.let {
                check(it.id == manifest.getString("ownedId") && it.profileId == profile.id &&
                    it.name == "fileway-owned-real-prefix-${it.id}.${manifest.getString("container")}" && it.sourceLabel == manifest.getString("marker"))
                val existing = database.downloads().get(it.id)
                check(existing == null || existing == it) { "Owned row changed; cleanup refused" }
                if (it.localUri.isNotEmpty() && !manifest.optBoolean("ownedFileDeleted")) {
                    val uri = android.net.Uri.parse(it.localUri)
                    check(uri.scheme == "content" && uri.authority == "media") { "Owned target is not the allocated MediaStore file" }
                    context.contentResolver.query(uri, arrayOf(android.provider.MediaStore.MediaColumns.DISPLAY_NAME,
                        android.provider.MediaStore.MediaColumns.OWNER_PACKAGE_NAME), null, null, null)?.use { cursor ->
                        check(cursor.moveToFirst() && cursor.getString(0) == it.name && cursor.getString(1) == context.packageName && !cursor.moveToNext()) { "Owned file name/package identity differs; cleanup refused" }
                    } ?: error("Owned file identity is unavailable; cleanup refused")
                    stage = "cleanup-owned-file"; check(DownloadTarget(context).delete(it)) { "Owned file could not be removed" }
                    manifest.put("ownedFileDeleted", true); save(file, manifest)
                }
                stage = "cleanup-owned-index-and-row"; DownloadIndex.get(context).remove(it)
                if (existing != null) check(database.downloads().removeRecord(it.id) == 1)
            }
            if (current != null && current != previous) database.profiles().releaseActiveSession(current.accountKey, current.owner)
            if (previous != null) database.profiles().saveActiveSession(previous)
            stage = "cleanup-owned-profile"; store.remove(profile); manifest.put("ownedProfileDeleted", true); save(file, manifest)
            unchanged(manifest); check(database.profiles().activeSession() == previous)
            stage = "cleaned"; report(manifest, JSONObject().put("originalDownloadsUnchanged", true).put("originalSessionRestored", true))
            check(file.delete()) { "Owned manifest could not be removed" }
        } catch (failure: Throwable) {
            manifest.put("cleanupFailureStage", stage); runCatching { save(file, manifest) }
            report(manifest, JSONObject().put("failureClass", failure.javaClass.simpleName))
            throw AssertionError("Owned real prefix cleanup failed at $stage (${failure.javaClass.simpleName}); recovery manifest retained")
        }
    }
}
