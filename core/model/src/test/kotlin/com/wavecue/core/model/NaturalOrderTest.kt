package com.wavecue.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class NaturalOrderTest {

    @Test
    fun sortsNumbersByValueNotLexically() {
        val input = listOf("10 Song", "2 Song", "1 Song", "100 Song")
        assertEquals(
            listOf("1 Song", "2 Song", "10 Song", "100 Song"),
            input.sortedWith(NaturalOrder),
        )
    }

    @Test
    fun ignoresLeadingTrackNumbersFollowedBySeparators() {
        // Prefixes are dropped, so the titles decide the order for album folders.
        val input = listOf("10 - Zebra", "2 - Alpha", "01 - Mango")
        assertEquals(
            listOf("2 - Alpha", "01 - Mango", "10 - Zebra"),
            input.sortedWith(NaturalOrder),
        )
    }

    @Test
    fun keepsLeadingNumbersThatAreNotTrackPrefixes() {
        // "2 Song" has no separator after the number, so the number stays in the comparison.
        val input = listOf("10 Song", "2 Song")
        assertEquals(
            listOf("2 Song", "10 Song"),
            input.sortedWith(NaturalOrder),
        )
    }

    @Test
    fun comparesTextCaseInsensitively() {
        // Plain ASCII ordering would put "Zebra" before "apple"; natural order does not.
        assertEquals(
            listOf("apple", "Zebra"),
            listOf("Zebra", "apple").sortedWith(NaturalOrder),
        )
    }

    @Test
    fun fallsBackToRawOrderForEquivalentNames() {
        // "Alpha" and "alpha" are equal ignoring case, so the raw comparison decides.
        assertEquals(
            listOf("Alpha", "alpha"),
            listOf("alpha", "Alpha").sortedWith(NaturalOrder),
        )
    }

    @Test
    fun treatsSwappedNumbersAsDifferentTracks() {
        assertEquals(
            listOf("Track 2", "Track 10"),
            listOf("Track 10", "Track 2").sortedWith(NaturalOrder),
        )
    }

    @Test
    fun digitsInsideWordsStillCompareNumerically() {
        assertEquals(
            listOf("Take 2", "Take 9", "Take 11"),
            listOf("Take 11", "Take 2", "Take 9").sortedWith(NaturalOrder),
        )
    }
}
