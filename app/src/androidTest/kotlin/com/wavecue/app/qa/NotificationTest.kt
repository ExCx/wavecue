package com.wavecue.app.qa

import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import com.wavecue.app.testing.TestHarness
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotificationTest : QaTest() {

    @Test
    fun notificationAndMediaKeysDriveThePlaybackSession() {
        playTrack(TRACK_OPENING)
        waitUntil(15_000) { TestHarness.isPlaying(context) }

        // The foreground notification carries the real track title.
        device.openNotification()
        assertTrue(
            "notification never showed the track title",
            device.wait(Until.hasObject(By.text(TRACK_OPENING)), 15_000),
        )

        val session = shell("dumpsys media_session")
        assertTrue(session, session.contains("com.wavecue.app"))
        assertTrue(session, session.contains(TRACK_OPENING))

        // Hardware/Bluetooth style media keys go through the media session.
        device.pressKeyCode(KeyEvent.KEYCODE_MEDIA_PAUSE)
        waitUntil(10_000) { !TestHarness.isPlaying(context) }
        assertTrue(
            "media session did not report paused",
            waitForMediaState(paused = true, timeoutMs = 10_000),
        )

        device.pressKeyCode(KeyEvent.KEYCODE_MEDIA_PLAY)
        var playAttempts = 0
        while (!TestHarness.isPlaying(context) && playAttempts < 3) {
            playAttempts++
            Thread.sleep(1_000)
            if (!TestHarness.isPlaying(context)) {
                device.pressKeyCode(KeyEvent.KEYCODE_MEDIA_PLAY)
            }
        }
        waitUntil(15_000) { TestHarness.isPlaying(context) }
        assertTrue(
            "media session did not report playing",
            waitForMediaState(paused = false, timeoutMs = 10_000),
        )

        device.pressBack()
        device.waitForIdle()
    }

    private fun waitForMediaState(paused: Boolean, timeoutMs: Long): Boolean {
        val tokens = if (paused) listOf("state=2", "PAUSED") else listOf("state=3", "PLAYING")
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val dump = shell("dumpsys media_session")
            if (tokens.any { dump.contains(it) }) return true
            Thread.sleep(300)
        }
        return false
    }
}
