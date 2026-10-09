package io.github.kkwans.nasfilebrowser.upload

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Add a transfer ledger; no previous account/download/node rows are rewritten. */
class UploadMigration : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `uploads` (`id` TEXT NOT NULL, `jobId` INTEGER NOT NULL, `accountKey` TEXT NOT NULL, `profileId` TEXT NOT NULL, `sourceRevision` INTEGER NOT NULL, `sourceUri` TEXT NOT NULL, `name` TEXT NOT NULL, `mime` TEXT NOT NULL, `expectedSize` INTEGER NOT NULL, `sourceModified` INTEGER NOT NULL, `targetPath` TEXT NOT NULL, `targetWire` TEXT NOT NULL, `parentWire` TEXT NOT NULL, `sourceLabel` TEXT NOT NULL, `overwrite` INTEGER NOT NULL, `replacedIdentity` TEXT NOT NULL, `protocol` TEXT NOT NULL, `remoteCreated` INTEGER NOT NULL, `status` TEXT NOT NULL, `uploaded` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `generation` INTEGER NOT NULL, `error` TEXT NOT NULL, `batchId` TEXT NOT NULL, `batchName` TEXT NOT NULL, `batchItems` INTEGER NOT NULL, `batchBytes` INTEGER NOT NULL, `folderUpload` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_uploads_jobId` ON `uploads` (`jobId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_uploads_accountKey_createdAt` ON `uploads` (`accountKey`, `createdAt`)")
    }
}
