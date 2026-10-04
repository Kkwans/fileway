package io.github.kkwans.nasfilebrowser.data
import org.junit.Assert.*
import org.junit.Test
class PlaybackRateTest {
    @Test fun holdRestoresCustomRateAndReleaseIsIdempotent() {
        var rate = 1.25f
        val hold = HeldPlaybackRate({ rate }, { rate = it })
        hold.start(3f); assertEquals(3f, rate, 0f)
        hold.start(5f); assertEquals(3f, rate, 0f)
        hold.release(); assertEquals(1.25f, rate, 0f)
        rate = 2f; hold.release(); assertEquals(2f, rate, 0f)
    }
    @Test fun onlyFiniteSupportedRatesAreAccepted() {
        listOf("", "0", "5.01", "NaN", "Infinity", "-1").forEach { assertNull(parsePlaybackRate(it)) }
        assertEquals(.1f, parsePlaybackRate("0.1")!!, 0f)
        assertEquals(5f, parsePlaybackRate("5")!!, 0f)
    }
}
