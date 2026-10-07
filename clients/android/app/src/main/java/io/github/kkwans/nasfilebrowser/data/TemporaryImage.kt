package io.github.kkwans.nasfilebrowser.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resumeWithException

class ImageWorkingLimitException : IOException("图片超过临时查看限额，请增大图片缓存后重试")

class TemporaryImage internal constructor(val file: File, private val dispose: (File) -> Unit) : AutoCloseable {
    private val closed = AtomicBoolean()
    override fun close() { if (closed.compareAndSet(false, true)) dispose(file) }
}

/** A cancellable working file for region decoding when persistent image caching is off. */
object TemporaryImages {
    private const val MAX_WORKING_BYTES = 256L * 1024 * 1024
    private val active = mutableSetOf<File>()
    private val client = OkHttpClient.Builder().cache(null).followRedirects(false).followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()

    internal fun cleanupAbandoned(context: Context) = synchronized(active) {
        File(context.cacheDir, "image-viewer-work").listFiles()
            ?.filter { it.isFile && it.name.startsWith("viewer-") && it !in active }?.forEach { it.delete() }
        Unit
    }

    suspend fun read(context: Context, lease: PreviewLease, expectedBytes: Long): TemporaryImage = withContext(Dispatchers.IO) {
        val uri = Uri.parse(lease.url)
        require(uri.scheme == "http" && uri.host == "127.0.0.1") { "图片来源已失效" }
        if (expectedBytes > MAX_WORKING_BYTES) throw ImageWorkingLimitException()
        val directory = File(context.cacheDir, "image-viewer-work")
        val asset = synchronized(active) {
            check(directory.isDirectory || directory.mkdirs()) { "无法准备图片查看空间" }
            // Only this module creates these files. Remove interrupted-process leftovers,
            // while preserving every working file still owned by a live viewer.
            cleanupAbandoned(context)
            val file = File.createTempFile("viewer-", ".image", directory)
            active.add(file)
            TemporaryImage(file) { owned -> synchronized(active) { active.remove(owned); owned.delete(); Unit } }
        }
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(Request.Builder().url(lease.url).build())
            continuation.invokeOnCancellation { call.cancel(); asset.close() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    asset.close()
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        response.use { value ->
                            if (!value.isSuccessful) throw ServiceException(value.code, "图片读取失败")
                            val body = value.body ?: throw IOException("图片内容为空")
                            if (body.contentLength() > MAX_WORKING_BYTES) throw ImageWorkingLimitException()
                            var bytes = 0L
                            val stream = synchronized(active) { if (continuation.isActive) asset.file.outputStream() else null }
                            if (stream == null) { asset.close(); return }
                            stream.use { output -> body.byteStream().use { input ->
                                val buffer = ByteArray(64 * 1024)
                                while (continuation.isActive) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    synchronized(active) {
                                        if (active.sumOf { it.length() } + count > MAX_WORKING_BYTES) throw ImageWorkingLimitException()
                                        output.write(buffer, 0, count)
                                    }
                                    bytes += count
                                }
                            } }
                            if (!continuation.isActive) { asset.close(); return }
                            if (bytes == 0L || (expectedBytes > 0 && bytes != expectedBytes)) throw IOException("图片读取不完整，请重试")
                        }
                        if (continuation.isActive) continuation.resume(asset) { _, abandoned, _ -> abandoned.close() } else asset.close()
                    } catch (error: Exception) {
                        asset.close()
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                }
            })
        }
    }
}
