package io.github.kkwans.nasfilebrowser.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File

class AppearanceMigration(private val backupDirectory: File) : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        backupLocalState(db, 2, 3, backupDirectory, listOf("server_profiles", "accounts", "directory_state", "playback_snapshots"))
        db.execSQL("CREATE TABLE IF NOT EXISTS `app_preferences` (`id` INTEGER NOT NULL, `theme` TEXT NOT NULL, PRIMARY KEY(`id`))")
    }
}
