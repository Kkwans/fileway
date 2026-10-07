package io.github.kkwans.nasfilebrowser.player

import org.junit.Assert.*
import org.junit.Test

class PlaybackTraceTest {
    @Test fun startupObservationsKeepElapsedTimeAndOpenAttempt() {
        var now = 1000L
        val trace = PlaybackTrace({ now })
        trace.record(PlaybackTraceAction.ENGINE_READY)
        now += 20
        trace.beginOpen(3000)
        now += 75
        trace.record(PlaybackTraceAction.FIRST_CLOCK, 3020.0)
        val entries = trace.snapshot()
        assertEquals(listOf(0L, 20L, 95L), entries.map { it.elapsedMs })
        assertEquals(listOf(0L, 1L, 1L), entries.map { it.openAttempt })
        assertEquals(3000.0, entries[1].value!!, 0.0)
    }

    @Test fun repeatedOpensEvictOldEventsWithoutMutatingSnapshots() {
        val trace = PlaybackTrace({ 0L }, capacity = 3)
        trace.beginOpen(0)
        trace.record(PlaybackTraceAction.PLAY_REQUEST)
        val saved = trace.snapshot()
        trace.record(PlaybackTraceAction.STOP_RETURNED)
        trace.beginOpen(50)
        trace.record(PlaybackTraceAction.PLAYING)
        assertEquals(listOf(3L, 4L, 5L), trace.snapshot().map { it.sequence })
        assertEquals(listOf(1L, 2L, 2L), trace.snapshot().map { it.openAttempt })
        assertEquals(listOf(1L, 2L), saved.map { it.sequence })
    }

    @Test fun disabledReleaseTraceRetainsNoObservations() {
        val trace = PlaybackTrace({ 0L }, enabled = false)
        repeat(1000) { trace.beginOpen(it.toLong()); trace.record(PlaybackTraceAction.BUFFERING, 100.0) }
        assertTrue(trace.snapshot().isEmpty())
    }

    @Test fun malformedNumericObservationsCannotCorruptElapsedOrSerialization() {
        var now = 100L
        val trace = PlaybackTrace({ now })
        now = 200
        trace.record(PlaybackTraceAction.RATE_REPORTED, Double.NaN)
        now = 50
        trace.record(PlaybackTraceAction.BUFFERING, Double.POSITIVE_INFINITY)
        assertEquals(listOf(100L, 100L), trace.snapshot().map { it.elapsedMs })
        assertTrue(trace.snapshot().all { it.value == null })
    }
}
