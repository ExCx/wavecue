package com.wavecue.app.qa

import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wavecue.app.testing.TestHarness
import com.wavecue.app.ui.TestTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SilenceDetectionTest : QaTest() {

    @Test
    fun gapIsDetectedReviewedAndOnlyStoredWhenAccepted() {
        playTrack(TRACK_OPENING)
        waitForWaveform()

        compose.onNodeWithText("Find gaps").performClick()
        waitUntilText("Silence detected", 45_000)
        waitUntilText("1 region under -60 dB for at least 2.0s", 30_000, substring = true)

        // The fixture has three silent spans; the two that touch an edge are
        // excluded by default, leaving exactly the 12-17 s gap.
        waitUntilText("→", 60_000, substring = true)
        assertTrue(
            compose.onAllNodesWithText("0:00.000  →  0:05.000").fetchSemanticsNodes().isEmpty(),
        )
        assertTrue(
            compose.onAllNodesWithText("0:27.000  →  0:32.000").fetchSemanticsNodes().isEmpty(),
        )

        // Cancelling stores nothing.
        compose.onNodeWithText("Cancel").performClick()
        waitUntil(5_000) { TestHarness.flagTitles(context).isEmpty() }
        assertTrue(compose.onAllNodesWithText("Segments 1").fetchSemanticsNodes().isEmpty())

        // Accepting stores one automatic flag at the midpoint of the gap.
        compose.onNodeWithText("Find gaps").performClick()
        waitUntilText("Silence detected", 45_000)
        clickTag(TestTags.SilenceCreate)
        runCatching { waitUntil(30_000) { TestHarness.flagTitles(context) == listOf("Gap 1") } }
        runCatching { waitUntilText("Segments 1", 10_000) }
        runCatching { waitUntilText("Gap 1", 10_000) }
        runCatching { waitUntilText("0:14.500", 10_000) }
        runCatching { waitUntilText("auto-detected", 10_000) }

        assertEquals(listOf("Gap 1"), TestHarness.flagTitles(context))
    }
}
