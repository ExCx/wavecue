package com.wavecue.app.qa

import android.Manifest
import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import com.wavecue.app.MainActivity
import com.wavecue.app.testing.TestHarness
import com.wavecue.app.ui.TestTags
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import kotlin.math.abs
import kotlin.math.roundToLong

const val FIXTURE_FOLDER = "WaveCueFixtures"
const val TRACK_OPENING = "01 Opening.wav"
const val TRACK_MIDDLE = "02 Middle.wav"
const val TRACK_FINALE = "03 Finale.wav"
const val TRACK_MP3 = "04 Codec Test.mp3"

/**
 * Clears the database and preferences before the activity launches, so every
 * test starts from the same place. The persisted SAF grant and the library
 * root rows survive, which is what lets the folder picker run once per install
 * instead of once per test.
 */
class TestStateRule : TestRule {
    override fun apply(base: Statement, description: Description): Statement =
        object : Statement() {
            override fun evaluate() {
                val instrumentation = InstrumentationRegistry.getInstrumentation()
                TestHarness.reset(instrumentation.targetContext)
                try {
                    base.evaluate()
                } finally {
                    TestHarness.reset(instrumentation.targetContext)
                }
            }
        }
}

abstract class QaTest {

    val compose = createAndroidComposeRule<MainActivity>()

    /**
     * Grants POST_NOTIFICATIONS before the activity launches. Without this the
     * app's own permission prompt can appear a moment after the test starts and
     * swallow taps meant for the library.
     */
    private val notificationPermission: TestRule =
        GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    @get:Rule
    val rules: RuleChain = RuleChain
        .outerRule(TestStateRule())
        .around(notificationPermission)
        .around(compose)

    protected val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    protected val context: Context get() = instrumentation.targetContext
    protected val device: UiDevice get() = UiDevice.getInstance(instrumentation)

    @Before
    fun startFromLibrary() {
        // A previous test may have failed while DocumentsUI was in front.
        if (DocumentsUi.isPickerOpen(1_000)) {
            device.pressBack()
            device.waitForIdle(2_000)
        }
        dismissSystemDialog()
        // Wait for the app to be responsive and showing the library tab.
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            dismissSystemDialog()
            val ready = runCatching {
                compose.onAllNodesWithTag(TestTags.NavLibrary).fetchSemanticsNodes().isNotEmpty()
            }.getOrDefault(false)
            if (ready) break
            Thread.sleep(500)
        }
        dismissSystemDialog()
        compose.waitForIdle()
        Thread.sleep(400)
        dismissSystemDialog()
        compose.onNodeWithTag(TestTags.NavLibrary).performClick()
    }

    @After
    fun leaveSystemUi() {
        dismissSystemDialog()
        repeat(2) {
            if (runCatching { device.currentPackageName }.getOrNull() == context.packageName) {
                return
            }
            device.pressBack()
            device.waitForIdle()
            dismissSystemDialog()
        }
    }

    // ---------------------------------------------------------------- compose

    protected fun waitUntil(timeoutMs: Long = 15_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            dismissSystemDialog()
            if (runCatching { condition() }.getOrDefault(false)) return
            Thread.sleep(250)
        }
        dismissSystemDialog()
        if (!runCatching { condition() }.getOrDefault(false)) {
            throw AssertionError("Condition not satisfied after ${timeoutMs}ms")
        }
    }

    protected fun waitForTag(tag: String, timeoutMs: Long = 15_000) {
        waitUntil(timeoutMs) {
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    protected fun waitUntilText(text: String, timeoutMs: Long = 15_000, substring: Boolean = false) {
        waitUntil(timeoutMs) {
            compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
        }
    }

    protected fun clickTag(tag: String) {
        compose.onNodeWithTag(tag).performClick()
        compose.waitForIdle()
    }

    protected fun clickText(text: String) {
        compose.onNodeWithText(text).performClick()
    }

    /** Scrolls the active list until [text] is on screen, like a person would. */
    protected fun scrollToText(text: String) {
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(text))
        compose.waitForIdle()
    }

    protected fun textOf(tag: String): String {
        val node = compose.onNodeWithTag(tag).fetchSemanticsNode()
        return node.config.getOrNull(SemanticsProperties.Text)
            ?.joinToString("") { it.text }
            .orEmpty()
    }

    protected fun positionMs(): Long = parseTimecode(textOf(TestTags.PlayerPosition))

    protected fun durationMs(): Long = parseTimecode(textOf(TestTags.PlayerDuration))

    protected fun parseTimecode(raw: String): Long {
        val clean = raw.trim().removePrefix("/").trim()
        val parts = clean.split(":")
        require(parts.size in 2..3) { "not a timecode: $raw" }
        val hours = if (parts.size == 3) parts[0].toLong() else 0L
        val minutes = if (parts.size == 3) parts[1].toLong() else parts[0].toLong()
        val seconds = parts.last().toDouble()
        return ((hours * 3600 + minutes * 60) * 1000 + (seconds * 1000).roundToLong())
    }

    // --------------------------------------------------------------- library

    /** Runs the real DocumentsUI flow once per install, then reuses the grant. */
    protected fun ensureFolderConfigured() {
        if (TestHarness.rootUris(context).isNotEmpty()) return
        compose.onNodeWithTag(TestTags.NavLibrary).performClick()
        // The launcher can ANR under load and cover the app; keep dismissing
        // while we wait for the empty library state to render.
        val emptyDeadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < emptyDeadline) {
            dismissSystemDialog()
            val ready = runCatching {
                compose.onAllNodesWithText("Choose a folder").fetchSemanticsNodes().isNotEmpty()
            }.getOrDefault(false)
            if (ready) break
            Thread.sleep(500)
        }
        dismissSystemDialog()
        waitUntilText("Choose a folder", 20_000)

        var opened = DocumentsUi.isPickerOpen(1_000)
        var attempts = 0
        while (!opened && attempts < 2) {
            attempts++
            val clicked = runCatching {
                compose.onNodeWithText("Choose a folder").performClick()
                true
            }.getOrDefault(false)

            // The first launch of DocumentsUI on a cold emulator can take well
            // over 30 s, and a Compose tap fails once the picker owns the
            // screen, so give the picker the full window before deciding.
            val deadline = System.currentTimeMillis() + 60_000
            while (!opened && System.currentTimeMillis() < deadline) {
                opened = DocumentsUi.isPickerOpen(1_000)
                if (!opened) dismissSystemDialog()
            }
            if (!opened && !clicked) {
                throw AssertionError(
                    "the folder picker never opened and the app had no Compose hierarchy; " +
                        DocumentsUi.describeForeground(),
                )
            }
            if (!opened) {
                // A system dialog may have taken the tap. Dismiss and retry.
                dismissSystemDialog()
            }
        }
        check(opened) { "the folder picker never opened; ${DocumentsUi.describeForeground()}" }
        DocumentsUi.pickFolder(FIXTURE_FOLDER)
        try {
            compose.waitUntil(60_000) { TestHarness.rootUris(context).isNotEmpty() }
        } catch (t: Throwable) {
            throw AssertionError(
                "folder grant did not reach the database. Visible: ${visibleTexts()}",
                t,
            )
        }
        waitUntilText(FIXTURE_FOLDER, 20_000)
    }

    /** Leaves the library at the fixture folder, showing its tracks. */
    protected fun openRoot() {
        ensureFolderConfigured()
        compose.onNodeWithTag(TestTags.NavLibrary).performClick()

        // Walk back out of any sub-folder first so every call lands at the root.
        var guard = 0
        while (
            compose.onAllNodesWithContentDescription("Up").fetchSemanticsNodes().isNotEmpty() &&
            guard++ < 6
        ) {
            compose.onNodeWithContentDescription("Up").performClick()
            compose.waitForIdle()
        }

        // The browser header only exists inside a root; on the roots list the
        // fixture row opens it.
        val insideRoot = compose
            .onAllNodesWithContentDescription("Add folder")
            .fetchSemanticsNodes()
            .isEmpty()
        if (!insideRoot) {
            compose.onNodeWithText(FIXTURE_FOLDER).performClick()
        }
        try {
            waitForTag(TestTags.libraryTrack(TRACK_OPENING), 60_000)
        } catch (t: Throwable) {
            throw AssertionError(
                "track '$TRACK_OPENING' did not appear within 60s. Visible text: ${visibleTexts()}",
                t,
            )
        }
    }

    private fun visibleTexts(): String {
        return try {
            compose.onAllNodesWithText("", substring = true)
                .fetchSemanticsNodes()
                .mapNotNull { it.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text } }
                .filter { it.isNotBlank() }
                .distinct()
                .joinToString(" | ")
        } catch (t: Throwable) {
            "(unavailable: ${t::class.simpleName})"
        }
    }

    protected fun playTrack(name: String) {
        openRoot()
        compose.onNodeWithTag(TestTags.libraryTrack(name)).performClick()
        compose.onNodeWithTag(TestTags.NavPlayer).performClick()
        waitForTag(TestTags.PlayerPosition, 20_000)
        waitUntilText(name, 30_000, substring = true)
    }

    /** Waits for analysis so the silence and waveform controls are usable. */
    protected fun waitForWaveform(timeoutMs: Long = 90_000) {
        compose.waitUntil(timeoutMs) {
            runCatching { compose.onNodeWithText("Find gaps").assertIsEnabled() }.isSuccess
        }
    }

    // ------------------------------------------------------------ annotations

    protected fun addFlag(title: String, timecode: String) {
        compose.onNodeWithText("Flag here").performClick()
        waitUntilText("New flag")
        compose.onNodeWithTag(TestTags.FlagTitle).performTextReplacement(title)
        compose.onNodeWithTag(TestTags.FlagTime).performTextReplacement(timecode)
        clickTag(TestTags.FlagSave)
        waitUntil(10_000) { TestHarness.flagTitles(context).contains(title) }
    }

    protected fun addNote(body: String, timecode: String) {
        compose.onNodeWithText("Note here").performClick()
        waitUntilText("New note")
        compose.onNodeWithTag(TestTags.NoteBody).performTextReplacement(body)
        compose.onNodeWithTag(TestTags.NoteTime).performTextReplacement(timecode)
        clickTag(TestTags.NoteSave)
        waitUntil(10_000) { TestHarness.noteBodies(context).contains(body) }
    }

    // --------------------------------------------------------------- helpers

    protected fun assertPositionNear(expectedMs: Long, toleranceMs: Long = 1_500) {
        val actual = positionMs()
        if (abs(actual - expectedMs) > toleranceMs) {
            throw AssertionError("expected position near ${expectedMs}ms but was ${actual}ms")
        }
    }

    protected fun assertVerticalOrder(vararg names: String) {
        val tops = names.map { compose.onNodeWithText(it).fetchSemanticsNode().boundsInRoot.top }
        if (tops != tops.sorted()) {
            throw AssertionError("expected $names in that vertical order, tops were $tops")
        }
    }

    protected fun dismissSystemDialog() {
        val allow = device.findObject(By.res("android", "aerr_wait"))
            ?: device.findObject(By.res("android", "aerr_close"))
            ?: device.findObject(By.text("Wait"))
            ?: device.findObject(By.text("WAIT"))
            ?: device.findObject(By.text("Close app"))
            ?: device.findObject(By.text("Close App"))
            ?: device.findObject(By.text("Allow"))
            ?: device.findObject(By.text("ALLOW"))
            ?: device.findObject(By.text("While using the app"))
            ?: device.findObject(By.res("com.android.permissioncontroller", "permission_allow_button"))
            ?: device.findObject(By.res("com.android.permissioncontroller", "permission_allow_foreground_only_button"))
            ?: device.findObject(By.res("android", "permission_allow_button"))
            ?: device.findObject(By.res("android", "permission_allow_foreground_only_button"))
            ?: device.findObject(By.res("android", "button1"))
        allow?.click()
        device.waitForIdle(2_000)
    }

    protected fun shell(command: String): String {
        val descriptor = instrumentation.uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor)
            .bufferedReader()
            .use { it.readText() }
    }
}
