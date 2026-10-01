package io.github.kkwans.nasfilebrowser

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.kkwans.nasfilebrowser.core.EmbeddedNetwork
import io.github.kkwans.nasfilebrowser.data.CredentialVault
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class EmbeddedStorageTest {
    @Test fun keystoreRecordsSurviveRecreationAndRejectTampering() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = "instrumentation-${UUID.randomUUID()}"
        val value = "private-test-value".toByteArray()
        val vault = CredentialVault(context)
        try {
            vault.write(id, value)
            assertArrayEquals(value, CredentialVault(context).read(id))
            val hash = MessageDigest.getInstance("SHA-256").digest(id.toByteArray()).joinToString("") { "%02x".format(it) }
            val file = File(context.noBackupFilesDir, "vault/$hash.bin")
            val ciphertext = file.readBytes()
            assertFalse(ciphertext.toString(Charsets.ISO_8859_1).contains(value.toString(Charsets.UTF_8)))
            ciphertext[ciphertext.lastIndex] = (ciphertext.last().toInt() xor 1).toByte()
            file.writeBytes(ciphertext)
            assertTrue("Corruption must not silently reset credentials", runCatching { vault.read(id) }.isFailure)
            assertArrayEquals(ciphertext, file.readBytes())
        } finally { vault.remove(id) }
    }

    @Test fun passiveConfigurationDoesNotEnrollOrConnectNode() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val first = EmbeddedNetwork(context).status()
        val second = EmbeddedNetwork(context).status()
        assertEquals("Configured", first.state)
        assertEquals(first.state, second.state)
        assertFalse(first.connected)
        assertTrue(first.authUrl.isEmpty())
        assertTrue(first.ips.isEmpty())
    }
}
