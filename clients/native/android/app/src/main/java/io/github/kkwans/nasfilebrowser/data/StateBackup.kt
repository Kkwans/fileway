package io.github.kkwans.nasfilebrowser.data

import android.util.AtomicFile
import androidx.sqlite.db.SupportSQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Atomic metadata-only backup before a local schema migration; no vault or node state. */
internal fun backupLocalState(db: SupportSQLiteDatabase, version: Int, nextVersion: Int, backupDirectory: File, tables: List<String>) {
    val backup = JSONObject().put("schemaVersion", version).put("createdAt", System.currentTimeMillis())
    for (table in tables) {
        val rows = JSONArray()
        db.query("SELECT * FROM `$table`").use { cursor ->
            while (cursor.moveToNext()) {
                val row = JSONObject()
                cursor.columnNames.forEachIndexed { index, name ->
                    row.put(name, when (cursor.getType(index)) {
                        android.database.Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
                        android.database.Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
                        else -> cursor.getString(index)
                    })
                }
                rows.put(row)
            }
        }
        backup.put(table, rows)
    }
    check(backupDirectory.isDirectory || backupDirectory.mkdirs()) { "无法备份本机状态，升级已停止" }
    val file = AtomicFile(File(backupDirectory, "schema$version-before$nextVersion-${System.currentTimeMillis()}.json"))
    val stream = file.startWrite()
    try { stream.write(backup.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
    catch (error: Exception) { file.failWrite(stream); throw error }
}
