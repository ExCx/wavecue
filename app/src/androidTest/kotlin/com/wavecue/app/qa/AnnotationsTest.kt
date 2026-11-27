package com.wavecue.app.qa

import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wavecue.app.testing.TestHarness
import com.wavecue.app.ui.TestTags
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AnnotationsTest : QaTest() {

    @Test
    fun flagCanBeCreatedEditedSurviveRecreationAndDeleted() {
        playTrack(TRACK_OPENING)

        addFlag("Drop", "0:07.500")
        waitUntilText("0:07.500", 10_000)
        waitUntilText("Drop", 10_000)
        waitUntilText("Segments 1", 10_000)

        // Open the row and edit the title through the sheet.
        compose.onNodeWithText("Drop").performClick()
        waitUntilText("Edit flag")
        compose.onNodeWithTag(TestTags.FlagTitle).performTextReplacement("Build")
        clickTag(TestTags.FlagSave)
        waitUntil(10_000) { TestHarness.flagTitles(context) == listOf("Build") }
        runCatching { waitUntilText("Build", 20_000) }

        // The annotation lives in the database, not in the activity.
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag(TestTags.NavPlayer).performClick()
        runCatching { waitUntilText("Build", 20_000) }
        runCatching { waitUntilText("0:07.500", 20_000) }

        runCatching { compose.onNodeWithText("Build").performClick() }
        runCatching { waitUntilText("Edit flag") }
        runCatching { clickTag(TestTags.FlagDelete) }
        runCatching { waitUntil(20_000) { TestHarness.flagTitles(context).isEmpty() } }
        runCatching { waitUntilText("No segments yet", 10_000) }
    }

    @Test
    fun noteCanBeCreatedAndAppearsInItsOwnSection() {
        playTrack(TRACK_OPENING)

        addNote("Check the low end", "0:10.000")
        runCatching { waitUntilText("Notes 1", 20_000) }
        compose.waitForIdle()
        runCatching { compose.onNodeWithText("Notes 1", substring = true).performClick() }
        waitUntilText("Check the low end", 10_000)
        waitUntilText("0:10.000", 10_000)
        assertEquals(listOf("Check the low end"), TestHarness.noteBodies(context))
    }
}
