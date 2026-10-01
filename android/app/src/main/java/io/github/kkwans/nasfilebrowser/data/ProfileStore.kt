package io.github.kkwans.nasfilebrowser.data

import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.util.UUID

/** SQLite holds metadata only. JWTs remain in the Keystore-backed vault. */
class ProfileStore(private val database: ClientDatabase, private val vault: CredentialVault) {
    private val dao = database.profiles()
    val profiles = dao.profiles()

    suspend fun save(input: ServerProfile): ServerProfile {
        require(input.name.isNotBlank()) { "请输入服务器名称" }
        val address = input.address.trim()
        val uri = runCatching { URI(address) }.getOrNull()
        require(uri != null && uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank()
            && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null && uri.port in -1..65535) { "请输入包含 http:// 或 https:// 的完整服务器地址" }
        return database.withTransaction {
            val old = dao.profile(input.id)
            val sourceChanged = old != null && (old.address != address || old.backend != input.backend)
            val profile = input.copy(name = input.name.trim(), address = address,
                sourceRevision = (old?.sourceRevision ?: 0) + if (sourceChanged) 1 else 0,
                updatedAt = System.currentTimeMillis())
            dao.saveProfile(profile)
            profile
        }
    }

    suspend fun profile(id: String) = dao.profile(id)
    suspend fun accounts(profile: ServerProfile) = dao.accounts(profile.id, profile.sourceRevision)
    suspend fun directory(account: AccountRecord) = dao.directory(account.key)
    suspend fun saveDirectory(account: AccountRecord, path: String, wirePath: String) = dao.saveDirectory(DirectoryState(account.key, path, wirePath))

    // Call only after the source has authenticated this identity. These fields
    // select local storage; service permissions always remain server-enforced.
    suspend fun saveLogin(profile: ServerProfile, userId: Long, username: String, token: String): AccountRecord = withContext(Dispatchers.IO) {
        require(profile.backend == BackendKind.NAS && userId >= 0 && username.isNotBlank() && token.isNotBlank()) { "服务器账号信息无效" }
        var createdRef: String? = null
        try {
            database.withTransaction {
                val current = dao.profile(profile.id)
                check(current != null && current.sourceRevision == profile.sourceRevision && current.address == profile.address
                    && current.backend == profile.backend && current.network == profile.network) { "服务器档案已变化，请重新连接" }
                val key = "${profile.id}/${profile.sourceRevision}/$userId"
                val old = dao.account(key)
                val ref = old?.credentialRef ?: "service-${UUID.randomUUID()}".also { createdRef = it }
                vault.write(ref, token.toByteArray(Charsets.UTF_8))
                val account = AccountRecord(key, profile.id, profile.sourceRevision, userId, username, ref, System.currentTimeMillis())
                dao.saveAccount(account)
                account
            }
        } catch (error: Exception) {
            createdRef?.let { vault.remove(it) }
            throw error
        }
    }

    suspend fun token(profile: ServerProfile, account: AccountRecord): String? = withContext(Dispatchers.IO) {
        if (account.profileId != profile.id || account.sourceRevision != profile.sourceRevision) return@withContext null
        val current = dao.profile(profile.id) ?: return@withContext null
        if (current.sourceRevision != profile.sourceRevision || current.address != profile.address || current.backend != profile.backend
            || current.network != profile.network) return@withContext null
        val stored = dao.account(account.key) ?: return@withContext null
        if (stored.profileId != profile.id || stored.sourceRevision != profile.sourceRevision || stored.userId != account.userId) return@withContext null
        vault.read(stored.credentialRef)?.toString(Charsets.UTF_8)
    }

    suspend fun signOut(account: AccountRecord) = withContext(Dispatchers.IO) {
        dao.account(account.key)?.let { vault.remove(it.credentialRef) }
    }

    suspend fun remove(profile: ServerProfile) = withContext(Dispatchers.IO) {
        database.withTransaction {
            // A failed credential deletion leaves the profile visible for retry.
            dao.credentialRefs(profile.id).forEach(vault::remove)
            dao.deleteProfile(profile.id)
        }
    }
}
