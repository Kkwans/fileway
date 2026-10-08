package io.github.kkwans.nasfilebrowser.download

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.AtomicFile
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.*
import androidx.media3.datasource.cache.*
import androidx.media3.extractor.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** Persist extractor-read metadata, not a second copy of the downloaded movie. */
@androidx.annotation.OptIn(UnstableApi::class)
internal class DownloadIndex private constructor(private val context: Context) {
    data class Point(val timeUs: Long, val requiredBytes: Long)
    data class Info(val durationUs: Long, val points: List<Point>) {
        fun availableMs(prefix: Long): Long = (points.lastOrNull { it.requiredBytes <= prefix }?.timeUs ?: 0) / 1000
    }
    private val root = File(context.filesDir, "download-playback-index")
    // SimpleCache owns this directory exclusively. Maps live outside it.
    private val cache = SimpleCache(File(root, "data"), NoOpCacheEvictor(), StandaloneDatabaseProvider(context))
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val known = ConcurrentHashMap<String, Info>()
    private fun mapFile(record: DownloadRecord): File {
        require(record.id.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        return File(File(root, "maps"), "${record.id}.json")
    }
    fun info(record: DownloadRecord): Info? = known[record.id] ?: runCatching {
        val json = JSONObject(AtomicFile(mapFile(record)).readFully().decodeToString())
        check(json.getString("identity") == record.identity && json.getInt("version") == 2)
        val rows = json.getJSONArray("points")
        Info(json.getLong("durationUs"), (0 until rows.length()).map { rows.getJSONArray(it).let { row -> Point(row.getLong(0), row.getLong(1)) } })
            .also { known[record.id] = it }
    }.getOrNull()

    fun reader(record: DownloadRecord, networkAllowed: Boolean = true, write: Boolean = false): DataSource {
        val factory = CacheDataSource.Factory().setCache(cache).setCacheKeyFactory { record.id }
        if (networkAllowed) factory.setUpstreamDataSourceFactory { Remote(context, record) }
        else factory.setUpstreamDataSourceFactory { object : DataSource {
            override fun open(spec: DataSpec): Long = throw DownloadPendingException()
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = throw DownloadPendingException()
            override fun getUri(): Uri? = null
            override fun addTransferListener(listener: TransferListener) = Unit
            override fun close() = Unit
        } }
        if (!write) factory.setCacheWriteDataSinkFactory(null)
        return factory.createDataSource()
    }

    suspend fun prepare(record: DownloadRecord): Info = locks.getOrPut(record.id) { Mutex() }.withLock {
        info(record)?.let { return@withLock it }
        withContext(Dispatchers.IO) {
            var data: DataSource? = null
            var extractor: Extractor? = null
            var byteCount = 0L
            var jumps = 0
            val started = SystemClock.elapsedRealtime()
            val preparation = currentCoroutineContext()
            val uri = Uri.Builder().scheme("fileway-download").authority(record.id).appendPath(record.name).build()
            fun input(position: Long): DefaultExtractorInput {
                data?.close()
                val source = DownloadDataSource(context, cacheMetadataWrites = true)
                data = object : DataSource by source {
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        preparation.ensureActive()
                        if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException()
                        check(byteCount < 64L * 1024 * 1024 && SystemClock.elapsedRealtime() - started < 30_000) { "启动索引超出准备预算，完整下载仍可继续" }
                        return source.read(buffer, offset, minOf(length.toLong(), 64L * 1024 * 1024 - byteCount).toInt()).also { if (it > 0) byteCount += it }
                    }
                }
                data!!.open(DataSpec.Builder().setUri(uri).setPosition(position).setLength(record.expectedSize - position).setKey(record.id).build())
                return DefaultExtractorInput(data!!, position, record.expectedSize)
            }
            try {
                var stream = input(0)
                val candidates = DefaultExtractorsFactory().createExtractors(uri, emptyMap())
                extractor = candidates.firstOrNull { candidate ->
                    try { candidate.sniff(stream) } catch (_: java.io.EOFException) { false }
                    finally { stream.resetPeekPosition() }
                } ?: error("此格式暂未提供离线启动索引")
                candidates.filter { it !== extractor }.forEach { it.release() }
                var map: SeekMap? = null
                var endedTracks = false
                val tracks = mutableSetOf<Int>()
                extractor!!.init(object : ExtractorOutput {
                    override fun track(id: Int, type: Int): TrackOutput {
                        if (type == C.TRACK_TYPE_AUDIO || type == C.TRACK_TYPE_VIDEO) tracks.add(id)
                        return DiscardingTrackOutput()
                    }
                    override fun endTracks() { endedTracks = true }
                    override fun seekMap(seekMap: SeekMap) { map = seekMap }
                })
                val seek = PositionHolder()
                while (map == null || !endedTracks) {
                    currentCoroutineContext().ensureActive()
                    when (extractor!!.read(stream, seek)) {
                        Extractor.RESULT_END_OF_INPUT -> break
                        Extractor.RESULT_SEEK -> {
                            check(++jumps <= 128 && seek.position in 0 until record.expectedSize) { "启动索引跳转无效" }
                            if (map == null || !endedTracks) stream = input(seek.position)
                        }
                    }
                }
                val index = requireNotNull(map) { "未获得媒体索引" }
                check(endedTracks)
                data?.close(); data = null // Commit cache spans before publishing readiness.
                val points = mutableListOf<Point>()
                var required = 0L
                if (index.isSeekable && index.durationUs > 0 && tracks.size in 1..32 && index is TrackAwareSeekMap) {
                    val step = maxOf(1_000_000L, (index.durationUs + 19_999) / 20_000)
                    val times = generateSequence(0L) { it + step }.takeWhile { it < index.durationUs }.toList()
                    val samples = tracks.map { track -> times.map { index.getSeekPoints(it, track) } }
                    // A point locates a block's START, not its complete payload.
                    // Require the next distinct block boundary for every A/V track.
                    val monotonic = samples.all { rows -> rows.all { it.first.position in 0 until record.expectedSize } &&
                        rows.zipWithNext().all { (a, b) -> a.first.position <= b.first.position } }
                    if (monotonic) {
                        val ends = samples.map { rows ->
                            val offsets = LongArray(rows.size)
                            var nextStart = record.expectedSize; var nextEnd = record.expectedSize
                            for (i in rows.indices.reversed()) {
                                val start = rows[i].first.position
                                if (start < nextStart) { nextEnd = nextStart; nextStart = start }
                                offsets[i] = maxOf(nextEnd, rows[i].second.position)
                            }
                            offsets
                        }
                        for (i in times.indices) {
                            val boundedTime = minOf(times[i], samples.minOf { it[i].first.timeUs })
                            required = maxOf(required, ends.maxOf { it[i] })
                            if (boundedTime >= (points.lastOrNull()?.timeUs ?: 0) && required in 0..record.expectedSize)
                                points.add(Point(boundedTime, required))
                        }
                    }
                }
                val result = Info(index.durationUs, points)
                val file = mapFile(record); check(file.parentFile!!.mkdirs() || file.parentFile!!.isDirectory)
                val atomic = AtomicFile(file)
                val output = atomic.startWrite()
                try {
                    val json = JSONObject().put("version", 2).put("identity", record.identity).put("durationUs", result.durationUs)
                        .put("points", JSONArray(result.points.map { JSONArray(listOf(it.timeUs, it.requiredBytes)) }))
                    output.write(json.toString().toByteArray()); atomic.finishWrite(output)
                } catch (failure: Throwable) { atomic.failWrite(output); throw failure }
                known[record.id] = result
                result
            } catch (failure: Throwable) {
                runCatching { data?.close() }; data = null
                runCatching { remove(record) } // Do not accumulate failed preparation fragments.
                throw failure
            } finally { try { data?.close() } finally { extractor?.release() } }
        }
    }

    fun remove(record: DownloadRecord) {
        cache.removeResource(record.id)
        AtomicFile(mapFile(record)).delete()
        known.remove(record.id)
    }
    private class Remote(private val context: Context, private val record: DownloadRecord) : DataSource {
        private var access: DownloadSource? = null
        private val http = DefaultHttpDataSource.Factory().setConnectTimeoutMs(10_000).setReadTimeoutMs(15_000).createDataSource()
        private var uri: Uri? = null
        override fun addTransferListener(listener: TransferListener) = http.addTransferListener(listener)
        override fun open(spec: DataSpec): Long {
            uri = spec.uri
            try {
                access = runBlocking(Dispatchers.IO) { DownloadRuntime.get(context).source(record) }
                return http.open(spec.buildUpon().setUri(access!!.url).build())
            } catch (failure: io.github.kkwans.nasfilebrowser.core.TransportException) {
                throw DownloadPendingException(failure)
            } catch (failure: HttpDataSource.HttpDataSourceException) {
                if (failure is HttpDataSource.InvalidResponseCodeException && failure.responseCode in 400..499) throw failure
                throw DownloadPendingException(failure)
            }
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = try { http.read(buffer, offset, length) }
            catch (failure: HttpDataSource.HttpDataSourceException) { throw DownloadPendingException(failure) }
        override fun getUri(): Uri? = uri
        override fun close() {
            try { http.close() }
            finally { val old = access; access = null; old?.let { runBlocking(Dispatchers.IO) { it.close() } } }
        }
    }
    companion object {
        @Volatile private var instance: DownloadIndex? = null
        fun get(context: Context): DownloadIndex = instance ?: synchronized(this) {
            instance ?: DownloadIndex(context.applicationContext).also { instance = it }
        }
    }
}
