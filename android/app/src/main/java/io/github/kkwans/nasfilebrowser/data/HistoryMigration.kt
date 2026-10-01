package io.github.kkwans.nasfilebrowser.data

import android.util.AtomicFile
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Snapshot v1 metadata before schema changes; vault/node secrets stay separate. */
class HistoryMigration(private val backupDirectory: File) : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        val backup = JSONObject().put("schemaVersion", 1).put("createdAt", System.currentTimeMillis())
        for (table in listOf("server_profiles", "accounts", "directory_state")) {
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
        val file = AtomicFile(File(backupDirectory, "schema1-before2-${System.currentTimeMillis()}.json"))
        val stream = file.startWrite()
        try { stream.write(backup.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
        catch (error: Exception) { file.failWrite(stream); throw error }
        db.execSQL("CREATE TABLE IF NOT EXISTS `playback_snapshots` (`accountKey` TEXT NOT NULL, `resourceKey` TEXT NOT NULL, `identity` TEXT NOT NULL, `path` TEXT NOT NULL, `wirePath` TEXT NOT NULL, `name` TEXT NOT NULL, `positionMs` INTEGER NOT NULL, `durationMs` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `sync` TEXT NOT NULL, PRIMARY KEY(`accountKey`, `resourceKey`, `identity`), FOREIGN KEY(`accountKey`) REFERENCES `accounts`(`key`) ON UPDATE NO ACTION ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_playback_snapshots_accountKey_updatedAt` ON `playback_snapshots` (`accountKey`, `updatedAt`)")
    }
}
