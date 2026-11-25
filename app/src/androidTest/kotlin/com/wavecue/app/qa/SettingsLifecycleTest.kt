package com.wavecue.app.qa

import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wavecue.app.testing.TestHarness
import com.wavecue.app.ui.TestTags
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsLifecycleTest : QaTest() {

    @Test
    fun settingsSurviveActivityRecreation() {
        openSettings()
        scrollToText("Follow playhead")
        compose.onNodeWithTag(TestTags.SettingsFollowPlayhead).assertIsOn()

        compose.onNodeWithTag(TestTags.SettingsFollowPlayhead).performClick()
        compose.onNodeWithTag(TestTags.SettingsFollowPlayhead).assertIsOff()

        compose.activityRule.scenario.recreate()
        openSettings()
        scrollToText("Follow playhead")
        compose.onNodeWithTag(TestTags.SettingsFollowPlayhead).assertIsOff()
    }

    @Test
    fun playbackContinuesWhileTheAppIsInTheBackground() {
        playTrack(TRACK_OPENING)
        waitUntil(15_000) { TestHarness.isPlaying(context) }
        val before = positionMs()

        device.pressHome()
        Thread.sleep(3_000)
        assertTrue("playback stopped when the app went to the background", TestHarness.isPlaying(context))

        device.executeShellCommand("am start -W -n com.wavecue.app/.MainActivity")
        compose.waitForIdle()
        waitUntilText(TRACK_OPENING, 20_000, substring = true)
        waitUntil(10_000) { positionMs() > before + 1_000 }
    }

    private fun openSettings() {
        compose.onNodeWithTag(TestTags.NavSettings).performClick()
        waitUntilText("Playback fidelity, silence detection and data", 15_000)
    }
}
