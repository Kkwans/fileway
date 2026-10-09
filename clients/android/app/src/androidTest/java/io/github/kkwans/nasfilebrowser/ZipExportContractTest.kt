package io.github.kkwans.nasfilebrowser

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.kkwans.nasfilebrowser.data.ClientDatabase
import io.github.kkwans.nasfilebrowser.download.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.URI
import java.net.URLDecoder

@RunWith(AndroidJUnit4::class)
class ZipExportContractTest {
    @Test fun zipIdentityQueryIsRepeatedAndGenerationFencesFinalSize(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, ClientDatabase::class.java).build()
        try {
            val identity = JSONObject().put("version", 1).put("wires", JSONArray().put("/docs/comma%2Cname").put("/docs/100%25%20%2B")).toString()
            val row = DownloadRecord("owned-zip", 7300001, "owned", "owned-profile", 0, "/docs", "/docs", "owned.zip", ZIP_EXPORT_TYPE,
                -1, "", identity, "Owned fixture", "", createdAt = 1, updatedAt = 1)
            val endpoint = URI(zipExportEndpoint(row))
            val paths = endpoint.rawQuery.split('&').filter { it.startsWith("fileWirePath=") }.map { URLDecoder.decode(it.substringAfter('='), "UTF-8") }
            assertEquals(listOf("/docs/comma%2Cname", "/docs/100%25%20%2B"), paths)
            val dao = database.downloads(); dao.insert(row)
            assertEquals(1, dao.claim(row.id, 2)); val generation = dao.get(row.id)!!.generation
            assertEquals(1, dao.progress(row.id, generation, 400, 3))
            assertEquals(1, dao.command(row.id, "paused", 4))
            assertEquals(0, dao.finalExport(row.id, generation, 400, 5))
            assertEquals(-1L, dao.get(row.id)!!.expectedSize)
            assertEquals(1, dao.command(row.id, "queued", 6)); assertEquals(1, dao.claim(row.id, 7))
            val restarted = dao.get(row.id)!!
            assertEquals(0L, restarted.downloaded); assertEquals(-1L, restarted.expectedSize)
            assertEquals(1, dao.finalExport(row.id, restarted.generation, 1234, 8))
            assertEquals(1, dao.finish(row.id, restarted.generation, "completed", "", 9))
            assertEquals(1234L, dao.get(row.id)!!.expectedSize); assertEquals(1234L, dao.get(row.id)!!.downloaded)
        } finally { database.close() }
    }

    @Test fun ordinaryDownloadsRetainTheirKnownSizeAndContiguousPrefixOnClaim(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, ClientDatabase::class.java).build()
        try {
            val row = DownloadRecord("owned-video", 7300001, "owned", "owned-profile", 0, "/video", "/video", "video.mkv", "video", 1000,
                "owned-modified", "1000/owned", "Owned fixture", "", downloaded = 400, createdAt = 1, updatedAt = 1)
            val dao = database.downloads(); dao.insert(row); assertEquals(1, dao.claim(row.id, 2))
            val saved = dao.get(row.id)!!
            assertEquals(1000L, saved.expectedSize); assertEquals(400L, saved.downloaded)
            assertEquals(0, dao.finalExport(row.id, saved.generation, 777, 3))
        } finally { database.close() }
    }
}
