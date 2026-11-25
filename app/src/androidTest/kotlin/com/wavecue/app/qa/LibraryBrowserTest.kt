package com.wavecue.app.qa

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wavecue.app.testing.TestHarness
import com.wavecue.app.ui.TestTags
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryBrowserTest : QaTest() {

    @Test
    fun pickerGrantsAccessAndListsOnlyPlayableAudio() {
        ensureFolderConfigured()

        // The folder just granted through DocumentsUI is persisted as a root.
        waitUntilText(FIXTURE_FOLDER)
        assertTrue(
            "expected the fixture root to be stored, got ${TestHarness.rootNames(context)}",
            TestHarness.rootNames(context).contains(FIXTURE_FOLDER),
        )

        openRoot()

        for (track in listOf(TRACK_OPENING, TRACK_MIDDLE, TRACK_FINALE, TRACK_MP3)) {
            compose.onNodeWithTag(TestTags.libraryTrack(track)).assertIsDisplayed()
        }

        // Everything that is not WAV/MP3 is filtered before it reaches the list.
        assertTrue(compose.onAllNodesWithText("readme.txt").fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithText("artwork.jpg").fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithText("05 Not Supported.m4a").fetchSemanticsNodes().isEmpty())

        // Natural order, independent of the order the provider returned.
        assertVerticalOrder(TRACK_OPENING, TRACK_MIDDLE, TRACK_FINALE, TRACK_MP3)

        waitUntilText("Playing a track here builds a temporary queue from this folder.")
    }

    @Test
    fun openingATrackShowsItInThePlayerAndNowPlayingBar() {
        playTrack(TRACK_OPENING)

        waitUntilText(TRACK_OPENING, substring = true)
        waitUntilText("Folder: $FIXTURE_FOLDER", substring = true)
        compose.onNodeWithTag(TestTags.PlayerWaveform).assertIsDisplayed()

        // The library marks the playing row and the folder queue label is the root name.
        compose.onNodeWithTag(TestTags.NavLibrary).performClick()
        waitUntilText(TRACK_OPENING)
        waitUntilText("Folder: $FIXTURE_FOLDER", substring = true)
    }
}
