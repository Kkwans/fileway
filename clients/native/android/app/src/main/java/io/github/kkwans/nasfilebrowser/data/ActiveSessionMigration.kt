package io.github.kkwans.nasfilebrowser.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File

class ActiveSessionMigration(private val backupDirectory: File) : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        backupLocalState(db, 4, 5, backupDirectory, listOf("server_profiles", "accounts", "directory_state", "playback_snapshots", "app_preferences"))
        db.execSQL("CREATE TABLE IF NOT EXISTS `active_session` (`id` INTEGER NOT NULL, `accountKey` TEXT NOT NULL, `owner` TEXT NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`accountKey`) REFERENCES `accounts`(`key`) ON UPDATE NO ACTION ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_active_session_accountKey` ON `active_session` (`accountKey`)")
        // One-time upgrade recovery from the recorded last authenticated login.
        // Tied timestamps require a manual choice; never pick an arbitrary account.
        val eligible = "SELECT a.`key`, a.updatedAt FROM accounts a JOIN server_profiles p ON p.id = a.profileId WHERE p.backend = 'NAS' AND p.sourceRevision = a.sourceRevision"
        db.execSQL("INSERT INTO active_session (id, accountKey, owner) SELECT 1, candidate.`key`, 'legacy-recovery' FROM ($eligible) candidate WHERE candidate.updatedAt = (SELECT MAX(updatedAt) FROM ($eligible)) AND (SELECT COUNT(*) FROM ($eligible) newest WHERE newest.updatedAt = candidate.updatedAt) = 1")
    }
}
