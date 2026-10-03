package io.github.kkwans.nasfilebrowser.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File

/** Snapshot v1 metadata before schema changes; vault/node secrets stay separate. */
class HistoryMigration(private val backupDirectory: File) : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        backupLocalState(db, 1, 2, backupDirectory, listOf("server_profiles", "accounts", "directory_state"))
        db.execSQL("CREATE TABLE IF NOT EXISTS `playback_snapshots` (`accountKey` TEXT NOT NULL, `resourceKey` TEXT NOT NULL, `identity` TEXT NOT NULL, `path` TEXT NOT NULL, `wirePath` TEXT NOT NULL, `name` TEXT NOT NULL, `positionMs` INTEGER NOT NULL, `durationMs` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `sync` TEXT NOT NULL, PRIMARY KEY(`accountKey`, `resourceKey`, `identity`), FOREIGN KEY(`accountKey`) REFERENCES `accounts`(`key`) ON UPDATE NO ACTION ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_playback_snapshots_accountKey_updatedAt` ON `playback_snapshots` (`accountKey`, `updatedAt`)")
    }
}
