package io.github.kkwans.nasfilebrowser

import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MpvComparisonTest {
    @get:Rule val activity = ActivityScenarioRule(EngineProbeActivity::class.java)
    @Test fun threeEnginesUseTheSameLeaseSurfaceAndAssertions(): Unit = runBlocking {
        EngineComparisonHarness(activity, mapOf("mpv" to ::MpvProbe)).run()
    }
}
