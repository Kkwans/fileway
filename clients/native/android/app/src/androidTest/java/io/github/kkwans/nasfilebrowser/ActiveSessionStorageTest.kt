package io.github.kkwans.nasfilebrowser

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.kkwans.nasfilebrowser.data.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ActiveSessionStorageTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun open(file: File, backups: File) = Room.databaseBuilder(context, ClientDatabase::class.java, file.absolutePath)
        .addMigrations(HistoryMigration(backups), AppearanceMigration(backups), FileLayoutMigration(backups), ActiveSessionMigration(backups)).build()

    @Test fun activeAccountSurvivesReopenAndNeverFallsBackAfterSignoutOrSourceChange(): Unit = runBlocking {
        val folder = File(context.cacheDir, "active-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val file = File(folder, "state.db"); val backups = File(folder, "backups")
        var profile: ServerProfile? = null
        try {
            var db = open(file, backups)
            var store = ProfileStore(db, CredentialVault(context))
            profile = store.save(ServerProfile(name = "Fixture", address = "https://fixture.example.test"))
            val one = store.saveLogin(profile, 1, "one", "owned-token-one")
            val two = store.saveLogin(profile, 2, "two", "owned-token-two")
            store.activate(profile, one)
            db.close()
            db = open(file, backups); store = ProfileStore(db, CredentialVault(context))
            try {
                assertEquals(one, store.active()?.second)
                store.activate(profile, one, "new-owner")
                store.deactivate(one, "old-owner")
                assertEquals("Late cleanup must not forget a newer login", one, store.active()?.second)
                store.activate(profile, two)
                assertEquals(two, store.active()?.second)
                store.signOut(two)
                assertNull("Signing out must not log in an older account", store.active())
                assertEquals("owned-token-one", store.token(profile, one))
                store.activate(profile, one)
                profile = store.save(profile.copy(address = "https://replacement.example.test"))
                assertNull("A changed source cannot reuse an old active reference", store.active())
                assertNull(store.token(profile, one))
                assertEquals(0, store.accounts(profile).size)
            } finally { store.remove(profile); db.close() }
        } finally { folder.deleteRecursively() }
    }

    @Test fun v4UpgradeRecoversOnlyUniqueLatestLoginAndKeepsMetadataBackup(): Unit = runBlocking {
        for (tied in listOf(false, true)) {
            val folder = File(context.cacheDir, "active-upgrade-${UUID.randomUUID()}").apply { check(mkdirs()) }
            val file = File(folder, "state.db"); val backups = File(folder, "backups")
            try {
                v4(file, tied)
                val db = open(file, backups)
                try {
                    assertEquals(if (tied) null else "profile/0/2", db.profiles().activeSession()?.accountKey)
                    assertEquals(2, db.profiles().accounts("profile", 0).size)
                    val backup = JSONObject(backups.listFiles()!!.single().readText())
                    assertEquals(4, backup.getInt("schemaVersion"))
                    assertEquals(2, backup.getJSONArray("accounts").length())
                    assertEquals("/%D6%D0", db.profiles().directory("profile/0/1")?.wirePath)
                    db.profiles().deleteProfile("profile")
                    assertNull(db.profiles().activeSession())
                } finally { db.close() }
            } finally { folder.deleteRecursively() }
        }
    }

    @Test fun backupFailureDoesNotUpgradeOrEraseV4Accounts(): Unit = runBlocking {
        val folder = File(context.cacheDir, "active-blocked-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val file = File(folder, "state.db"); val blocker = File(folder, "blocked").apply { writeText("owned obstruction") }
        try {
            v4(file, false)
            val db = open(file, File(blocker, "backups"))
            try {
                try { db.profiles().activeSession(); fail("Backup must precede upgrade") }
                catch (error: IllegalStateException) { assertTrue(generateSequence<Throwable>(error) { it.cause }.any { it.message.orEmpty().contains("无法备份") }) }
            } finally { db.close() }
            SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { old ->
                assertEquals(4, old.version)
                old.rawQuery("SELECT COUNT(*) FROM accounts", null).use { row -> assertTrue(row.moveToFirst()); assertEquals(2, row.getInt(0)) }
            }
        } finally { folder.deleteRecursively() }
    }

    private fun v4(file: File, tied: Boolean) {
        val schema = JSONObject(instrumentation.context.assets.open("io.github.kkwans.nasfilebrowser.data.ClientDatabase/4.json").bufferedReader().use { it.readText() }).getJSONObject("database")
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i); val name = entity.getString("tableName")
                db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", name))
                entity.optJSONArray("indices")?.let { indices -> for (j in 0 until indices.length()) db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", name)) }
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
            db.execSQL("INSERT INTO server_profiles VALUES ('profile', 'Fixture', 'https://fixture.example.test', 'NAS', 'DIRECT', 0, 1)")
            db.execSQL("INSERT INTO accounts VALUES ('profile/0/1', 'profile', 0, 1, 'one', 'opaque-one', ${if (tied) 2 else 1})")
            db.execSQL("INSERT INTO accounts VALUES ('profile/0/2', 'profile', 0, 2, 'two', 'opaque-two', 2)")
            db.execSQL("INSERT INTO directory_state VALUES ('profile/0/1', '/中', '/%D6%D0', 'COMPACT')")
            db.version = 4
        }
    }
}
