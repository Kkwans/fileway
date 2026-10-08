package io.github.kkwans.nasfilebrowser.download

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import java.io.FileNotFoundException

class DownloadTarget(private val context: Context) {
    private val resolver = context.contentResolver
    private val preferences = context.getSharedPreferences("download-target", Context.MODE_PRIVATE)
    fun selectedTree(): String = preferences.getString("tree", "").orEmpty()
    fun selectTree(uri: Uri?) {
        if (uri != null) authorizeTree(uri)
        check(preferences.edit().putString("tree", uri?.toString().orEmpty()).commit()) { "下载目录设置未能保存" }
    }
    private fun authorizeTree(uri: Uri) {
        require(uri.scheme == "content" && DocumentsContract.isTreeUri(uri)) { "请选择系统文件管理器中的文件夹" }
        try { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        catch (failure: SecurityException) { throw IllegalStateException("未获得目录的读写授权，请重新选择并允许访问", failure) }
        check(hasAccess(uri.toString())) { "目录的读写授权未能保留，请重新选择目录" }
    }
    fun reauthorizeTree(original: String, selected: Uri) {
        require(sameTree(Uri.parse(original), selected)) { "请选择此下载原来的目录；重新授权不会移动文件或更改默认目录" }
        authorizeTree(selected)
    }
    fun hasAccess(tree: String): Boolean = tree.isEmpty() || resolver.persistedUriPermissions.any {
        it.isReadPermission && it.isWritePermission && sameTree(it.uri, Uri.parse(tree))
    }
    companion object {
        internal fun sameTree(first: Uri, second: Uri): Boolean = runCatching {
            first.scheme == "content" && second.scheme == "content" && first.authority == second.authority &&
                DocumentsContract.isTreeUri(first) && DocumentsContract.isTreeUri(second) &&
                DocumentsContract.getTreeDocumentId(first) == DocumentsContract.getTreeDocumentId(second)
        }.getOrDefault(false)
    }
    fun allocate(record: DownloadRecord): Uri {
        val name = record.name.replace('/', '_').replace('\u0000', '_').ifBlank { "fileway-${record.id}" }
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)) ?: "application/octet-stream"
        if (record.treeUri.isEmpty()) {
            return resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name); put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/fileway")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }) ?: throw FileNotFoundException("无法在 Download/fileway 创建文件，请检查可用空间")
        }
        val tree = Uri.parse(record.treeUri)
        check(hasAccess(record.treeUri)) { "原下载目录授权已失效，请在此下载的更多菜单中重新授权目录" }
        val folder = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        return DocumentsContract.createDocument(resolver, folder, mime, name) ?: throw FileNotFoundException("所选目录无法创建文件")
    }
    fun complete(record: DownloadRecord) {
        if (record.treeUri.isEmpty()) check(resolver.update(Uri.parse(record.localUri), ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) == 1) { "文件已经写入，但无法完成下载目录登记，请重试" }
    }
    fun delete(record: DownloadRecord): Boolean = if (record.treeUri.isNotEmpty()) DocumentsContract.deleteDocument(resolver, Uri.parse(record.localUri)) else resolver.delete(Uri.parse(record.localUri), null, null) > 0
    private fun directoryUri(treeUri: String): Uri = if (treeUri.isEmpty())
        DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Download/fileway")
        else Uri.parse(treeUri).let { DocumentsContract.buildDocumentUriUsingTree(it, DocumentsContract.getTreeDocumentId(it)) }
    fun directoryIntent(treeUri: String): Intent = Intent(Intent.ACTION_VIEW).setDataAndType(directoryUri(treeUri), DocumentsContract.Document.MIME_TYPE_DIR)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    fun directoryPicker(treeUri: String): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).putExtra(DocumentsContract.EXTRA_INITIAL_URI, directoryUri(treeUri))
}
