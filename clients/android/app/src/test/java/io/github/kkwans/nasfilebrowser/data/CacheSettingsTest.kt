package io.github.kkwans.nasfilebrowser.data
import org.junit.Assert.*
import org.junit.Test
class CacheSettingsTest {
    @Test fun defaultsAndBoundaries() {
        val config = CacheSettings(); config.validate()
        assertEquals(500L, config.thumbnailMB); assertEquals(1024L, config.playbackMB)
        assertEquals(24, config.cleanupHours); assertEquals(1024L, config.imageMB)
        assertEquals(ImageQuality.ORIGINAL, config.imageQuality)
        config.copy(thumbnailMB = 10240, playbackMB = 0, imageMB = 51200, cleanupHours = 720).validate()
    }
    @Test fun invalidQuotasAreRejected() {
        listOf(CacheSettings(thumbnailMB = -1), CacheSettings(playbackMB = 10241), CacheSettings(imageMB = 51201), CacheSettings(cleanupHours = 0)).forEach {
            try { it.validate(); fail("invalid quota accepted") } catch (_: IllegalArgumentException) { }
        }
    }
}
