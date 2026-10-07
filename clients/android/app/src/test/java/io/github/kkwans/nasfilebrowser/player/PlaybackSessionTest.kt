package io.github.kkwans.nasfilebrowser.player

import org.junit.Assert.*
import org.junit.Test

class PlaybackSessionTest {
    @Test fun replacementAndStopRejectCallbacksFromEarlierMedia() {
        val session = PlaybackSession()
        val old = session.open(true)
        val current = session.open(false)
        assertFalse(session.accepts(old))
        assertTrue(session.accepts(current))
        assertFalse(session.wantsPlay)
        session.stop()
        assertFalse(session.accepts(current))
        session.play()
        assertFalse(session.wantsPlay)
    }

    @Test fun pauseIntentAndPreferredSpeedSurviveSettingsAndMediaChanges() {
        val session = PlaybackSession()
        session.open(true)
        session.pause()
        assertTrue(session.selectRate(1.5f))
        assertFalse(session.wantsPlay)
        session.open(false)
        assertEquals(1.5f, session.preferredRate, .0001f)
        assertFalse(session.wantsPlay)
        session.play()
        assertTrue(session.wantsPlay)
    }

    @Test fun invalidRateCannotReplaceTheLastValidPreference() {
        val session = PlaybackSession()
        session.selectRate(1.25f)
        for (rate in listOf(Float.NaN, Float.POSITIVE_INFINITY, 0f, 5.1f)) assertFalse(session.selectRate(rate))
        assertEquals(1.25f, session.preferredRate, .0001f)
    }
}
