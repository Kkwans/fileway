package io.github.kkwans.nasfilebrowser.player

import org.junit.Assert.*
import org.junit.Test

class SeekDragSessionTest {
    @Test fun longMovieIsControllableAndReversingDoesNotAccumulateClockDrift() {
        val drag = SeekDragSession(7, 60_000, 9_813_824)
        assertEquals(90_000L, drag.preview(.25f))
        assertEquals(72_000L, drag.preview(.1f))
        assertEquals(48_000L, drag.preview(-.1f))
        assertEquals(60_000L, drag.preview(0f))
        assertNull(drag.finish(7, released = true, seekable = true))
    }

    @Test fun shortClipsBoundariesAndVeryLargeDurationsKeepTheirRealBounds() {
        val short = SeekDragSession(1, 6_000, 12_000)
        assertEquals(7_200L, short.preview(.1f))
        assertEquals(12_000L, short.preview(1f))
        assertEquals(0L, short.preview(-1f))
        val huge = SeekDragSession(2, Long.MAX_VALUE - 1, Long.MAX_VALUE)
        assertEquals(Long.MAX_VALUE, huge.preview(1f))
        assertEquals(Long.MAX_VALUE - 120_001, huge.preview(-1f))
    }

    @Test fun cancelChangedMediaOrUnavailableSeekNeverCommits() {
        for (case in listOf(Triple(7L, false, true), Triple(8L, true, true), Triple(7L, true, false))) {
            val drag = SeekDragSession(7, 60_000, 600_000)
            drag.preview(.1f)
            assertNull(drag.finish(case.first, case.second, case.third))
            assertNull("A cancelled drag cannot be resurrected", drag.finish(7, true, true))
        }
    }

    @Test fun onlyTheFinalPreviewCommitsOnceAndInvalidPointerValuesAreIgnored() {
        val drag = SeekDragSession(7, 60_000, 600_000)
        drag.preview(.25f)
        assertEquals(90_000L, drag.preview(Float.NaN))
        assertEquals(90_000L, drag.preview(Float.POSITIVE_INFINITY))
        assertEquals(72_000L, drag.preview(.1f))
        assertEquals(72_000L, drag.finish(7, true, true))
        assertNull(drag.finish(7, true, true))
        assertEquals(72_000L, drag.preview(-1f))
    }
}
