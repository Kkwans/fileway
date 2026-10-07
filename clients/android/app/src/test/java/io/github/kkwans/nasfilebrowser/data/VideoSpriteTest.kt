package io.github.kkwans.nasfilebrowser.data

import org.junit.Assert.*
import org.junit.Test

class VideoSpriteTest {
    @Test fun timelineUsesIntervalAndNeverSelectsAnEmptyLastRowTile() {
        val sprite = VideoSprite(23, 10, 160, 90, 5.0)
        assertEquals(0 to 0, sprite.tileAt(-1))
        assertEquals(1440 to 0, sprite.tileAt(49_999))
        assertEquals(0 to 90, sprite.tileAt(50_000))
        assertEquals(320 to 180, sprite.tileAt(Long.MAX_VALUE))
        assertEquals(1600, sprite.sheetWidth)
        assertEquals(270, sprite.sheetHeight)
    }
    @Test fun rejectsUnsafeGeometryAndInvalidTimeline() {
        listOf(
            { VideoSprite(101, 10, 160, 90, 1.0) },
            { VideoSprite(1, 2, 160, 90, 1.0) },
            { VideoSprite(2, 2, Int.MAX_VALUE, 90, 1.0) },
            { VideoSprite(2, 2, 160, 90, Double.NaN) },
            { VideoSprite(2, 2, 160, 90, 0.0) }
        ).forEach { invalid -> assertThrows(IllegalArgumentException::class.java) { invalid() } }
    }
}
