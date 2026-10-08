package io.github.kkwans.nasfilebrowser.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TreeMap

class SubtitleCacheEvictionTest {
    @Test fun backwardSeekKeepsRereadDisplayAndClearEventsUnderPressure() {
        val packets = TreeMap<Long, String>()
        var position = 100L
        fun append(time: Long, content: String) {
            packets[time] = content
            while (packets.size > 4) {
                val floor = packets.floorKey(position)
                val victim = subtitleCacheVictim(packets.keys.asSequence(), position,
                    current = { it == floor }, selected = { true }, timeUs = { it })!!
                packets.remove(victim)
            }
        }
        (100L..200L step 10).forEach { append(it, "future") }
        position = 10L
        append(10, "reread subtitle")
        append(11, "clear")
        (20L..90L step 10).forEach { append(it, "read ahead") }
        assertEquals("reread subtitle", packets.floorEntry(position).value)
        position = 11L
        append(210, "far future")
        assertEquals("clear", packets.floorEntry(position).value)
        assertTrue(packets.size <= 4)
    }

    @Test fun hardBudgetCanEvictUnselectedCurrentDisplayBeforeSelectedOne() {
        val victim = subtitleCacheVictim(sequenceOf("selected", "other"), 0,
            current = { true }, selected = { it == "selected" }, timeUs = { 0 })
        assertEquals("other", victim)
    }

    @Test fun farHistoryAndFutureAreEvictedBeforeNearUpcomingCue() {
        assertEquals(900L, subtitleCacheVictim(sequenceOf(0L, 90L, 100L, 110L, 900L), 100,
            current = { it == 100L }, selected = { true }, timeUs = { it }))
    }
}
