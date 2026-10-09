package io.github.kkwans.nasfilebrowser.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.*
import okhttp3.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resumeWithException

internal class DocumentWorkingFile(val file: File, private val dispose: (File) -> Unit) : AutoCloseable {
    private val closed = AtomicBoolean()
    override fun close() { if (closed.compareAndSet(false, true)) dispose(file) }
}

/** Ephemeral session workspace, independent of persistent image-cache preferences. */
internal object DocumentWorkingFiles {
    private val active = mutableSetOf<File>()
    fun create(context: Context, expected: Long): DocumentWorkingFile = synchronized(active) {
        require(expected in 1..DOCUMENT_PDF_LIMIT) { "PDF 为空或超过 64 MiB 查看上限，请下载后打开" }
        val directory = File(context.cacheDir, "document-preview-work")
        check(directory.isDirectory || directory.mkdirs()) { "无法准备文档查看空间，请下载后打开" }
        directory.listFiles()?.filter { it.name.startsWith("document-") && it.isFile && it !in active }?.forEach { it.delete() }
        check(directory.usableSpace >= expected + 2 * CACHE_MB) { "本机可用空间不足，请下载到其他目录后打开" }
        val file = File.createTempFile("document-", ".pdf", directory)
        active.add(file)
        DocumentWorkingFile(file) { owned -> synchronized(active) { active.remove(owned); owned.delete(); Unit } }
    }
    fun write(working: DocumentWorkingFile, output: OutputStream, buffer: ByteArray, count: Int) = synchronized(active) {
        if (working.file !in active) throw CancellationException("Document working file closed")
        check(active.sumOf { it.length() } + count <= DOCUMENT_PDF_LIMIT) { "文档临时查看空间超过 64 MiB，请关闭其他文档或下载后打开" }
        output.write(buffer, 0, count)
    }
}

internal interface DocumentPreviewReader {
    suspend fun text(lease: PreviewLease, expected: Long): ByteArray
    suspend fun pdf(context: Context, lease: PreviewLease, expected: Long): DocumentWorkingFile
}

/** Reuses the existing OkHttp localhost-capability pattern used by TemporaryImages. */
internal object NativeDocumentReader : DocumentPreviewReader {
    private val client = OkHttpClient.Builder().cache(null).followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    private suspend fun <T> read(lease: PreviewLease, expected: Long, limit: Long, working: DocumentWorkingFile?, finish: (ByteArray?) -> T): T {
        require(expected in 0..limit) { "文件超过查看上限，请下载后打开" }
        val uri = Uri.parse(lease.url)
        require(uri.scheme == "http" && uri.host == "127.0.0.1" && uri.port > 0 && uri.userInfo == null &&
            uri.query == null && uri.fragment == null && uri.path?.startsWith("/stream/") == true) { "文档来源已失效，请重新打开" }
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(Request.Builder().url(lease.url).build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    working?.close()
                    if (continuation.isActive) continuation.resumeWithException(IOException("文档读取中断，请重试或下载后打开", e))
                }
                override fun onResponse(call: Call, response: Response) {
                    var delivered = false
                    try {
                        var bytes: ByteArray? = null
                        response.use { value ->
                            check(value.code == 200) { if (value.code in setOf(401, 403)) "当前账号没有读取权限，请重新登录或检查权限" else "文档读取失败（${value.code}），请重试" }
                            val body = value.body ?: error("文档内容不可用")
                            check(body.contentLength() <= limit) { "文件超过查看上限，请下载后打开" }
                            check(body.contentLength() < 0 || body.contentLength() == expected) { "文件大小已变化，请重新打开" }
                            val memory = if (working == null) ByteArrayOutputStream() else null
                            val output = working?.file?.outputStream() ?: memory!!
                            var total = 0L
                            output.use { sink -> body.byteStream().use { source ->
                                val buffer = ByteArray(32 * 1024)
                                while (continuation.isActive) {
                                    val count = source.read(buffer)
                                    if (count < 0) break
                                    total += count
                                    check(total <= limit && total <= expected) { "文件超出预期大小，请重新打开或下载后查看" }
                                    if (working != null) DocumentWorkingFiles.write(working, sink, buffer, count)
                                    else sink.write(buffer, 0, count)
                                }
                            } }
                            if (!continuation.isActive) return
                            check(total == expected) { "文档读取不完整，请重试" }
                            bytes = memory?.toByteArray()
                        }
                        val result = finish(bytes)
                        delivered = true
                        continuation.resume(result) { _, _, _ -> working?.close() }
                    } catch (error: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    } finally { if (!delivered) working?.close() }
                }
            })
        }
    }
    override suspend fun text(lease: PreviewLease, expected: Long): ByteArray = read(lease, expected, DOCUMENT_TEXT_LIMIT, null) { it!! }
    override suspend fun pdf(context: Context, lease: PreviewLease, expected: Long): DocumentWorkingFile {
        var working: DocumentWorkingFile? = null
        try {
            withContext(Dispatchers.IO) { working = DocumentWorkingFiles.create(context, expected) }
            val owned = requireNotNull(working)
            return read(lease, expected, DOCUMENT_PDF_LIMIT, owned) { owned }
        } catch (error: Throwable) { working?.close(); throw error }
    }
}

/** The UI borrows a bitmap; closing the controller cannot recycle a bitmap still being drawn. */
class DocumentPageBitmap internal constructor(private val image: Bitmap) : AutoCloseable {
    private var references = 1
    private val closed = AtomicBoolean()
    @Synchronized fun borrow(): DocumentBitmapLease? {
        if (references == 0 || closed.get()) return null
        references++
        return DocumentBitmapLease(image) { release() }
    }
    @Synchronized private fun release() { references--; if (references == 0) image.recycle() }
    override fun close() { if (closed.compareAndSet(false, true)) release() }
}
class DocumentBitmapLease internal constructor(val bitmap: Bitmap, private val dispose: () -> Unit) : AutoCloseable {
    private val closed = AtomicBoolean()
    override fun close() { if (closed.compareAndSet(false, true)) dispose() }
}

internal class DocumentPdfSession(private val working: DocumentWorkingFile) : AutoCloseable {
    private val descriptor = ParcelFileDescriptor.open(working.file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = try { PdfRenderer(descriptor) } catch (error: Throwable) { descriptor.close(); working.close(); throw error }
    val pageCount = renderer.pageCount
    private var closed = false
    @Synchronized fun render(index: Int, width: Int, memoryClass: Int): DocumentPageBitmap {
        check(!closed)
        renderer.openPage(index).use { page ->
            val (w, h) = documentBitmapDimensions(page.width, page.height, width, memoryClass)
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                return DocumentPageBitmap(bitmap)
            } catch (error: Throwable) { bitmap.recycle(); throw error }
        }
    }
    @Synchronized override fun close() {
        if (closed) return
        closed = true
        try { renderer.close() } finally { try { descriptor.close() } finally { working.close() } }
    }
}
