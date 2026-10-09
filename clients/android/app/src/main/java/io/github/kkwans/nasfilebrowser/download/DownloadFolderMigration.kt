package io.github.kkwans.nasfilebrowser.download

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Existing tasks keep their original URI and resume position. */
class DownloadFolderMigration : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE downloads ADD COLUMN relativeDirectory TEXT NOT NULL DEFAULT ''")
    }
}
