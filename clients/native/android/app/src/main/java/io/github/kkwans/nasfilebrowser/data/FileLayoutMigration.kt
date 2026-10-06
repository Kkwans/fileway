package io.github.kkwans.nasfilebrowser.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File

class FileLayoutMigration(private val backupDirectory: File) : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        backupLocalState(db, 3, 4, backupDirectory, listOf("server_profiles", "accounts", "directory_state", "playback_snapshots", "app_preferences"))
        db.execSQL("ALTER TABLE `directory_state` ADD COLUMN `fileLayout` TEXT NOT NULL DEFAULT 'COVER'")
    }
}
