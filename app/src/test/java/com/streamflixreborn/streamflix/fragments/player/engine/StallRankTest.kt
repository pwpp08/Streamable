package com.streamflixreborn.streamflix.fragments.player.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class StallRankTest {
    @Test
    fun vidzeeBeatsAStickyVixSrcCappedAt720p() {
        val ranked = ServerRanker.rank(
            listOf(
                com.streamflixreborn.streamflix.models.Video.Server("v", "VixSrc"),
                com.streamflixreborn.streamflix.models.Video.Server("e", "2Embed"),
                com.streamflixreborn.streamflix.models.Video.Server("n", "Nflix (Vidzee)"),
            ),
            stickyName = "VixSrc",
            score = { null },
            height = { null },
        )
        assertEquals("Nflix (Vidzee)", ranked.first().name)
    }

    @Test
    fun knownSlowVixSrcRanksBelowVideasyEvenWhenSticky() {
        val servers = listOf(
            com.streamflixreborn.streamflix.models.Video.Server("v", "VixSrc"),
            com.streamflixreborn.streamflix.models.Video.Server("b", "Videasy Boise"),
        )
        val ranked = ServerRanker.rank(servers, stickyName = "VixSrc", score = { null }, height = { null })
        assertEquals("Videasy Boise", ranked.first().name)
    }
}
