package com.wavecue.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TimeFormatTest {

    @Test
    fun timecode_formatsMinutesSecondsMillis() {
        assertEquals("0:00.000", TimeFormat.timecode(0))
        assertEquals("0:00.001", TimeFormat.timecode(1))
        assertEquals("1:01.500", TimeFormat.timecode(61_500))
        assertEquals("10:00.000", TimeFormat.timecode(600_000))
    }

    @Test
    fun timecode_countsPastAnHourAsMinutes() {
        assertEquals("60:05.000", TimeFormat.timecode(3_605_000))
    }

    @Test
    fun timecode_clampsNegativeInput() {
        assertEquals("0:00.000", TimeFormat.timecode(-1))
    }

    @Test
    fun short_hidesHoursUntilNeeded() {
        assertEquals("0:00", TimeFormat.short(0))
        assertEquals("1:05", TimeFormat.short(65_000))
        assertEquals("1:02:05", TimeFormat.short(3_725_000))
    }

    @Test
    fun duration_roundsToNearestSecond() {
        assertEquals("1:00", TimeFormat.duration(59_600))
        assertEquals("0:59", TimeFormat.duration(59_400))
    }

    @Test
    fun parseTimecode_acceptsMinutesAndSeconds() {
        assertEquals(62_500L, TimeFormat.parseTimecode("1:02.500"))
        assertEquals(5_000L, TimeFormat.parseTimecode("0:05"))
    }

    @Test
    fun parseTimecode_acceptsHours() {
        assertEquals(3_723_250L, TimeFormat.parseTimecode("1:02:03.250"))
    }

    @Test
    fun parseTimecode_acceptsBareSecondsAndShortFractions() {
        assertEquals(12_000L, TimeFormat.parseTimecode("12"))
        assertEquals(1_500L, TimeFormat.parseTimecode("1.5"))
        assertEquals(1_050L, TimeFormat.parseTimecode("1.05"))
    }

    @Test
    fun parseTimecode_rejectsGarbage() {
        assertNull(TimeFormat.parseTimecode("abc"))
        assertNull(TimeFormat.parseTimecode("1:xx"))
        assertNull(TimeFormat.parseTimecode("1:2:3:4"))
    }

    @Test
    fun parseTimecode_roundTripsTimecode() {
        val values = listOf(0L, 1L, 999L, 61_500L, 3_605_000L)
        values.forEach { value ->
            assertEquals(value, TimeFormat.parseTimecode(TimeFormat.timecode(value)))
        }
    }
}
