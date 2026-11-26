package com.wavecue.app.qa

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.util.regex.Pattern

/**
 * The system file picker is a separate app, so it is driven with UiAutomator
 * rather than Compose. Selectors prefer visible text and fall back to the
 * framework ids, which have been stable across the DocumentsUI versions used
 * by the CI image.
 *
 * Two DocumentsUI builds exist: AOSP (`com.android.documentsui`) and Google's
 * Files (`com.google.android.documentsui`), which is what the API 34
 * `google_apis` emulator image ships. Material buttons can render all-caps, so
 * the text selectors are case-insensitive.
 */
object DocumentsUi {

    private val PICKER_PKGS = listOf(
        "com.google.android.documentsui",
        "com.android.documentsui",
    )
    private const val TARGET_PKG = "com.wavecue.app"

    private val USE_THIS_FOLDER = Pattern.compile("(?i).*use this folder.*")
    private val SAVE = Pattern.compile("(?i).*save.*")

    private val device: UiDevice
        get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    fun isPickerOpen(timeoutMs: Long = 10_000): Boolean = pickerVisible(timeoutMs)

    /** Foreground package plus all visible labels, for selector failures. */
    fun describeForeground(): String {
        val pkg = runCatching { device.currentPackageName }.getOrNull().orEmpty()
        return "foreground=$pkg labels=${uiSnapshot(allPackages = true)}"
    }

    private fun pickerVisible(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (PICKER_PKGS.any { device.findObject(By.pkg(it)) != null }) return true
            Thread.sleep(200)
        }
        return false
    }

    private fun pickerResource(id: String): List<BySelector> = PICKER_PKGS.map { By.res(it, id) }

    private fun useThisFolderSelectors(): List<BySelector> = buildList {
        addAll(pickerResource("action_menu_select"))
        addAll(pickerResource("use_folder"))
        addAll(pickerResource("use_this_folder"))
        addAll(pickerResource("button1"))
        add(By.text(USE_THIS_FOLDER))
        add(By.desc(USE_THIS_FOLDER))
        add(By.textContains("USE THIS FOLDER"))
        add(By.textContains("Use this folder"))
        add(By.descContains("USE THIS FOLDER"))
        add(By.descContains("Use this folder"))
    }

    fun pickFolder(name: String, timeoutMs: Long = 30_000) {
        val d = device
        check(pickerVisible(timeoutMs)) {
            "the folder picker did not open (looked for ${PICKER_PKGS.joinToString()}); " +
                describeForeground()
        }
        d.waitForIdle()

        openDrawerIfPresent()
        selectInternalStorage()

        if (!waitForText(name, 15_000) && !searchUpwardsFor(name)) {
            throw AssertionError("folder '$name' did not appear. ${describeForeground()}")
        }
        scrollTo(name)

        val folder = d.findObject(By.text(name))
            ?: throw AssertionError("folder '$name' is not visible in the picker")
        folder.click()
        d.waitForIdle()

        var use = waitAnyEnabled(useThisFolderSelectors(), 12_000)
        if (use == null) {
            // Some DocumentsUI builds hide the confirmation for the folder the
            // picker opened in; stepping up and back in makes it appear.
            if (navigateUp()) {
                Thread.sleep(500)
                d.findObject(By.text(name))?.click()
                d.waitForIdle()
            }
            use = waitAnyEnabled(useThisFolderSelectors(), 10_000)
        }
        if (use == null) {
            throw AssertionError("'Use this folder' is missing. ${describeForeground()}")
        }
        use.click()
        d.waitForIdle()

        // The confirmation sometimes needs a second tap or an Enter keypress on
        // slower emulators.
        var returned = false
        val confirmDeadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < confirmDeadline && !returned) {
            waitAny(listOf(By.text("Allow"), By.text("ALLOW"), By.res("android", "button1")), 2_000)?.click()
            d.waitForIdle(1_000)
            if (d.findObject(By.pkg(TARGET_PKG)) != null) { returned = true; break }
            // Retry the confirmation tap if the picker is still open.
            if (d.findObject(By.pkg(PICKER_PKGS[0])) != null || d.findObject(By.pkg(PICKER_PKGS[1])) != null) {
                use.click()
                d.waitForIdle(1_000)
            }
        }

        // Wait for the app to fully return.
        val returnDeadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < returnDeadline) {
            if (d.findObject(By.pkg(TARGET_PKG)) != null) { returned = true; break }
            dismissSystemDialog()
            Thread.sleep(500)
        }
        d.waitForIdle(2_000)
        if (!returned) {
            throw AssertionError("picker did not return to the app. ${describeForeground()}")
        }
    }

    /** Accepts the current CreateDocument dialog using its prefilled name. */
    fun saveFile(timeoutMs: Long = 30_000) {
        val d = device
        check(pickerVisible(timeoutMs)) {
            "the save dialog did not open (looked for ${PICKER_PKGS.joinToString()})"
        }
        d.waitForIdle()

        // Prefer Downloads when the dialog offers it, so the test knows where
        // to look for the file afterwards.
        // (Skipped: clicking the sidebar entry can navigate away from the
        //  CreateDocument dialog on some DocumentsUI builds.)
        // d.findObject(By.text("Downloads"))?.click()
        // d.waitForIdle()

        val save = waitAny(
            pickerResource("action_menu_select") +
                pickerResource("save") +
                listOf(
                    By.text(SAVE),
                    By.desc(SAVE),
                    By.res("android", "button1"),
                ),
            15_000,
        ) ?: throw AssertionError("'Save' is missing from the document dialog. ${describeForeground()}")
        save.click()
        device.waitForIdle(1_000)

        // Handle a possible overwrite confirmation, then wait for the app to return.
        waitAny(
            listOf(By.textContains("Replace"), By.textContains("Overwrite"), By.text("OK")),
            3_000,
        )?.click()
        d.waitForIdle()

        val returnDeadline = System.currentTimeMillis() + 30_000
        var returned = false
        while (System.currentTimeMillis() < returnDeadline) {
            if (d.findObject(By.pkg(TARGET_PKG)) != null) { returned = true; break }
            dismissSystemDialog()
            Thread.sleep(500)
        }
        d.waitForIdle(2_000)
        if (!returned) {
            // Retry the save tap once on slow emulators.
            save.click()
            d.waitForIdle()
            val d2 = System.currentTimeMillis() + 15_000
            while (System.currentTimeMillis() < d2 && !returned) {
                if (d.findObject(By.pkg(TARGET_PKG)) != null) { returned = true; break }
                dismissSystemDialog()
                Thread.sleep(500)
            }
            d.waitForIdle(2_000)
        }
        if (!returned) {
            throw AssertionError("save dialog did not return to the app. ${describeForeground()}")
        }
    }

    /**
     * The picker reopens in the last-used directory, which may be a sibling of
     * the fixture folder. Walk up until the folder appears in the listing.
     */
    private fun searchUpwardsFor(name: String): Boolean {
        repeat(4) {
            if (!navigateUp()) return false
            if (waitForText(name, 2_000)) return true
        }
        return false
    }

    private fun openDrawerIfPresent() {
        val d = device
        val hamburger = d.findObject(By.desc("Show roots"))
            ?: d.findObject(By.desc("Open navigation drawer"))
            ?: d.findObject(By.descContains("Show roots"))
        if (hamburger == null) return
        hamburger.click()
        d.waitForIdle()
        d.wait(Until.hasObject(By.textContains("Internal storage")), 5_000)
    }

    private fun selectInternalStorage() {
        val d = device
        val storage = d.findObject(By.textContains("Internal storage"))
            ?: d.findObject(By.textContains("Internal Storage"))
            ?: d.findObject(By.textContains("sdk_gphone"))
            ?: d.findObject(By.textContains("emulator"))
            ?: return
        storage.click()
        d.waitForIdle()
    }

    /** Dismisses permission prompts and ANR dialogs while polling. */
    private fun dismissSystemDialog() {
        val node = device.findObject(By.res("android", "aerr_wait"))
            ?: device.findObject(By.res("android", "aerr_close"))
            ?: device.findObject(By.text("Wait"))
            ?: device.findObject(By.text("Close app"))
            ?: device.findObject(By.text("Allow"))
            ?: device.findObject(By.text("ALLOW"))
            ?: device.findObject(By.text("While using the app"))
            ?: device.findObject(By.res("android", "button1"))
        node?.click()
        device.waitForIdle(1_000)
    }

    private fun navigateUp(): Boolean {
        val d = device
        val up = d.findObject(By.descContains("Navigate up"))
            ?: d.findObject(By.desc("Up"))
            ?: return false
        up.click()
        d.waitForIdle()
        return true
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
            Thread.sleep(250)
        }
    }

    private fun waitForText(text: String, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (device.findObject(By.text(text)) != null) return true
            Thread.sleep(200)
        }
        return false
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

    /** Waits for a match that can actually be tapped, not just a disabled stub. */
    private fun waitAnyEnabled(selectors: List<BySelector>, timeoutMs: Long): UiObject2? {
        val deadline = System.currentTimeMillis() + timeoutMs
        var sawDisabled = false
        while (System.currentTimeMillis() < deadline) {
            for (selector in selectors) {
                val node = device.findObject(selector) ?: continue
                if (node.isEnabled) return node
                sawDisabled = true
            }
            Thread.sleep(200)
        }
        if (sawDisabled) {
            throw AssertionError(
                "'Use this folder' was found but stayed disabled. Visible: ${uiSnapshot()}",
            )
        }
        return null
    }

    /** A compact dump of visible labels, used to make selector failures debuggable. */
    private fun uiSnapshot(limit: Int = 60, allPackages: Boolean = false): String {
        val seen = LinkedHashSet<String>()
        fun collect(node: UiObject2) {
            val text = runCatching { node.text }.getOrNull()?.trim().orEmpty()
            val desc = runCatching { node.contentDescription }.getOrNull()?.trim().orEmpty()
            if (text.isEmpty() && desc.isEmpty()) return
            val res = runCatching { node.resourceName }.getOrNull()?.substringAfterLast('/').orEmpty()
            seen.add("$res t='$text' d='$desc'")
        }
        if (allPackages) {
            val anyText = Pattern.compile(".+")
            runCatching { device.findObjects(By.text(anyText)) }.getOrDefault(emptyList())
                .forEach { collect(it) }
            runCatching { device.findObjects(By.desc(anyText)) }.getOrDefault(emptyList())
                .forEach { collect(it) }
        } else {
            for (pkg in PICKER_PKGS) {
                val nodes = runCatching { device.findObjects(By.pkg(pkg)) }.getOrDefault(emptyList())
                for (node in nodes) {
                    collect(node)
                    if (seen.size >= limit) break
                }
            }
        }
        val trimmed = seen.take(limit)
        return if (trimmed.isEmpty()) "(no labelled controls)" else trimmed.joinToString(" | ")
    }
}
