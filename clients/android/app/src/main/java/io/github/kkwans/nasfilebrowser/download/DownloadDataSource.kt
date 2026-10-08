package io.github.kkwans.nasfilebrowser.download

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.*
import io.github.kkwans.nasfilebrowser.data.ClientDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.FileInputStream
import java.io.IOException

/** Read the saved prefix first; HTTP Range supplies a seek beyond that prefix. */
@androidx.annotation.OptIn(UnstableApi::class)
class DownloadDataSource(private val context: Context, private val database: ClientDatabase = ClientDatabase.get(context)) : BaseDataSource(true) {
    private var spec: DataSpec? = null
    private var record: DownloadRecord? = null
    private var local: android.os.ParcelFileDescriptor? = null
    private var file: FileInputStream? = null
    private var network: HttpDataSource? = null
    private var access: DownloadSource? = null
    private var position = 0L
    private var remaining = 0L
    private var opened = false
    private var firstRead = false
    private fun trace(stage: String) {
        if (io.github.kkwans.nasfilebrowser.BuildConfig.DEBUG) android.util.Log.d("FilewayDownloadRead",
            "${android.os.SystemClock.elapsedRealtime()} reader=${System.identityHashCode(this)} $stage")
    }
    override fun open(dataSpec: DataSpec): Long {
        trace("open position=${dataSpec.position} length=${dataSpec.length}")
        transferInitializing(dataSpec)
        val id = dataSpec.uri.host ?: throw IOException("下载标识无效")
        val item = runBlocking(Dispatchers.IO) { database.downloads().get(id) } ?: throw IOException("下载记录不存在")
        trace("record prefix=${item.downloaded} size=${item.expectedSize} status=${item.status}")
        check(item.localUri.isNotEmpty()) { "文件仍在准备，请稍后打开" }
        if (dataSpec.position > item.expectedSize) throw DataSourceException( androidx.media3.common.PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        spec = dataSpec; record = item; position = dataSpec.position
        remaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) item.expectedSize - position else minOf(dataSpec.length, item.expectedSize - position)
        try {
            trace("local-open-start")
            local = context.contentResolver.openFileDescriptor(Uri.parse(item.localUri), "r")
            trace("local-open-ready")
            file = local?.let { android.os.ParcelFileDescriptor.AutoCloseInputStream(it) }
            file?.channel?.position(position)
        } catch (_: IOException) { runCatching { file?.close() }; file = null; runCatching { local?.close() }; local = null }
        opened = true; transferStarted(dataSpec)
        firstRead = true
        trace("opened remaining=$remaining local=${file != null}")
        return remaining
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        var item = record ?: throw IOException("下载读取尚未打开")
        val allowed = minOf(length.toLong(), remaining).toInt()
        var prefix = if (item.complete) item.expectedSize else DownloadRuntime.get(context).prefix(item)
        if (!item.complete && position >= prefix) {
            val latest = runBlocking(Dispatchers.IO) { database.downloads().get(item.id) }
            if (latest != null) { item = latest; record = latest; prefix = if (latest.complete) latest.expectedSize else DownloadRuntime.get(context).prefix(latest) }
        }
        val input = file
        if (firstRead) { firstRead = false; trace("first-read position=$position prefix=$prefix local=${input != null}") }
        if (input != null && position < prefix && network == null) {
            val count = input.read(buffer, offset, minOf(allowed.toLong(), prefix - position).toInt())
            if (count > 0) { position += count; remaining -= count; bytesTransferred(count); return count }
        }
        if (item.complete) throw IOException("本机文件未完整读取，请检查文件是否被移动或修改")
        if (network == null) {
            trace("remote-source-start position=$position remaining=$remaining")
            access = runBlocking(Dispatchers.IO) { DownloadRuntime.get(context).source(item) }
            trace("remote-source-ready")
            network = DefaultHttpDataSource.Factory().setConnectTimeoutMs(15_000).setReadTimeoutMs(30_000).createDataSource().also { source ->
                source.open(DataSpec.Builder().setUri(access!!.url).setPosition(position).setLength(remaining).build())
                trace("remote-open-ready")
            }
        }
        val count = network!!.read(buffer, offset, allowed)
        if (count == C.RESULT_END_OF_INPUT) throw IOException("源文件片段尚未完整接收，请重试播放")
        position += count; remaining -= count; bytesTransferred(count); return count
    }
    override fun getUri(): Uri? = spec?.uri
    override fun close() {
        try { network?.close() }
        finally {
            network = null
            try { file?.close() } finally { file = null; runCatching { local?.close() }; local = null }
            try { access?.let { runBlocking(Dispatchers.IO) { runCatching { it.close() } } } } finally {
                access = null; record = null; spec = null
                if (opened) { opened = false; transferEnded() }
            }
        }
    }
    class Factory(private val context: Context) : DataSource.Factory {
        // External captions and other auxiliary requests retain the standard
        // content/file/HTTP handlers; only our download URI uses prefix reads.
        override fun createDataSource(): DataSource = object : DataSource {
            private var delegate: DataSource? = null
            private val listeners = mutableListOf<TransferListener>()
            override fun addTransferListener(listener: TransferListener) { listeners.add(listener); delegate?.addTransferListener(listener) }
            override fun open(dataSpec: DataSpec): Long {
                check(delegate == null)
                val source = if (dataSpec.uri.scheme == "fileway-download") DownloadDataSource(context.applicationContext) else DefaultDataSource.Factory(context.applicationContext).createDataSource()
                delegate = source; listeners.forEach(source::addTransferListener)
                return source.open(dataSpec)
            }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = checkNotNull(delegate).read(buffer, offset, length)
            override fun getUri(): Uri? = delegate?.uri
            override fun getResponseHeaders(): Map<String, List<String>> = delegate?.responseHeaders.orEmpty()
            override fun close() { try { delegate?.close() } finally { delegate = null } }
        }
    }
}
