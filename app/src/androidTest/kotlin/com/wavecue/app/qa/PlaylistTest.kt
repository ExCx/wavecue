package com.wavecue.app.qa

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wavecue.app.testing.TestHarness
import com.wavecue.app.ui.TestTags
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaylistTest : QaTest() {

    @Test
    fun playlistIsAlphabeticalByDefaultShufflesAndPlaysInOrder() {
        createPlaylist(PLAYLIST)
        addToPlaylist(TRACK_FINALE)
        addToPlaylist(TRACK_OPENING)
        addToPlaylist(TRACK_MIDDLE)

        compose.onNodeWithTag(TestTags.NavPlaylists).performClick()
        compose.onNodeWithText(PLAYLIST).performClick()
        waitUntilText("3 tracks · A–Z", 15_000)
        assertVerticalOrder(TRACK_OPENING, TRACK_MIDDLE, TRACK_FINALE)

        // Shuffle is an explicit mode change; A-Z restores the deterministic order.
        compose.onNodeWithText("Shuffle").performClick()
        waitUntilText("3 tracks · Shuffled", 10_000)
        compose.onNodeWithText("A–Z").performClick()
        waitUntilText("3 tracks · A–Z", 10_000)
        assertVerticalOrder(TRACK_OPENING, TRACK_MIDDLE, TRACK_FINALE)

        compose.onNodeWithText("Play").performClick()
        waitUntil(20_000) { TestHarness.currentTrackName(context) == TRACK_OPENING }
        waitUntilText("Playlist: $PLAYLIST", 15_000, substring = true)
    }

    @Test
    fun entriesCanBeMovedAndRemovedAndThePlaylistDeleted() {
        createPlaylist(PLAYLIST)
        addToPlaylist(TRACK_OPENING)
        addToPlaylist(TRACK_MIDDLE)
        addToPlaylist(TRACK_FINALE)

        compose.onNodeWithTag(TestTags.NavPlaylists).performClick()
        compose.onNodeWithText(PLAYLIST).performClick()
        waitUntilText("3 tracks · A–Z", 15_000)

        compose.onAllNodesWithContentDescription("Track options")[0].performClick()
        compose.onNodeWithText("Move down").performClick()
        waitUntilText("3 tracks · Manual", 10_000)
        assertVerticalOrder(TRACK_MIDDLE, TRACK_OPENING, TRACK_FINALE)

        compose.onAllNodesWithContentDescription("Track options")[2].performClick()
        compose.onNodeWithText("Remove").performClick()
        waitUntilText("2 tracks · Manual", 10_000)

        compose.onNodeWithContentDescription("Delete playlist").performClick()
        waitUntilText("Delete playlist?")
        compose.onNodeWithText("Delete").performClick()
        waitUntilText("Build a playlist", 15_000)
        assertEquals(0, compose.onAllNodesWithText(PLAYLIST).fetchSemanticsNodes().size)
    }

    private fun createPlaylist(name: String) {
        compose.onNodeWithTag(TestTags.NavPlaylists).performClick()
        compose.onNodeWithContentDescription("New playlist").performClick()
        waitUntilText("New playlist")
        compose.onNode(hasSetTextAction()).performTextInput(name)
        compose.onNodeWithText("Create").performClick()
        waitUntilText(name, 10_000)
    }

    private fun addToPlaylist(track: String) {
        openRoot()
        compose.onNodeWithTag(TestTags.libraryTrackMenu(track)).performClick()
        compose.onNodeWithText("Add to playlist…").performClick()
        waitUntilText("Add to playlist")
        compose.onNodeWithText(PLAYLIST).performClick()
        waitUntil(10_000) {
            compose.onAllNodesWithText("Add to playlist").fetchSemanticsNodes().isEmpty()
        }
    }

    private companion object {
        const val PLAYLIST = "QA Set"
    }
}
