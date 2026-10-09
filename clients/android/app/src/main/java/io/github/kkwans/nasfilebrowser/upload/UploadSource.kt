package io.github.kkwans.nasfilebrowser.upload

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.provider.MediaStore
import java.io.FileNotFoundException

data class LocalUploadSource(val uri: String, val name: String, val size: Long, val modified: Long, val mime: String,
    val relativeDirectory: String = "")

/** Read-only SAF access. Never delete, move or write a selected local file. */
internal class UploadSources(private val context: Context) {
    private val resolver = context.contentResolver
    companion object {
        internal fun sameDocumentUri(first: Uri, second: Uri): Boolean {
            if (first.scheme != "content" || second.scheme != "content" || first.authority != second.authority ||
                first.query != second.query || first.fragment != null || second.fragment != null) return false
            if (first == second) return true
            return runCatching { DocumentsContract.getDocumentId(first) == DocumentsContract.getDocumentId(second) }.getOrDefault(false)
        }
    }
    private fun document(uri: Uri): Uri = if (uri.authority == MediaStore.AUTHORITY) {
        runCatching { MediaStore.getDocumentUri(context, uri) }.getOrNull() ?: uri
    } else uri
    private fun originalDocument(record: UploadRecord, selected: Uri) {
        val original = Uri.parse(record.sourceUri)
        require(sameDocumentUri(original, selected) || sameDocumentUri(document(original), document(selected))) {
            "请选择任务原来的文件；同名文件不能代替原文件继续上传"
        }
    }
    fun restartSource(record: UploadRecord, selected: Uri): LocalUploadSource {
        originalDocument(record, selected)
        val source = read(selected)
        resolver.openFileDescriptor(selected, "r")?.use { } ?: throw FileNotFoundException("原文件无法读取，请检查系统授权")
        retain(selected)
        return source
    }
    fun reauthorize(record: UploadRecord, selected: Uri): LocalUploadSource {
        originalDocument(record, selected)
        val source = read(selected)
        check(source.name == record.name && source.size == record.expectedSize && source.modified == record.sourceModified) {
            "原文件的名称、大小或修改时间已变化，请明确重新开始上传；已有进度保留"
        }
        check(!record.remoteCreated || record.expectedSize == 0L || record.sourceModified > 0) {
            "该来源没有可靠修改时间，不能安全追加旧片段，请重新开始上传"
        }
        resolver.openFileDescriptor(selected, "r")?.use { } ?: throw FileNotFoundException("原文件无法读取，请检查系统授权")
        retain(selected)
        return source
    }
    fun retain(uri: Uri) {
        require(uri.scheme == "content") { "请通过系统文件选择器选择文件" }
        if (uri.authority == MediaStore.AUTHORITY) {
            val owned = resolver.query(uri, arrayOf(MediaStore.MediaColumns.OWNER_PACKAGE_NAME), null, null, null)?.use {
                it.moveToFirst() && it.getString(0) == context.packageName
            } == true
            if (owned) return
        }
        try { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        catch (_: SecurityException) { error("该来源无法保留读取授权，请先保存到本机文件夹再选择") }
    }
    fun read(uri: Uri, directory: String = ""): LocalUploadSource {
        require(uri.scheme == "content") { "文件来源无效，请重新选择" }
        val document = DocumentsContract.isDocumentUri(context, uri)
        val projection = if (document) arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED, DocumentsContract.Document.COLUMN_MIME_TYPE)
            else if (uri.authority == MediaStore.AUTHORITY) arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE,
                MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.MediaColumns.MIME_TYPE)
            else arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return resolver.query(uri, projection, null, null, null)?.use { row ->
            check(row.moveToFirst()) { "源文件已经不存在，请重新选择" }
            val name = row.getString(0)
            check(name.isNotBlank() && name != "." && name != ".." && !name.contains('/') && !name.contains('\u0000')) { "源文件名称无效" }
            val mime = if (row.columnCount > 3 && !row.isNull(3)) row.getString(3) else resolver.getType(uri).orEmpty()
            check(mime != DocumentsContract.Document.MIME_TYPE_DIR) { "请选择文件，文件夹请使用上传文件夹入口" }
            val size = if (!row.isNull(1)) row.getLong(1) else resolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1
            check(size >= 0) { "来源未提供文件大小，请先保存到本机后再上传" }
            LocalUploadSource(uri.toString(), name, size, if (row.columnCount > 2 && !row.isNull(2)) row.getLong(2) * if (document) 1 else 1000 else 0,
                mime.ifEmpty { "application/octet-stream" }, directory)
        } ?: throw FileNotFoundException("无法读取源文件，请检查系统读取授权")
    }
    fun folder(tree: Uri, current: () -> Boolean, progress: (Int) -> Unit): List<LocalUploadSource> {
        require(DocumentsContract.isTreeUri(tree)) { "请选择系统文件夹" }
        retain(tree)
        val root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val projection = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE)
        val rootName = resolver.query(root, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: error("无法读取文件夹")
        fun valid(name: String) = name.isNotBlank() && name != "." && name != ".." && !name.contains('/') && !name.contains('\u0000')
        check(valid(rootName)) { "文件夹名称无效" }
        val result = arrayListOf<LocalUploadSource>(); val visited = hashSetOf<String>(); var directories = 0
        fun scan(uri: Uri, directory: String, depth: Int) {
            check(current()) { "上传目标已切换" }
            require(depth <= 64 && ++directories <= 2000 && visited.add(DocumentsContract.getDocumentId(uri))) { "文件夹过深、重复或过多，请分批选择子目录" }
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(uri))
            resolver.query(children, projection, null, null, null)?.use { row ->
                while (row.moveToNext()) {
                    check(current()) { "上传目标已切换" }
                    val child = DocumentsContract.buildDocumentUriUsingTree(tree, row.getString(0)); val name = row.getString(1)
                    check(valid(name)) { "子项目名称无效" }
                    if (row.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) scan(child, "$directory/$name", depth + 1)
                    else {
                        require(result.size < 5000) { "超过5000个文件，请分批选择子目录" }
                        result.add(read(child, directory)); progress(result.size)
                    }
                }
            } ?: error("无法读取文件夹内容，请检查授权")
        }
        scan(root, rootName, 0)
        return result
    }
}
