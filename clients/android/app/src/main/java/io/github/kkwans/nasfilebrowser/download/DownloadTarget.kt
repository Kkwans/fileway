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
        if (uri != null) resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        check(preferences.edit().putString("tree", uri?.toString().orEmpty()).commit()) { "下载目录设置未能保存" }
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
        check(resolver.persistedUriPermissions.any { it.uri == tree && it.isWritePermission }) { "所选下载目录的授权已失效，请重新选择目录" }
        val folder = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        return DocumentsContract.createDocument(resolver, folder, mime, name) ?: throw FileNotFoundException("所选目录无法创建文件")
    }
    fun complete(record: DownloadRecord) {
        if (record.treeUri.isEmpty()) check(resolver.update(Uri.parse(record.localUri), ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) == 1) { "文件已经写入，但无法完成下载目录登记，请重试" }
    }
    fun delete(record: DownloadRecord): Boolean = if (record.treeUri.isNotEmpty()) DocumentsContract.deleteDocument(resolver, Uri.parse(record.localUri)) else resolver.delete(Uri.parse(record.localUri), null, null) > 0
    fun directoryIntent(record: DownloadRecord): Intent {
        if (record.treeUri.isNotEmpty()) {
            val tree = Uri.parse(record.treeUri)
            return Intent(Intent.ACTION_VIEW).setDataAndType(DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)), DocumentsContract.Document.MIME_TYPE_DIR)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        // DocumentsUI/providers differ in direct-folder support. Use the system
        // picker with the exact location when no directory VIEW is available.
        val folder = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Download/fileway")
        return Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).putExtra(DocumentsContract.EXTRA_INITIAL_URI, folder)
    }
}
