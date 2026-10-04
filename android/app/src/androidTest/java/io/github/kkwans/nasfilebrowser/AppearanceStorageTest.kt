package io.github.kkwans.nasfilebrowser

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.kkwans.nasfilebrowser.data.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AppearanceStorageTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun folder() = File(context.cacheDir, "appearance-${UUID.randomUUID()}").apply { check(mkdirs()) }
    private fun open(file: File, backups: File) = Room.databaseBuilder(context, ClientDatabase::class.java, file.absolutePath)
        .addMigrations(HistoryMigration(backups), AppearanceMigration(backups), FileLayoutMigration(backups), ActiveSessionMigration(backups)).build()

    @Test fun themesPersistAcrossDatabaseReopenAndPreserveProfiles(): Unit = runBlocking {
        val folder = folder(); val file = File(folder, "state.db")
        val profile = ServerProfile(id = "profile", name = "Fixture", address = "https://fixture.example.test/nas", updatedAt = 1)
        try {
            val db = open(file, File(folder, "backups"))
            try {
                val store = AppearanceStore(db)
                assertEquals(AppTheme.SYSTEM, store.theme.first())
                db.profiles().saveProfile(profile)
                store.save(AppTheme.LIGHT); assertEquals(AppTheme.LIGHT, store.theme.first())
                store.save(AppTheme.DARK); assertEquals(AppTheme.DARK, store.theme.first())
            } finally { db.close() }
            val reopened = open(file, File(folder, "backups"))
            try {
                assertEquals(AppTheme.DARK, AppearanceStore(reopened).theme.first())
                assertEquals(profile, reopened.profiles().profile(profile.id))
                AppearanceStore(reopened).save(AppTheme.SYSTEM)
                assertEquals(AppTheme.SYSTEM, AppearanceStore(reopened).theme.first())
            } finally { reopened.close() }
        } finally { folder.deleteRecursively() }
    }

    @Test fun v2UpgradeBacksUpAndPreservesEveryExistingStateTable(): Unit = runBlocking {
        val folder = folder(); val file = File(folder, "state.db"); val backups = File(folder, "backups")
        try {
            v2(file)
            val db = open(file, backups)
            try {
                assertEquals(AppTheme.SYSTEM, AppearanceStore(db).theme.first())
                assertEquals("Fixture", db.profiles().profile("profile")?.name)
                assertEquals("opaque-ref", db.profiles().account("account")?.credentialRef)
                assertEquals("/%D6%D0", db.profiles().directory("account")?.wirePath)
                val old = db.playback().snapshot("account", "/%D6%D0/movie.mkv", "source-id")
                assertEquals(12345L, old?.positionMs); assertEquals(120000L, old?.durationMs)
                assertEquals(ProgressSync.PENDING, old?.sync)
                val backup = JSONObject(backups.listFiles()!!.single { it.name.startsWith("schema2-before3-") }.readText())
                assertEquals(2, backup.getInt("schemaVersion"))
                for (table in listOf("server_profiles", "accounts", "directory_state", "playback_snapshots"))
                    assertEquals(1, backup.getJSONArray(table).length())
                assertEquals(12345L, backup.getJSONArray("playback_snapshots").getJSONObject(0).getLong("positionMs"))
                AppearanceStore(db).save(AppTheme.DARK)
                assertEquals(old, db.playback().snapshot("account", "/%D6%D0/movie.mkv", "source-id"))
            } finally { db.close() }
        } finally { folder.deleteRecursively() }
    }

    @Test fun failedBackupStopsUpgradeWithoutDeletingOldState(): Unit = runBlocking {
        val folder = folder(); val file = File(folder, "state.db"); val blocked = File(folder, "blocked")
        try {
            v2(file); blocked.writeText("test-owned obstruction")
            val db = open(file, File(blocked, "backups"))
            try {
                try { db.profiles().profile("profile"); fail("Upgrade must stop if backup cannot be created") }
                catch (error: IllegalStateException) {
                    assertTrue(generateSequence<Throwable>(error) { it.cause }.any { it.message.orEmpty().contains("无法备份") })
                }
            } finally { db.close() }
            SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { old ->
                assertEquals(2, old.version)
                old.rawQuery("SELECT positionMs FROM playback_snapshots", null).use { cursor -> assertTrue(cursor.moveToFirst()); assertEquals(12345L, cursor.getLong(0)) }
                old.rawQuery("SELECT COUNT(*) FROM sqlite_master WHERE name = 'app_preferences'", null).use { cursor -> assertTrue(cursor.moveToFirst()); assertEquals(0, cursor.getInt(0)) }
            }
        } finally { folder.deleteRecursively() }
    }

    private fun v2(file: File) {
        val schema = JSONObject(instrumentation.context.assets.open("io.github.kkwans.nasfilebrowser.data.ClientDatabase/2.json").bufferedReader().use { it.readText() }).getJSONObject("database")
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i); val table = entity.getString("tableName")
                db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                entity.optJSONArray("indices")?.let { indices -> for (j in 0 until indices.length()) db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table)) }
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
            db.execSQL("INSERT INTO server_profiles VALUES ('profile', 'Fixture', 'https://fixture.example.test/nas', 'NAS', 'DIRECT', 0, 1)")
            db.execSQL("INSERT INTO accounts VALUES ('account', 'profile', 0, 7, 'viewer', 'opaque-ref', 1)")
            db.execSQL("INSERT INTO directory_state VALUES ('account', '/中', '/%D6%D0')")
            db.execSQL("INSERT INTO playback_snapshots VALUES ('account', '/%D6%D0/movie.mkv', 'source-id', '/中/movie.mkv', '/%D6%D0/movie.mkv', 'Movie', 12345, 120000, 1, 'PENDING')")
            db.version = 2
        }
    }
}
