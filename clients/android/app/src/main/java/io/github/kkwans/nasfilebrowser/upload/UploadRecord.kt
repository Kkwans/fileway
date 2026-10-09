package io.github.kkwans.nasfilebrowser.upload

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/** A transfer is tied to its original account/target, independent of browsing. */
@Entity(tableName = "uploads", indices = [Index(value = ["jobId"], unique = true), Index(value = ["accountKey", "createdAt"])])
data class UploadRecord(@PrimaryKey val id: String, val jobId: Int, val accountKey: String, val profileId: String,
    val sourceRevision: Long, val sourceUri: String, val name: String, val mime: String, val expectedSize: Long, val sourceModified: Long,
    val targetPath: String, val targetWire: String, val parentWire: String, val sourceLabel: String,
    val overwrite: Boolean = false, val replacedIdentity: String = "", val protocol: String = "tus", val remoteCreated: Boolean = false,
    val status: String = "queued", val uploaded: Long = 0, val createdAt: Long, val updatedAt: Long,
    val generation: Long = 0, val error: String = "", val batchId: String = "", val batchName: String = "", val batchItems: Int = 0,
    val batchBytes: Long = 0, val folderUpload: Boolean = false) {
    val complete get() = status == "completed"
    val active get() = status in setOf("queued", "running")
}

@Dao interface UploadDao {
    @Query("SELECT * FROM uploads ORDER BY createdAt DESC") fun observe(): Flow<List<UploadRecord>>
    @Query("SELECT * FROM uploads WHERE id = :id") suspend fun get(id: String): UploadRecord?
    @Query("SELECT COALESCE(MAX(jobId), 7900000) FROM uploads") suspend fun lastJobId(): Int
    @Insert suspend fun insert(record: UploadRecord)
    @Query("UPDATE uploads SET status = 'running', generation = generation + 1, error = '', updatedAt = :now WHERE id = :id AND status IN ('queued', 'interrupted', 'running')")
    suspend fun claim(id: String, now: Long): Int
    @Query("UPDATE uploads SET remoteCreated = 1, updatedAt = :now WHERE id = :id AND generation = :generation AND status = 'running'")
    suspend fun created(id: String, generation: Long, now: Long): Int
    @Query("UPDATE uploads SET uploaded = :bytes, updatedAt = :now WHERE id = :id AND generation = :generation AND status = 'running'")
    suspend fun progress(id: String, generation: Long, bytes: Long, now: Long): Int
    @Query("UPDATE uploads SET status = :status, error = :error, updatedAt = :now WHERE id = :id AND generation = :generation AND status = 'running'")
    suspend fun finish(id: String, generation: Long, status: String, error: String, now: Long): Int
    @Query("UPDATE uploads SET status = :status, generation = generation + 1, error = '', updatedAt = :now WHERE id = :id AND status != 'completed'")
    suspend fun command(id: String, status: String, now: Long): Int
    @Query("DELETE FROM uploads WHERE id = :id AND status NOT IN ('queued', 'running')") suspend fun removeRecord(id: String): Int
}
