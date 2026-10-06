package io.github.kkwans.nasfilebrowser.data

import android.content.Context
import android.content.ComponentName
import android.app.job.JobInfo
import android.app.job.JobScheduler
import androidx.compose.runtime.mutableStateOf
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import io.github.kkwans.nasfilebrowser.core.NativeTransport
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.Path.Companion.toOkioPath
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.security.MessageDigest

const val CACHE_MB = 1024L * 1024
enum class ImageQuality(val label: String, val pixels: Int?) { ORIGINAL("原图", null), HIGH("高", 2560), MEDIUM("中", 1080), LOW("低", 512) }
data class CacheSettings(val thumbnailMB: Long = 500, val playbackMB: Long = 1024, val cleanupHours: Int = 24,
    val imageMB: Long = 1024, val imageQuality: ImageQuality = ImageQuality.ORIGINAL) {
    fun validate() { require(thumbnailMB in 0..10240 && playbackMB in 0..10240 && imageMB in 0..51200 && cleanupHours in 1..720) }
}
data class CacheState(val settings: CacheSettings = CacheSettings(), val loading: Boolean = true, val busy: Boolean = false,
    val revision: Int = 0, val thumbnails: Long = 0, val playback: Long = 0, val images: Long = 0,
    val protected: Int = 0, val error: String? = null)
data class CachedImage(val key: String, val account: String, val name: String, val path: String, val wirePath: String,
    val modified: String, val size: Long, val bytes: Long)

internal fun readCacheSettings(context: Context): CacheSettings {
    val p = context.getSharedPreferences("cache_settings", Context.MODE_PRIVATE)
    return CacheSettings(p.getLong("thumbnail_mb", 500), p.getLong("playback_mb", 1024), p.getInt("cleanup_hours", 24),
        p.getLong("image_mb", 1024), runCatching { ImageQuality.valueOf(p.getString("image_quality", "ORIGINAL")!!) }.getOrDefault(ImageQuality.ORIGINAL))
        .also { it.validate() }
}
internal fun cacheCommand(context: Context, settings: CacheSettings) = JSONObject().put("op", "cache_configure")
    .put("cacheConfig", JSONObject().put("directory", File(context.filesDir, "media-cache").absolutePath)
        .put("maxBytes", settings.playbackMB * CACHE_MB).put("hours", settings.cleanupHours))
internal fun scheduleCacheCleanup(context: Context, hours: Int) {
    val scheduler = context.getSystemService(JobScheduler::class.java)
    if (scheduler.getPendingJob(73003)?.intervalMillis == hours * 3_600_000L) return
    val job = JobInfo.Builder(73003, ComponentName(context, CacheCleanupService::class.java))
        .setPeriodic(hours * 3_600_000L).setPersisted(true).build()
    check(scheduler.schedule(job) == JobScheduler.RESULT_SUCCESS) { "无法安排缓存自动清理" }
}

/** Private, persistent LRU disks; authenticated leases remain required to display any resource. */
class CacheController(private val context: Context, private val scope: CoroutineScope) {
    private val mutable = MutableStateFlow(CacheState())
    val state = mutable.asStateFlow()
    private val lock = Mutex()
    private var ready = CompletableDeferred<Unit>()
    private var thumbnailDisk: DiskCache? = null
    private var imageDisk: DiskCache? = null
    val thumbnailLoader = mutableStateOf(loader(null, 16))
    val imageLoader = mutableStateOf(loader(null, 32))
    private val index = File(context.filesDir, "image-cache-index.json")
    init { initialize() }
    fun retry() { if (!mutable.value.loading && !mutable.value.busy) { ready = CompletableDeferred(); mutable.value = mutable.value.copy(loading = true, error = null); initialize() } }
    private fun initialize() {
        scope.launch {
            try {
                val settings = withContext(Dispatchers.IO) { readCacheSettings(context) }
                NativeTransport.call(cacheCommand(context, settings))
                withContext(Dispatchers.IO) { resize(settings); scheduleCacheCleanup(context, settings.cleanupHours) }
                mutable.value = CacheState(settings, loading = false, revision = mutable.value.revision + 1)
                ready.complete(Unit)
                refresh()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                mutable.value = mutable.value.copy(loading = false, error = "缓存初始化失败，请重试")
                ready.completeExceptionally(e)
            }
        }
    }
    suspend fun awaitReady() = ready.await()
    private fun loader(disk: DiskCache?, mb: Int) = ImageLoader.Builder(context.applicationContext)
        .memoryCache { MemoryCache.Builder().maxSizeBytes(mb * CACHE_MB).build() }.diskCache(disk).build()
    private fun disk(name: String, mb: Long): DiskCache? = if (mb == 0L) null else DiskCache.Builder()
        .directory(File(context.filesDir, name).toOkioPath()).maxSizeBytes(mb * CACHE_MB).build()
    private fun resize(settings: CacheSettings) {
        thumbnailLoader.value.shutdown(); imageLoader.value.shutdown()
        thumbnailDisk?.shutdown(); imageDisk?.shutdown()
        if (settings.thumbnailMB == 0L) File(context.filesDir, "thumbnail-cache").deleteRecursively()
        if (settings.imageMB == 0L) File(context.filesDir, "image-cache").deleteRecursively()
        thumbnailDisk = disk("thumbnail-cache", settings.thumbnailMB)
        imageDisk = disk("image-cache", settings.imageMB)
        thumbnailLoader.value = loader(thumbnailDisk, 16); imageLoader.value = loader(imageDisk, 32)
    }
    fun save(settings: CacheSettings) { scope.launch { edit(settings) } }
    private suspend fun edit(settings: CacheSettings) = lock.withLock {
        if (mutable.value.loading || mutable.value.busy) return@withLock
        val old = mutable.value.settings
        mutable.value = mutable.value.copy(busy = true, error = null)
        try {
            settings.validate(); awaitReady()
            NativeTransport.call(cacheCommand(context, settings))
            withContext(Dispatchers.IO) {
                val ok = context.getSharedPreferences("cache_settings", Context.MODE_PRIVATE).edit()
                    .putLong("thumbnail_mb", settings.thumbnailMB).putLong("playback_mb", settings.playbackMB)
                    .putInt("cleanup_hours", settings.cleanupHours).putLong("image_mb", settings.imageMB)
                    .putString("image_quality", settings.imageQuality.name).commit()
                check(ok) { "cannot save cache settings" }
                if (old.thumbnailMB != settings.thumbnailMB || old.imageMB != settings.imageMB) resize(settings)
                scheduleCacheCleanup(context, settings.cleanupHours)
            }
            mutable.value = mutable.value.copy(settings = settings, busy = false, revision = mutable.value.revision + 1)
            refreshLocked()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            runCatching {
                NativeTransport.call(cacheCommand(context, old))
                withContext(Dispatchers.IO) {
                    context.getSharedPreferences("cache_settings", Context.MODE_PRIVATE).edit()
                        .putLong("thumbnail_mb", old.thumbnailMB).putLong("playback_mb", old.playbackMB)
                        .putInt("cleanup_hours", old.cleanupHours).putLong("image_mb", old.imageMB).putString("image_quality", old.imageQuality.name).commit()
                    if (old.thumbnailMB != settings.thumbnailMB || old.imageMB != settings.imageMB) resize(old)
                    scheduleCacheCleanup(context, old.cleanupHours)
                }
            }
            mutable.value = mutable.value.copy(busy = false, error = "缓存设置未能应用，请重试")
        }
    }
    suspend fun refresh() = lock.withLock { if (!mutable.value.loading) refreshLocked() }
    private suspend fun refreshLocked() {
        try {
            val stats = NativeTransport.call(JSONObject().put("op", "cache_cleanup")) as JSONObject
            mutable.value = mutable.value.copy(thumbnails = thumbnailDisk?.size ?: 0, images = imageDisk?.size ?: 0,
                playback = stats.optLong("bytes"), protected = stats.optInt("protected"))
        } catch (e: Exception) { if (e is CancellationException) throw e; mutable.value = mutable.value.copy(error = "无法读取播放缓存，请重试") }
    }
    suspend fun clear(kind: String) = lock.withLock {
        awaitReady(); mutable.value = mutable.value.copy(busy = true, error = null)
        try {
            withContext(Dispatchers.IO) {
                when (kind) {
                    "thumbnail" -> { thumbnailLoader.value.memoryCache?.clear(); thumbnailDisk?.clear() }
                    "image" -> { imageLoader.value.memoryCache?.clear(); imageDisk?.clear(); if (index.exists()) check(index.delete()) }
                }
            }
            if (kind == "playback") NativeTransport.call(JSONObject().put("op", "cache_cleanup").put("clear", true))
            mutable.value = mutable.value.copy(busy = false, revision = mutable.value.revision + 1)
            refreshLocked()
        } catch (e: Exception) { if (e is CancellationException) throw e; mutable.value = mutable.value.copy(busy = false, error = "缓存未能清理，请重试"); throw e }
    }
    fun key(account: String, path: String, identity: String): String = MessageDigest.getInstance("SHA-256")
        .digest("$account\u0000$path\u0000$identity".toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun readIndex(): MutableList<CachedImage> {
        if (!index.exists()) return mutableListOf()
        val a = JSONArray(index.readText())
        return (0 until a.length()).map { i -> a.getJSONObject(i).let { CachedImage(it.getString("key"), it.getString("account"), it.getString("name"),
            it.getString("path"), it.getString("wirePath"), it.getString("modified"), it.getLong("size"), it.optLong("bytes")) } }.toMutableList()
    }
    private fun writeIndex(items: List<CachedImage>) {
        val a = JSONArray(); items.forEach { a.put(JSONObject().put("key", it.key).put("account", it.account).put("name", it.name).put("path", it.path)
            .put("wirePath", it.wirePath).put("modified", it.modified).put("size", it.size).put("bytes", it.bytes)) }
        val temp = File(index.parentFile, "image-cache-index.tmp"); temp.writeText(a.toString()); check(temp.renameTo(index))
    }
    suspend fun recordImage(image: CachedImage) = lock.withLock { withContext(Dispatchers.IO) {
        val snapshot = imageDisk?.openSnapshot(image.key) ?: return@withContext
        val bytes = try { snapshot.data.toFile().length() } finally { snapshot.close() }
        val items = readIndex().filter { it.key != image.key && exists(it.key) }.toMutableList(); items.add(image.copy(bytes = bytes)); writeIndex(items)
    } }
    private fun exists(key: String): Boolean { val snapshot = imageDisk?.openSnapshot(key) ?: return false; snapshot.close(); return true }
    suspend fun cachedImages(account: String): List<CachedImage> = lock.withLock { withContext(Dispatchers.IO) { readIndex().filter { it.account == account && exists(it.key) } } }
    suspend fun removeImage(key: String, account: String) = lock.withLock { withContext(Dispatchers.IO) {
        val items = readIndex(); check(items.any { it.key == key && it.account == account })
        imageDisk?.remove(key); imageLoader.value.memoryCache?.clear(); writeIndex(items.filter { it.key != key })
        mutable.value = mutable.value.copy(images = imageDisk?.size ?: 0)
    } }
    fun close() { thumbnailLoader.value.shutdown(); imageLoader.value.shutdown(); thumbnailDisk?.shutdown(); imageDisk?.shutdown() }
}
