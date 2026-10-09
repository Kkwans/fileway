package io.github.kkwans.nasfilebrowser

import android.content.Context
import android.content.ContextWrapper
import android.util.AtomicFile
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.kkwans.nasfilebrowser.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID

private suspend fun ownedVaultFixture(block: suspend (Context, ClientDatabase) -> Unit) {
    val target = InstrumentationRegistry.getInstrumentation().targetContext
    val owned = File(target.cacheDir, "owned-vault-commit-${UUID.randomUUID()}")
    check(owned.mkdir())
    val isolated = object : ContextWrapper(target) {
        override fun getNoBackupFilesDir(): File = owned
    }
    val database = Room.inMemoryDatabaseBuilder(isolated, ClientDatabase::class.java).build()
    try { block(isolated, database) }
    finally { database.close(); check(owned.deleteRecursively()) }
}

private fun vaultEntry(context: Context, id: String): File {
    // Existing EmbeddedStorageTest uses this same product ID derivation.
    val name = MessageDigest.getInstance("SHA-256").digest(id.toByteArray()).joinToString("") { "%02x".format(it) }
    return File(context.noBackupFilesDir, "vault/$name.bin")
}

/** Both methods use only the original public constructor, including when a
 * new test APK runs against an installed old product for the directory RED.
 * All files/Room rows are isolated; no real vault/active session or key is deleted.
 */
@RunWith(AndroidJUnit4::class)
class CredentialVaultCommitFailureTest {
    @Test fun nonemptyPasswordTargetCannotAcknowledgeAnUnpublishedWrite(): Unit = runBlocking {
        ownedVaultFixture { context, database ->
            val store = ProfileStore(database, CredentialVault(context))
            val profile = store.save(ServerProfile(name = "Owned commit fixture", address = "https://owned.invalid"))
            val account = store.saveLogin(profile, 7, "owned-viewer", "owned-token")
            val blocked = vaultEntry(context, account.credentialRef + ":password")
            check(blocked.mkdir())
            val sentinel = File(blocked, "owned-sentinel")
            val bytes = byteArrayOf(17, 29, 43)
            sentinel.writeBytes(bytes)

            // .new is writable, but a nonempty final target blocks the real
            // AtomicFile.finishWrite rename, which AOSP reports only in logcat.
            val failure = runCatching { store.rememberPassword(profile, account, "owned-password") }.exceptionOrNull()
            assertNotNull("Local password save must not acknowledge an unpublished write", failure)
            assertFalse(failure!!.message.orEmpty().contains("owned-password"))
            assertArrayEquals(bytes, sentinel.readBytes())
            assertEquals("owned-token", ProfileStore(database, CredentialVault(context)).token(profile, account))

            check(sentinel.delete()); check(blocked.delete())
            store.rememberPassword(profile, account, "owned-password")
            assertEquals("owned-password", ProfileStore(database, CredentialVault(context)).password(profile, account))
        }
    }

    @Test fun normalPasswordOverwriteReopensAndDoesNotChangeTheToken(): Unit = runBlocking {
        ownedVaultFixture { context, database ->
            val store = ProfileStore(database, CredentialVault(context))
            val profile = store.save(ServerProfile(name = "Owned normal fixture", address = "https://owned.invalid"))
            val account = store.saveLogin(profile, 7, "owned-viewer", "owned-token")
            store.rememberPassword(profile, account, "owned-first-password")
            store.rememberPassword(profile, account, "owned-second-password")
            val reopened = ProfileStore(database, CredentialVault(context))
            assertEquals("owned-second-password", reopened.password(profile, account))
            assertEquals("owned-token", reopened.token(profile, account))
        }
    }
}

/** Green-only: separate class keeps the new constructor out of the RED class. */
@RunWith(AndroidJUnit4::class)
class CredentialVaultExistingBaseCommitFailureTest {
    @Test fun unpublishedReplacementReportsFailureAndPreservesReadableOldBase(): Unit = runBlocking {
        ownedVaultFixture { context, _ ->
            val id = "owned-existing-entry"
            val old = "owned-old-value".toByteArray()
            val replacement = "owned-replacement-value".toByteArray()
            CredentialVault(context).write(id, old)
            val previousCiphertext = vaultEntry(context, id).readBytes()
            var finishCalls = 0
            val failing = CredentialVault(context) { base -> object : AtomicFile(base) {
                override fun finishWrite(stream: FileOutputStream) {
                    finishCalls++
                    // Deterministically remove only our prepared sibling after
                    // write/sync; execute the real platform's swallowed rename failure.
                    check(File(base.path + ".new").delete())
                    super.finishWrite(stream)
                }
            } }
            val failure = runCatching { failing.write(id, replacement) }.exceptionOrNull()
            assertEquals("Fault must reach finishWrite", 1, finishCalls)
            assertNotNull("Missing publication must not report success", failure)
            assertFalse(failure!!.message.orEmpty().contains("owned-replacement-value"))
            assertArrayEquals(previousCiphertext, vaultEntry(context, id).readBytes())
            assertArrayEquals(old, CredentialVault(context).read(id))
            assertFalse(File(vaultEntry(context, id).path + ".new").exists())
            CredentialVault(context).write(id, replacement)
            assertArrayEquals(replacement, CredentialVault(context).read(id))
        }
    }
}
