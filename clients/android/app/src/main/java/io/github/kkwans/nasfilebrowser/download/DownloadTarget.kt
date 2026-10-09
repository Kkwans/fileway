package io.github.kkwans.nasfilebrowser.download

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
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
        private val directoryLock = Any()
        internal fun sameTree(first: Uri, second: Uri): Boolean = runCatching {
            first.scheme == "content" && second.scheme == "content" && first.authority == second.authority &&
                DocumentsContract.isTreeUri(first) && DocumentsContract.isTreeUri(second) &&
                DocumentsContract.getTreeDocumentId(first) == DocumentsContract.getTreeDocumentId(second)
        }.getOrDefault(false)
    }
    fun allocate(record: DownloadRecord): Uri {
        val segments = downloadDirectorySegments(record.relativeDirectory)
        val name = record.name.replace('/', '_').replace('\u0000', '_').ifBlank { "fileway-${record.id}" }
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)) ?: "application/octet-stream"
        if (record.treeUri.isEmpty()) {
            return resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name); put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, (listOf(Environment.DIRECTORY_DOWNLOADS, "fileway") + segments).joinToString("/"))
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }) ?: throw FileNotFoundException("无法在 Download/fileway 创建文件，请检查可用空间")
        }
        val tree = Uri.parse(record.treeUri)
        check(hasAccess(record.treeUri)) { "原下载目录授权已失效，请在此下载的更多菜单中重新授权目录" }
        val folder = nestedDirectory(tree, segments, create = true)
        return DocumentsContract.createDocument(resolver, folder, mime, name) ?: throw FileNotFoundException("所选目录无法创建文件")
    }
    private fun nestedDirectory(tree: Uri, segments: List<String>, create: Boolean): Uri = synchronized(directoryLock) {
        var folder = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        for (name in segments) {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(folder))
            val matches = arrayListOf<Pair<String, String>>()
            resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) if (cursor.getString(1) == name) matches.add(cursor.getString(0) to cursor.getString(2))
            } ?: throw FileNotFoundException("无法读取下载目录，请检查目录授权")
            check(matches.size <= 1 && matches.all { it.second == DocumentsContract.Document.MIME_TYPE_DIR }) { "下载位置存在同名文件或重复目录，请更换保存目录" }
            folder = matches.singleOrNull()?.let { DocumentsContract.buildDocumentUriUsingTree(tree, it.first) }
                ?: if (create) DocumentsContract.createDocument(resolver, folder, DocumentsContract.Document.MIME_TYPE_DIR, name)
                    ?: throw FileNotFoundException("无法创建本机下载子目录")
                else throw FileNotFoundException("下载子目录尚未建立")
        }
        folder
    }
    fun complete(record: DownloadRecord) {
        if (record.treeUri.isEmpty()) check(resolver.update(Uri.parse(record.localUri), ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) == 1) { "文件已经写入，但无法完成下载目录登记，请重试" }
    }
    fun delete(record: DownloadRecord): Boolean {
        val removed = if (record.treeUri.isNotEmpty()) DocumentsContract.deleteDocument(resolver, Uri.parse(record.localUri)) else resolver.delete(Uri.parse(record.localUri), null, null) > 0
        if (removed) runCatching { DownloadIndex.get(context).remove(record) }
        return removed
    }
    fun directoryUri(treeUri: String, relativeDirectory: String = ""): Uri {
        val segments = downloadDirectorySegments(relativeDirectory)
        return if (treeUri.isEmpty()) DocumentsContract.buildDocumentUri("com.android.externalstorage.documents",
            (listOf("primary:Download", "fileway") + segments).joinToString("/"))
        else nestedDirectory(Uri.parse(treeUri), segments, create = false)
    }
    private fun systemDirectoryBrowser(): String = context.packageManager.queryIntentActivities(
        Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), PackageManager.MATCH_SYSTEM_ONLY or PackageManager.MATCH_DEFAULT_ONLY,
    ).firstOrNull()?.activityInfo?.packageName ?: throw ActivityNotFoundException("系统目录浏览器不可用")
    fun directoryIntent(treeUri: String, relativeDirectory: String = ""): Intent = Intent(Intent.ACTION_VIEW).setDataAndType(directoryUri(treeUri, relativeDirectory), DocumentsContract.Document.MIME_TYPE_DIR)
        .setPackage(systemDirectoryBrowser())
        .apply {
            // MediaStore ownership does not give us a SAF directory grant to
            // delegate. The system file manager can browse its own provider.
            if (treeUri.isNotEmpty()) addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    fun directoryPicker(treeUri: String, relativeDirectory: String = ""): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        .setPackage(systemDirectoryBrowser()).putExtra(DocumentsContract.EXTRA_INITIAL_URI, directoryUri(treeUri, relativeDirectory))
}
