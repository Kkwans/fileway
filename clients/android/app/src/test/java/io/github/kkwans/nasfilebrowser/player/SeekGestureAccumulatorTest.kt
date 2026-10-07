package io.github.kkwans.nasfilebrowser.player

import org.junit.Assert.assertEquals
import org.junit.Test

class SeekGestureAccumulatorTest {
    @Test fun burstUsesPendingTargetAndCanReverseDirection() {
        val seek = SeekGestureAccumulator()
        assertEquals(30_000L, seek.next(20_000, 120_000, 10_000, 100))
        assertEquals(40_000L, seek.next(20_100, 120_000, 10_000, 700))
        assertEquals(30_000L, seek.next(20_200, 120_000, -10_000, 900))
        assertEquals(60_000L, seek.next(50_000, 120_000, 10_000, 2_000))
    }
    @Test fun boundariesAndExplicitTransportResetDoNotCarryOldTargets() {
        val seek = SeekGestureAccumulator()
        assertEquals(0L, seek.next(2_000, 12_000, -10_000, 100))
        assertEquals(10_000L, seek.next(2_000, 12_000, 10_000, 200))
        assertEquals(12_000L, seek.next(2_000, 12_000, 10_000, 300))
        seek.reset()
        assertEquals(3_000L, seek.next(13_000, 120_000, -10_000, 400))
    }
}
