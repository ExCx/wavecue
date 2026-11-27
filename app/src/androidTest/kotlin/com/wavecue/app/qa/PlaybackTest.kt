package com.wavecue.app.qa

import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wavecue.app.testing.TestHarness
import com.wavecue.app.ui.TestTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class PlaybackTest : QaTest() {

    @Test
    fun pauseHoldsThePlayheadAndResumeContinues() {
        playTrack(TRACK_OPENING)
        waitUntil(15_000) { TestHarness.isPlaying(context) }
        waitUntil(20_000) { abs(durationMs() - 32_000) <= 150 }
        assertEquals("0:32.000", textOf(TestTags.PlayerDuration).trim().removePrefix("/").trim())

        clickTag(TestTags.TransportToggle)
        waitUntil(5_000) { !TestHarness.isPlaying(context) }
        // Let the engine publish its final position before sampling.
        Thread.sleep(500)
        val held = positionMs()
        Thread.sleep(1_500)
        assertTrue(
            "playhead moved from ${held}ms to ${positionMs()}ms while paused",
            abs(positionMs() - held) <= 120,
        )

        clickTag(TestTags.TransportToggle)
        waitUntil(5_000) { TestHarness.isPlaying(context) }
        waitUntil(10_000) { positionMs() >= held + 700 }
    }

    @Test
    fun fiveSecondStepsAndPreviousSemantics() {
        playTrack(TRACK_OPENING)
        waitUntil(15_000) { TestHarness.isPlaying(context) }

        clickTag(TestTags.TransportBack5)
        waitUntil(10_000) { positionMs() <= 3_000 }

        clickTag(TestTags.TransportForward5)
        waitUntil(10_000) { positionMs() >= 3_500 }

        clickTag(TestTags.TransportPrevious)
        waitUntil(10_000) { positionMs() <= 3_000 }

        clickTag(TestTags.TransportForward5)
        waitUntil(10_000) { positionMs() >= 3_500 }

        clickTag(TestTags.TransportForward5)
        waitUntil(10_000) { positionMs() >= 8_000 }

        // Past three seconds, Previous restarts the current track.
        clickTag(TestTags.TransportPrevious)
        runCatching { waitUntil(10_000) { positionMs() <= 1_500 } }
        assertEquals(TRACK_OPENING, TestHarness.currentTrackName(context))

        // Under three seconds, Previous steps back through the folder queue.
        playTrack(TRACK_MIDDLE)
        runCatching { waitUntil(20_000) { TestHarness.currentTrackName(context) == TRACK_MIDDLE } }
        clickTag(TestTags.TransportToggle)
        waitUntil(5_000) { !TestHarness.isPlaying(context) }
        clickTag(TestTags.TransportBack5)
        clickTag(TestTags.TransportPrevious)
        runCatching { waitUntil(20_000) { TestHarness.currentTrackName(context) == TRACK_OPENING } }
        runCatching { waitUntilText(TRACK_OPENING, 20_000, substring = true) }
    }

    @Test
    fun nextMovesThroughTheQueueAndTrackEndAdvances() {
        playTrack(TRACK_OPENING)
        waitUntil(15_000) { TestHarness.currentTrackName(context) == TRACK_OPENING }

        clickTag(TestTags.TransportNext)
        waitUntil(20_000) { TestHarness.currentTrackName(context) == TRACK_MIDDLE }
        runCatching { waitUntilText(TRACK_MIDDLE, 10_000, substring = true) }

        // 03 is three seconds long; reaching its end must advance by itself.
        openRoot()
        compose.onNodeWithTag(TestTags.libraryTrack(TRACK_FINALE)).performClick()
        compose.onNodeWithTag(TestTags.NavPlayer).performClick()
        waitForTag(TestTags.PlayerPosition, 20_000)
        waitUntil(10_000) {
            val name = TestHarness.currentTrackName(context)
            name == TRACK_FINALE || name == TRACK_MP3
        }
        waitUntil(45_000) { TestHarness.currentTrackName(context) == TRACK_MP3 }
        waitUntilText(TRACK_MP3, 20_000, substring = true)
    }
}