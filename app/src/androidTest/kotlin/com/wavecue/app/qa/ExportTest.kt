package com.wavecue.app.qa

import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wavecue.app.ui.TestTags
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExportTest : QaTest() {

    @Test
    fun jsonAndMarkdownExportsContainTheAnnotationsAndNoSidecarFiles() {
        playTrack(TRACK_OPENING)
        addFlag("Drop", "0:07.500")
        addNote("Check the low end", "0:10.000")

        // Annotations live in the database. Adding them must not touch the audio
        // folder at all, so the exported formats are checked against this baseline.
        val baseline = fixtureListing()
        assertTrue(baseline, baseline.contains(TRACK_OPENING))
        assertFalse("a sidecar appeared next to the audio: $baseline", baseline.contains(".json"))
        assertFalse("a sidecar appeared next to the audio: $baseline", baseline.contains(".md"))

        shell("rm -f '/sdcard/Download/wavecue-backup.json' '/sdcard/Download/01 Opening-notes.md'")

        // Full library backup from Settings.
        compose.onNodeWithTag(TestTags.NavSettings).performClick()
        waitUntilText("Playback fidelity, silence detection and data", 15_000)
        scrollToText("Export library as JSON")
        compose.onNodeWithText("Export library as JSON").performClick()
        DocumentsUi.saveFile()

        val json = shell("cat '${waitForFile("wavecue-backup.json")}'")
        assertTrue(json, json.contains("\"app\": \"WaveCue\""))
        assertTrue(json, json.contains(TRACK_OPENING))
        assertTrue(json, json.contains("\"title\": \"Drop\""))
        assertTrue(json, json.contains("\"timecode\": \"0:07.500\""))
        assertTrue(json, json.contains("\"body\": \"Check the low end\""))

        // Per-track Markdown from the Player tab.
        compose.onNodeWithTag(TestTags.NavPlayer).performClick()
        waitUntilText("Export notes", 20_000)
        compose.onNodeWithText("Export notes").performClick()
        DocumentsUi.saveFile()

        val markdown = shell("cat '${waitForFile("01 Opening-notes.md")}'")
        assertTrue(markdown, markdown.contains("# 01 Opening.wav"))
        assertTrue(markdown, markdown.contains("## Flags"))
        assertTrue(markdown, markdown.contains("- `0:07.500` — Drop"))
        assertTrue(markdown, markdown.contains("- `0:10.000` — Check the low end"))
    }

    private fun fixtureListing(): String = shell("ls -1 /sdcard/$FIXTURE_FOLDER")

    private fun waitForFile(name: String, timeoutMs: Long = 40_000): String {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val path = shell("find /sdcard -type f -name '$name' 2>/dev/null | head -1").trim()
            if (path.isNotEmpty()) return path
            Thread.sleep(500)
        }
        throw AssertionError("exported file '$name' was not written anywhere under /sdcard")
    }
}
