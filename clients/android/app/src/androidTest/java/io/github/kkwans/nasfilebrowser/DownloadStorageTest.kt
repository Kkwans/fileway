package io.github.kkwans.nasfilebrowser

import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.kkwans.nasfilebrowser.data.*
import io.github.kkwans.nasfilebrowser.download.*
import io.github.kkwans.nasfilebrowser.upload.UploadMigration
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Audited owned fixtures only: never opens nfb-client.db, Vault or user media. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class DownloadStorageTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun record(id: String = UUID.randomUUID().toString()) = DownloadRecord(id, 7300001, "owned-account", "owned-profile", 0,
        "/owned.mkv", "/owned.mkv", "fileway-owned-$id.mkv", "video", 8, "owned-modified", "8/owned-modified", "Owned fixture", "", createdAt = 1, updatedAt = 1)

    @Test fun migrationPreservesAccountsDirectoriesThemeHistoryAndSession() = runBlocking {
        val name = "fileway-owned-migration-${UUID.randomUUID()}.db"
        var room: ClientDatabase? = null
        try {
            val schema = JSONObject(InstrumentationRegistry.getInstrumentation().context.assets
                .open("io.github.kkwans.nasfilebrowser.data.ClientDatabase/5.json").bufferedReader().use { it.readText() }).getJSONObject("database")
            SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null).use { old ->
                val entities = schema.getJSONArray("entities")
                for (i in 0 until entities.length()) {
                    val entity = entities.getJSONObject(i); val table = entity.getString("tableName")
                    old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                    val indexes = entity.optJSONArray("indices") ?: org.json.JSONArray()
                    for (j in 0 until indexes.length()) old.execSQL(indexes.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                }
                old.execSQL("INSERT INTO server_profiles VALUES ('owned-profile','Owned fixture','https://owned.example.test','NAS','DIRECT',0,1)")
                old.execSQL("INSERT INTO accounts VALUES ('owned-account','owned-profile',0,7,'owned-user','unused-owned-reference',1)")
                old.execSQL("INSERT INTO directory_state VALUES ('owned-account','/收藏','/%ed%a0%80%2B','UNBOUNDED')")
                old.execSQL("INSERT INTO playback_snapshots VALUES ('owned-account','/owned.mkv','8/owned-modified','/owned.mkv','/owned.mkv','owned.mkv',42000,120000,1,'PENDING')")
                old.execSQL("INSERT INTO app_preferences VALUES (1,'DARK')")
                old.execSQL("INSERT INTO active_session VALUES (1,'owned-account','owned-session')")
                old.version = 5
            }
            room = Room.databaseBuilder(context, ClientDatabase::class.java, name).addMigrations(DownloadMigration(), DownloadFolderMigration(), UploadMigration()).build()
            assertEquals("unused-owned-reference", room.profiles().account("owned-account")?.credentialRef)
            assertEquals("/%ed%a0%80%2B", room.profiles().directory("owned-account")?.wirePath)
            assertEquals(FileLayout.UNBOUNDED, room.profiles().directory("owned-account")?.fileLayout)
            assertEquals("owned-session", room.profiles().activeSession()?.owner)
            assertEquals(AppTheme.DARK, room.preferences().observe().first()?.theme)
            assertEquals(42000L, room.playback().snapshot("owned-account", "/owned.mkv", "8/owned-modified")?.positionMs)
            assertTrue(room.downloads().observe().first().isEmpty())
            room.downloads().insert(record())
            assertEquals(1, room.downloads().observe().first().size)
        } finally { room?.close(); context.deleteDatabase(name) }
    }

    @Test fun pausedGenerationRejectsLateProgressAndCompletion() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, ClientDatabase::class.java).build()
        try {
            val item = record(); val dao = db.downloads(); dao.insert(item)
            assertEquals(1, dao.claim(item.id, 2)); val generation = dao.get(item.id)!!.generation
            assertEquals(1, dao.progress(item.id, generation, 4, 3))
            assertEquals(1, dao.command(item.id, "paused", 4))
            assertEquals(0, dao.progress(item.id, generation, 8, 5))
            assertEquals(0, dao.finish(item.id, generation, "completed", "", 5))
            assertEquals(4L, dao.get(item.id)!!.downloaded)
            assertEquals("paused", dao.get(item.id)!!.status)
            assertEquals(1, dao.command(item.id, "queued", 6)); assertEquals(1, dao.claim(item.id, 7))
            assertTrue(dao.get(item.id)!!.generation > generation)
        } finally { db.close() }
    }

    @Test fun folderMigrationPreservesExistingPartialDownloadAndPersistsNestedTargets(): Unit = runBlocking {
        val name = "fileway-owned-folder-migration-${UUID.randomUUID()}.db"
        var room: ClientDatabase? = null
        try {
            val schema = JSONObject(InstrumentationRegistry.getInstrumentation().context.assets
                .open("io.github.kkwans.nasfilebrowser.data.ClientDatabase/6.json").bufferedReader().use { it.readText() }).getJSONObject("database")
            SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null).use { old ->
                val entities = schema.getJSONArray("entities")
                for (index in 0 until entities.length()) {
                    val entity = entities.getJSONObject(index); val table = entity.getString("tableName")
                    old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                    val indices = entity.optJSONArray("indices") ?: org.json.JSONArray()
                    for (j in 0 until indices.length()) old.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                }
                old.execSQL("INSERT INTO downloads VALUES ('owned-id',7300001,'owned-account','owned-profile',2,'/owned.mkv','/%FF.mkv','owned.mkv','video',8,'owned-modified','8/owned-modified','Owned fixture','content://fixture.invalid/tree/owned','content://fixture.invalid/document/partial','paused',4,1,2,3,'owned-error',42000,60000)")
                old.version = 6
            }
            room = Room.databaseBuilder(context, ClientDatabase::class.java, name).addMigrations(DownloadFolderMigration(), UploadMigration()).build()
            val saved = room.downloads().get("owned-id")!!
            assertEquals("paused", saved.status); assertEquals(4L, saved.downloaded); assertEquals(3L, saved.generation)
            assertEquals(42000L, saved.positionMs); assertEquals(60000L, saved.durationMs); assertEquals("/%FF.mkv", saved.wirePath)
            assertEquals("content://fixture.invalid/document/partial", saved.localUri); assertEquals("", saved.relativeDirectory)
            room.downloads().insert(saved.copy(id = "owned-nested", jobId = 7300002, relativeDirectory = "owned/子目录 +% #"))
            assertEquals("owned/子目录 +% #", room.downloads().get("owned-nested")!!.relativeDirectory)
        } finally { room?.close(); context.deleteDatabase(name) }
    }

    @Test fun completedContentReadsAndSeeksOfflineWithoutProfileOrCredentials() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, ClientDatabase::class.java).build()
        val target = DownloadTarget(context); var saved: DownloadRecord? = null
        try {
            val item = record(); val uri = target.allocate(item)
            saved = item.copy(localUri = uri.toString(), status = "completed", downloaded = 8)
            context.contentResolver.openOutputStream(uri, "w")!!.use { it.write(byteArrayOf(10, 11, 12, 13, 14, 15, 16, 17)) }
            target.complete(saved); db.downloads().insert(saved)
            val source = DownloadDataSource(context, db)
            try {
                assertEquals(3L, source.open(DataSpec.Builder().setUri(Uri.parse("fileway-download://${item.id}/owned.mkv")).setPosition(3).setLength(3).build()))
                val bytes = ByteArray(3)
                assertEquals(3, source.read(bytes, 0, 3)); assertArrayEquals(byteArrayOf(13, 14, 15), bytes)
                assertEquals(C.RESULT_END_OF_INPUT, source.read(bytes, 0, 3))
            } finally { source.close() }
        } finally { saved?.let { target.delete(it) }; db.close() }
    }
}
