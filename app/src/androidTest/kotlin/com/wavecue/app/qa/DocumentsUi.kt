package com.wavecue.app.qa

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until

/**
 * The system file picker is a separate app, so it is driven with UiAutomator
 * rather than Compose. Selectors prefer visible text and fall back to the
 * framework ids, which have been stable across the DocumentsUI versions used
 * by the CI image.
 */
object DocumentsUi {

    private const val PKG = "com.android.documentsui"
    private const val TARGET_PKG = "com.wavecue.app"

    private val device: UiDevice
        get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    fun pickFolder(name: String, timeoutMs: Long = 30_000) {
        val d = device
        check(d.wait(Until.hasObject(By.pkg(PKG)), timeoutMs)) { "the folder picker did not open" }
        d.waitForIdle()

        openDrawerIfPresent()
        selectInternalStorage()
        scrollTo(name)

        val folder = d.findObject(By.text(name))
            ?: throw AssertionError("folder '$name' is not visible in the picker")
        folder.click()
        d.waitForIdle()

        val use = waitAny(
            listOf(
                By.textContains("Use this folder"),
                By.res(PKG, "action_menu_select"),
                By.descContains("Use this folder"),
            ),
            15_000,
        ) ?: throw AssertionError("'Use this folder' is missing")
        use.click()
        d.waitForIdle()

        waitAny(listOf(By.text("Allow"), By.text("ALLOW"), By.res("android", "button1")), 10_000)?.click()
        d.waitForIdle()
        // The picker closes and the app resumes; make sure the app window is back
        // before Compose assertions run against it.
        d.wait(Until.hasObject(By.pkg(TARGET_PKG)), 15_000)
        d.waitForIdle()
    }

    /** Accepts the current CreateDocument dialog using its prefilled name. */
    fun saveFile(timeoutMs: Long = 30_000) {
        val d = device
        check(d.wait(Until.hasObject(By.pkg(PKG)), timeoutMs)) { "the save dialog did not open" }
        d.waitForIdle()

        // Prefer Downloads when the dialog offers it, so the test knows where
        // to look for the file afterwards.
        d.findObject(By.text("Downloads"))?.click()
        d.waitForIdle()

        val save = waitAny(
            listOf(
                By.text("Save"),
                By.res(PKG, "action_menu_select"),
                By.res("android", "button1"),
            ),
            15_000,
        ) ?: throw AssertionError("'Save' is missing from the document dialog")
        save.click()
        d.waitForIdle()

        waitAny(
            listOf(By.textContains("Replace"), By.textContains("Overwrite"), By.text("OK")),
            3_000,
        )?.click()
        d.waitForIdle()
        d.wait(Until.hasObject(By.pkg(TARGET_PKG)), 15_000)
        d.waitForIdle()
    }

    private fun openDrawerIfPresent() {
        val d = device
        val hamburger = d.findObject(By.desc("Show roots"))
            ?: d.findObject(By.desc("Open navigation drawer"))
        if (hamburger == null) return
        hamburger.click()
        d.waitForIdle()
        d.wait(Until.hasObject(By.textContains("Internal storage")), 5_000)
    }

    private fun selectInternalStorage() {
        val d = device
        val storage = d.findObject(By.textContains("Internal storage"))
            ?: d.findObject(By.textContains("sdk_gphone"))
            ?: d.findObject(By.textContains("emulator"))
            ?: return
        storage.click()
        d.waitForIdle()
    }

    private fun scrollTo(name: String) {
        val d = device
        repeat(8) {
            if (d.findObject(By.text(name)) != null) return
            val scrollable = d.findObject(By.scrollable(true))
            if (scrollable != null) {
                scrollable.scroll(Direction.DOWN, 0.9f)
            } else {
                d.swipe(
                    d.displayWidth / 2,
                    (d.displayHeight * 0.8f).toInt(),
                    d.displayWidth / 2,
                    (d.displayHeight * 0.2f).toInt(),
                    20,
                )
            }
            d.waitForIdle()
        }
    }

    private fun waitAny(selectors: List<BySelector>, timeoutMs: Long): UiObject2? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            for (selector in selectors) {
                device.findObject(selector)?.let { return it }
            }
            Thread.sleep(200)
        }
        return null
    }
}
