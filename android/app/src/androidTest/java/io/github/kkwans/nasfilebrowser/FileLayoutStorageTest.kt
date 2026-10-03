package io.github.kkwans.nasfilebrowser

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.kkwans.nasfilebrowser.data.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class FileLayoutStorageTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun open(file: File, backups: File) = Room.databaseBuilder(context, ClientDatabase::class.java, file.absolutePath)
        .addMigrations(HistoryMigration(backups), AppearanceMigration(backups), FileLayoutMigration(backups)).build()

    @Test fun layoutSurvivesReopenAndDirectoryNavigationWithoutCrossingAccounts(): Unit = runBlocking {
        val folder = File(context.cacheDir, "layout-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val file = File(folder, "state.db"); val backups = File(folder, "backups")
        try {
            val db = open(file, backups)
            val accounts = listOf(
                AccountRecord("a/0/1", "a", 0, 1, "one", "fixture-ref", 1),
                AccountRecord("a/0/2", "a", 0, 2, "two", "fixture-ref", 1),
                AccountRecord("b/0/1", "b", 0, 1, "one", "fixture-ref", 1),
                AccountRecord("a/1/1", "a", 1, 1, "one", "fixture-ref", 1))
            try {
                for (id in listOf("a", "b")) db.profiles().saveProfile(ServerProfile(id, id, "https://fixture.example.test/$id"))
                accounts.forEach { db.profiles().saveAccount(it) }
                val store = ProfileStore(db, CredentialVault(context))
                store.saveDirectory(accounts[0], "/中", "/%D6%D0")
                store.saveFileLayout(accounts[0], FileLayout.COMPACT)
                store.saveDirectory(accounts[0], "/新目录", "/%E6%96%B0")
                store.saveFileLayout(accounts[1], FileLayout.LIST)
                store.saveFileLayout(accounts[2], FileLayout.DETAIL)
                store.saveDirectory(accounts[3], "/", "/")
                assertEquals(FileLayout.COMPACT, store.directory(accounts[0])?.fileLayout)
                assertEquals("/%E6%96%B0", store.directory(accounts[0])?.wirePath)
            } finally { db.close() }
            val reopened = open(file, backups)
            try {
                assertEquals(listOf(FileLayout.COMPACT, FileLayout.LIST, FileLayout.DETAIL, FileLayout.COVER),
                    accounts.map { reopened.profiles().directory(it.key)?.fileLayout })
                reopened.profiles().deleteProfile("a")
                assertNull(reopened.profiles().directory(accounts[0].key))
                assertEquals(FileLayout.DETAIL, reopened.profiles().directory(accounts[2].key)?.fileLayout)
            } finally { reopened.close() }
        } finally { folder.deleteRecursively() }
    }

    @Test fun v3UpgradeBacksUpThemeProgressAndOpaqueDirectoryBeforeAddingDefault(): Unit = runBlocking {
        val folder = File(context.cacheDir, "layout-upgrade-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val file = File(folder, "state.db"); val backups = File(folder, "backups")
        try {
            v3(file)
            val db = open(file, backups)
            try {
                val directory = db.profiles().directory("account")!!
                assertEquals(FileLayout.COVER, directory.fileLayout)
                assertEquals("/%D6%D0", directory.wirePath)
                assertEquals(AppTheme.DARK, db.preferences().observe().first()?.theme)
                assertEquals(12345L, db.playback().snapshot("account", "/movie", "identity")?.positionMs)
                val backup = JSONObject(backups.listFiles()!!.single().readText())
                assertEquals(3, backup.getInt("schemaVersion"))
                assertEquals("DARK", backup.getJSONArray("app_preferences").getJSONObject(0).getString("theme"))
                assertFalse(backup.getJSONArray("directory_state").getJSONObject(0).has("fileLayout"))
                assertEquals("opaque-ref", db.profiles().account("account")?.credentialRef)
            } finally { db.close() }
        } finally { folder.deleteRecursively() }
    }

    @Test fun backupFailureLeavesV3DatabaseAndRowsIntact(): Unit = runBlocking {
        val folder = File(context.cacheDir, "layout-blocked-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val file = File(folder, "state.db"); val obstruction = File(folder, "blocked").apply { writeText("owned fixture") }
        try {
            v3(file)
            val db = open(file, File(obstruction, "backups"))
            try {
                try { db.profiles().directory("account"); fail("Backup failure must abort migration") }
                catch (error: IllegalStateException) {
                    assertTrue(generateSequence<Throwable>(error) { it.cause }.any { it.message.orEmpty().contains("无法备份") })
                }
            } finally { db.close() }
            SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { old ->
                assertEquals(3, old.version)
                old.rawQuery("SELECT wirePath FROM directory_state", null).use { row -> assertTrue(row.moveToFirst()); assertEquals("/%D6%D0", row.getString(0)) }
                old.rawQuery("PRAGMA table_info(directory_state)", null).use { rows ->
                    while (rows.moveToNext()) assertNotEquals("fileLayout", rows.getString(rows.getColumnIndexOrThrow("name")))
                }
            }
        } finally { folder.deleteRecursively() }
    }

    private fun v3(file: File) {
        val schema = JSONObject(instrumentation.context.assets.open("io.github.kkwans.nasfilebrowser.data.ClientDatabase/3.json").bufferedReader().use { it.readText() }).getJSONObject("database")
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i); val table = entity.getString("tableName")
                db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                entity.optJSONArray("indices")?.let { indices -> for (j in 0 until indices.length()) db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table)) }
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
            db.execSQL("INSERT INTO server_profiles VALUES ('profile', 'Fixture', 'https://fixture.example.test', 'NAS', 'DIRECT', 0, 1)")
            db.execSQL("INSERT INTO accounts VALUES ('account', 'profile', 0, 1, 'one', 'opaque-ref', 1)")
            db.execSQL("INSERT INTO directory_state VALUES ('account', '/中', '/%D6%D0')")
            db.execSQL("INSERT INTO playback_snapshots VALUES ('account', '/movie', 'identity', '/movie', '/movie', 'Movie', 12345, 120000, 1, 'PENDING')")
            db.execSQL("INSERT INTO app_preferences VALUES (1, 'DARK')")
            db.version = 3
        }
    }
}
