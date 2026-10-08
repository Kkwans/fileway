package io.github.kkwans.nasfilebrowser

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.kkwans.nasfilebrowser.download.DownloadTarget
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Isolated preferences and nonexistent owned provider; never changes user grants or download records. */
@RunWith(AndroidJUnit4::class)
class DownloadDirectoryTest {
    @Test fun reauthorizationRejectsOtherTreesAndMissingGrantsWithoutChangingDefault() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val preference = "owned-download-directory-${UUID.randomUUID()}"
        val isolated = object : ContextWrapper(app) {
            override fun getSharedPreferences(name: String, mode: Int) = app.getSharedPreferences(preference, mode)
        }
        val original = Uri.parse("content://fileway.owned.invalid/tree/volume%3Afolder")
        try {
            isolated.getSharedPreferences("download-target", Context.MODE_PRIVATE).edit().putString("tree", "current-default").commit()
            val target = DownloadTarget(isolated)
            assertTrue(DownloadTarget.sameTree(original, Uri.parse("content://fileway.owned.invalid/tree/volume:folder")))
            for (wrong in listOf("content://fileway.owned.invalid/tree/volume%3Aother", "content://another.owned.invalid/tree/volume%3Afolder", "file:///tree/volume:folder")) {
                assertFalse(DownloadTarget.sameTree(original, Uri.parse(wrong)))
                assertThrows(IllegalArgumentException::class.java) { target.reauthorizeTree(original.toString(), Uri.parse(wrong)) }
                assertEquals("current-default", target.selectedTree())
            }
            assertFalse(target.hasAccess(original.toString()))
            assertThrows(IllegalStateException::class.java) { target.reauthorizeTree(original.toString(), original) }
            assertEquals("current-default", target.selectedTree())
            assertTrue(target.hasAccess(""))
        } finally { app.deleteSharedPreferences(preference) }
    }
}
