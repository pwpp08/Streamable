package com.streamflixreborn.streamflix.extractors

import org.junit.Assert.assertEquals
import org.junit.Test

class VideasyKeystreamTest {

    /** Reference words produced by the original keystream implementation for this seed and key. */
    @Test
    fun keystreamMatchesReferenceVector() {
        val state = VideasyExtractor.Companion.State("test-seed", 1622)
        val expected = longArrayOf(
            1_565_862_539, 4_109_863_583, 230_583_110, 1_106_669_467,
            4_046_983_225, 3_597_725_526, 55_526_469, 3_807_938_347,
        )
        expected.forEachIndexed { counter, word ->
            assertEquals(word, state.nextWord(counter).toLong() and 0xFFFFFFFFL)
        }
    }
}
