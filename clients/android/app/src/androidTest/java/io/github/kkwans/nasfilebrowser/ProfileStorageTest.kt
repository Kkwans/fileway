package io.github.kkwans.nasfilebrowser

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.kkwans.nasfilebrowser.data.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import android.util.Base64
import org.json.JSONObject

@RunWith(AndroidJUnit4::class)
class ProfileStorageTest {
    private lateinit var database: ClientDatabase
    private lateinit var vault: CredentialVault
    private lateinit var store: ProfileStore

    @Before fun prepare() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, ClientDatabase::class.java).build()
        vault = CredentialVault(context)
        store = ProfileStore(database, vault)
    }
    @After fun cleanup() = runBlocking {
        try { store.profiles.first().forEach { store.remove(it) } } finally { database.close() }
    }

    private fun jwt(user: Long, issued: Long): String {
        val payload = JSONObject().put("iat", issued).put("user", JSONObject().put("id", user).put("username", "owned-viewer"))
        return "owned." + Base64.encodeToString(payload.toString().toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING) + ".signature"
    }

    @Test fun refreshedTokenSurvivesReopenAndLateRenewalCannotUndoSignOut() = runBlocking {
        val profile = store.save(ServerProfile(name = "Owned renewal", address = "https://renew.example.test"))
        val account = store.saveLogin(profile, 7, "owned-viewer", jwt(7, 10))
        assertTrue(store.refreshToken(profile, account, jwt(7, 20)))
        val reopened = ProfileStore(database, vault)
        assertEquals(jwt(7, 20), reopened.token(profile, account))
        assertFalse(reopened.refreshToken(profile, account, jwt(7, 10)))
        assertEquals(jwt(7, 20), reopened.token(profile, account))
        reopened.signOut(account)
        assertFalse(store.refreshToken(profile, account, jwt(7, 30)))
        assertNull(store.token(profile, account))
    }

    @Test fun renewalCannotCrossSourceRevisionOrPrincipal() = runBlocking {
        val profile = store.save(ServerProfile(name = "Owned renewal", address = "https://renew.example.test"))
        val other = store.save(ServerProfile(name = "Other source", address = "https://other.example.test"))
        val account = store.saveLogin(profile, 7, "owned-viewer", jwt(7, 10))
        assertFalse(store.refreshToken(other, account, jwt(7, 20)))
        assertTrue(runCatching { store.refreshToken(profile, account, jwt(8, 20)) }.isFailure)
        store.save(profile.copy(address = "https://changed.example.test"))
        assertFalse(store.refreshToken(profile, account, jwt(7, 20)))
        assertEquals(jwt(7, 10), vault.read(account.credentialRef)?.toString(Charsets.UTF_8))
    }

    @Test fun accountsAndOpaqueDirectoriesAreIsolatedAcrossProfiles() = runBlocking {
        val a = store.save(ServerProfile(name = "NAS A", address = "http://a.example.test:8080/base"))
        val b = store.save(ServerProfile(name = "NAS B", address = "https://b.example.test"))
        val a1 = store.saveLogin(a, 1, "user-one", "test-token-a1")
        val a2 = store.saveLogin(a, 2, "user-two", "test-token-a2")
        val b1 = store.saveLogin(b, 1, "user-one", "test-token-b1")
        store.saveDirectory(a1, "/收藏", "/%ed%a0%80%2B")
        store.saveDirectory(a2, "/other", "/other")
        assertEquals("/%ed%a0%80%2B", store.directory(a1)?.wirePath)
        assertEquals("/other", store.directory(a2)?.path)
        assertNull(store.directory(b1))
        assertEquals("test-token-a1", store.token(a, a1))
        assertEquals("test-token-b1", store.token(b, b1))
        assertNull(store.token(b, a1))
        assertEquals(2, store.accounts(a).size)
        assertEquals(1, store.accounts(b).size)
    }

    @Test fun parentUpsertsPreserveDirectoriesAndAddressChangesRejectLateLogin() = runBlocking {
        val original = store.save(ServerProfile(name = "NAS", address = "https://nas.example.test"))
        val account = store.saveLogin(original, 7, "viewer", "old-test-token")
        store.saveDirectory(account, "/films", "/films")
        val renamed = store.save(original.copy(name = "Family NAS"))
        assertEquals(original.sourceRevision, renamed.sourceRevision)
        val renewed = store.saveLogin(renamed, 7, "viewer", "renewed-test-token")
        assertEquals(account.key, renewed.key)
        assertEquals(account.credentialRef, renewed.credentialRef)
        assertEquals("/films", store.directory(renewed)?.path)
        val changed = store.save(renamed.copy(address = "https://other.example.test"))
        assertTrue(changed.sourceRevision > original.sourceRevision)
        assertTrue(store.accounts(changed).isEmpty())
        assertNull(store.token(changed, account))
        assertNull(store.token(renamed, account))
        assertTrue(runCatching { store.saveLogin(renamed, 7, "viewer", "late-test-token") }.isFailure)
        assertEquals("renewed-test-token", vault.read(account.credentialRef)?.toString(Charsets.UTF_8))
    }

    @Test fun signOutAndProfileRemovalDoNotAffectOtherSources() = runBlocking {
        val a = store.save(ServerProfile(name = "A", address = "http://a.example.test"))
        val b = store.save(ServerProfile(name = "B", address = "http://b.example.test"))
        val a1 = store.saveLogin(a, 1, "viewer", "test-token-a")
        val b1 = store.saveLogin(b, 1, "viewer", "test-token-b")
        store.saveDirectory(a1, "/a", "/a")
        store.saveDirectory(b1, "/b", "/b")
        store.signOut(a1)
        assertNull(store.token(a, a1))
        assertEquals("/a", store.directory(a1)?.path)
        store.remove(a)
        assertNull(database.profiles().account(a1.key))
        assertNull(store.directory(a1))
        assertEquals("test-token-b", store.token(b, b1))
        assertEquals("/b", store.directory(b1)?.path)
    }

    @Test fun unsupportedWindowsProfilesCanBeSavedWithoutPretendingLoginWorks() = runBlocking {
        val profile = store.save(ServerProfile(name = "Windows", address = "http://windows.example.test:8080", backend = BackendKind.WINDOWS))
        assertEquals(BackendKind.WINDOWS, store.profile(profile.id)?.backend)
        assertTrue(runCatching { store.saveLogin(profile, 1, "viewer", "test-token") }.isFailure)
        assertTrue(store.accounts(profile).isEmpty())
        assertTrue(runCatching { store.save(profile.copy(address = "https://user:secret@example.test")) }.isFailure)
    }
}
