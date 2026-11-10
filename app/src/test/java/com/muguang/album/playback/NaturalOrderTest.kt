package com.muguang.album.playback

import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class NaturalOrderTest {
    @Test fun sortsNumericNamesWithoutStringOrIntegerOverflowMistakes() {
        val names = listOf("10.jpg", "2.jpg", "1.jpg", "123456789012345678901.jpg", "02.jpg")
        assertEquals(listOf("1.jpg", "2.jpg", "02.jpg", "10.jpg", "123456789012345678901.jpg"), names.sortedWith(naturalComparator { it }))
    }

    @Test fun shuffleRoundContainsEveryTrackAndAvoidsCrossRoundRepeat() {
        val items = (0..9).toList()
        repeat(100) { seed ->
            val round = shuffledRound(items, 5, Random(seed))
            assertEquals(items.toSet(), round.toSet())
            assertEquals(items.size, round.size)
            assertNotEquals(5, round.first())
        }
    }

    @Test fun singleOrEmptyPlaylistRemainsValid() {
        assertEquals(listOf("one"), shuffledRound(listOf("one"), "one"))
        assertEquals(emptyList<String>(), shuffledRound(emptyList<String>()))
    }
}
