package io.github.kkwans.nasfilebrowser.download

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Additive only: no existing account, node, preference or playback rows change. */
class DownloadMigration : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `downloads` (`id` TEXT NOT NULL, `jobId` INTEGER NOT NULL, `accountKey` TEXT NOT NULL, `profileId` TEXT NOT NULL, `sourceRevision` INTEGER NOT NULL, `path` TEXT NOT NULL, `wirePath` TEXT NOT NULL, `name` TEXT NOT NULL, `type` TEXT NOT NULL, `expectedSize` INTEGER NOT NULL, `modified` TEXT NOT NULL, `identity` TEXT NOT NULL, `sourceLabel` TEXT NOT NULL, `treeUri` TEXT NOT NULL, `localUri` TEXT NOT NULL, `status` TEXT NOT NULL, `downloaded` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `generation` INTEGER NOT NULL, `error` TEXT NOT NULL, `positionMs` INTEGER NOT NULL, `durationMs` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_downloads_jobId` ON `downloads` (`jobId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_downloads_accountKey_createdAt` ON `downloads` (`accountKey`, `createdAt`)")
    }
}
