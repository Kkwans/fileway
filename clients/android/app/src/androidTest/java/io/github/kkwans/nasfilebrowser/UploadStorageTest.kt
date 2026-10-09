package io.github.kkwans.nasfilebrowser

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.kkwans.nasfilebrowser.data.ClientDatabase
import io.github.kkwans.nasfilebrowser.upload.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class UploadStorageTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun restartClaimsOnlyTheConfirmedGenerationAndPermanentlyFencesTheOldTask(): Unit = runBlocking {
        val room = Room.inMemoryDatabaseBuilder(context, ClientDatabase::class.java).build()
        try {
            val dao = room.uploads()
            val row = UploadRecord("owned-restart", 7900001, "owned", "owned", 0, "content://fixture.invalid/source", "owned.bin", "blob", 8, 1,
                "/owned.bin", "/owned.bin", "/", "Owned", remoteCreated = true, status = "paused", uploaded = 4, createdAt = 1, updatedAt = 1)
            dao.insert(row)
            assertEquals(1, dao.command(row.id, "queued", 2))
            assertEquals(0, dao.beginCancelAtGeneration(row.id, row.generation, 3))
            assertEquals(1, dao.pause(row.id, 4))
            val before = dao.get(row.id)!!
            assertEquals(1, dao.beginCancelAtGeneration(row.id, before.generation, 5))
            val cleaning = dao.get(row.id)!!
            assertEquals(0, dao.restarted(row.id, cleaning.generation, 6))
            assertEquals(1, dao.canceled(row.id, cleaning.generation, "canceled", 4, "", 7))
            assertEquals(1, dao.restarted(row.id, cleaning.generation, 8))
            assertEquals(0, dao.restarted(row.id, cleaning.generation, 9))
            assertEquals(0, dao.beginCancel(row.id, 9)); assertEquals(0, dao.command(row.id, "queued", 9))
            assertEquals(0, dao.claim(row.id, 9)); assertEquals(0, dao.reauthorize(row.id, cleaning.generation, row.sourceUri, 9))
            assertEquals(0, dao.progress(row.id, before.generation, 8, 9))
            assertEquals(0, dao.canceled(row.id, cleaning.generation, "canceled", 8, "", 9))
            val ended = dao.get(row.id)!!
            assertEquals("restarted", ended.status); assertEquals(4L, ended.uploaded)
            assertFalse(ended.canResume); assertFalse(ended.canRestart); assertFalse(ended.canReselectSource)
            assertEquals(row.targetWire, ended.targetWire); assertEquals(row.accountKey, ended.accountKey)
        } finally { room.close() }
    }
    @Test fun cancellationFencesOldWritesPauseAndCleanupAcknowledgements(): Unit = runBlocking {
        val room = Room.inMemoryDatabaseBuilder(context, ClientDatabase::class.java).build()
        try {
            val dao = room.uploads()
            val row = UploadRecord("owned-cancel", 7900001, "owned", "owned", 0, "content://fixture.invalid/missing", "owned.bin", "blob", 8, 1,
                "/owned.bin", "/owned.bin", "/", "Owned", createdAt = 1, updatedAt = 1)
            dao.insert(row); assertEquals(1, dao.claim(row.id, 2))
            val old = dao.get(row.id)!!.generation
            assertEquals(1, dao.progress(row.id, old, 4, 3))
            assertEquals(1, dao.beginCancel(row.id, 4))
            val cancellation = dao.get(row.id)!!.generation
            assertEquals(0, dao.progress(row.id, old, 8, 5))
            assertEquals(0, dao.finish(row.id, old, "completed", "", 5))
            assertEquals(0, dao.pause(row.id, 5))
            assertEquals(0, dao.command(row.id, "queued", 5))
            assertEquals(1, dao.canceled(row.id, cancellation, "cancel_failed", 4, "owned retry", 6))
            assertEquals(1, dao.beginCancel(row.id, 7))
            assertEquals(0, dao.canceled(row.id, cancellation, "canceled", 4, "", 8))
            val latest = dao.get(row.id)!!.generation
            assertEquals(1, dao.canceled(row.id, latest, "canceled", 4, "", 9))
            assertEquals(0, dao.beginCancel(row.id, 10))
            assertEquals(0, dao.command(row.id, "queued", 10))
            assertFalse(dao.get(row.id)!!.canResume)
            assertEquals(4L, dao.get(row.id)!!.uploaded)
        } finally { room.close() }
    }
    @Test fun migrationAddsUploadLedgerWithoutChangingExistingDownloadBytesOrDirectory(): Unit = runBlocking {
        val name = "fileway-owned-upload-migration-${UUID.randomUUID()}.db"
        var room: ClientDatabase? = null
        try {
            val schema = JSONObject(InstrumentationRegistry.getInstrumentation().context.assets
                .open("io.github.kkwans.nasfilebrowser.data.ClientDatabase/7.json").bufferedReader().use { it.readText() }).getJSONObject("database")
            SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null).use { old ->
                val entities = schema.getJSONArray("entities")
                for (index in 0 until entities.length()) {
                    val entity = entities.getJSONObject(index); val table = entity.getString("tableName")
                    old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                    val indices = entity.optJSONArray("indices") ?: org.json.JSONArray()
                    for (j in 0 until indices.length()) old.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                }
                old.execSQL("INSERT INTO downloads VALUES ('owned-id',7300001,'owned-account','owned-profile',2,'/owned.mkv','/%FF.mkv','owned.mkv','video',8,'owned-modified','8/owned-modified','Owned fixture','','content://fixture.invalid/document/partial','paused',4,1,2,3,'owned-error',42000,60000,'owned/nested')")
                old.version = 7
            }
            room = Room.databaseBuilder(context, ClientDatabase::class.java, name).addMigrations(UploadMigration()).build()
            val old = room.downloads().get("owned-id")!!
            assertEquals(4L, old.downloaded); assertEquals("/%FF.mkv", old.wirePath); assertEquals(42000L, old.positionMs)
            assertEquals("content://fixture.invalid/document/partial", old.localUri); assertEquals("owned/nested", old.relativeDirectory)
            val row = UploadRecord("owned-upload", 7900001, "owned-account", "owned-profile", 2, "content://fixture.invalid/source", "owned.bin",
                "application/octet-stream", 8, 1, "/owned.bin", "/owned.bin", "/", "Owned fixture", createdAt = 1, updatedAt = 1)
            val dao = room.uploads(); dao.insert(row); assertEquals(1, dao.claim(row.id, 2))
            val generation = dao.get(row.id)!!.generation
            assertEquals(1, dao.created(row.id, generation, 3)); assertEquals(1, dao.progress(row.id, generation, 4, 3))
            assertEquals(1, dao.command(row.id, "paused", 4)); assertEquals(0, dao.progress(row.id, generation, 8, 5))
            assertEquals(0, dao.finish(row.id, generation, "completed", "", 5))
            assertEquals(4L, dao.get(row.id)!!.uploaded); assertTrue(dao.get(row.id)!!.remoteCreated)
        } finally { room?.close(); context.deleteDatabase(name) }
    }
}
