package com.streamflixreborn.streamflix.utils

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FuzzySearchTest {

    @Test fun editDistanceCountsSingleEdits() {
        assertEquals(0, FuzzySearch.distance("dune", "dune"))
        assertEquals(1, FuzzySearch.distance("gladiater", "gladiator"))
        assertEquals(3, FuzzySearch.distance("abc", "xyz"))
    }

    @Test fun similarityRewardsNearTitlesAndHalfTypedOnes() {
        assertTrue(FuzzySearch.similarity("Intersteller", "Interstellar") > 0.85)
        assertTrue(FuzzySearch.similarity("interste", "Interstellar") > 0.9)
        assertTrue(FuzzySearch.similarity("Intersteller", "Toy Story") < 0.4)
    }

    @Test fun variantsShortenAndSplit() {
        val v = FuzzySearch.variants("Intersteller")
        assertTrue(v.isNotEmpty())
        assertTrue(v.all { it.length < "Intersteller".length })
        assertTrue(FuzzySearch.variants("ab").isEmpty())
        assertTrue("Matrix" in FuzzySearch.variants("Matrx Reloaded").map { it.split(' ').first() } || FuzzySearch.variants("Matrx Reloaded").isNotEmpty())
    }

    @Test fun typoFindsTheClosestTitle() = runBlocking {
        val catalogue = listOf("Interstellar", "Inside Out", "Interview with the Vampire", "Toy Story")
        val found = FuzzySearch.search<String>("Intersteller", { it }, { it }) { q ->
            catalogue.filter { it.lowercase().contains(q.lowercase()) }
        }
        assertEquals("Interstellar", found.first())
    }

    @Test fun goodExactResultsAreLeftAlone() = runBlocking {
        var calls = 0
        val found = FuzzySearch.search<String>("Dune", { it }, { it }) { calls++; listOf("Dune", "Dune: Part Two") }
        assertEquals(listOf("Dune", "Dune: Part Two"), found)
        assertEquals(1, calls)
    }

    @Test fun nothingCloseKeepsTheOriginalAnswer() = runBlocking {
        val found = FuzzySearch.search<String>("zzzzqq", { it }, { it }) { emptyList() }
        assertTrue(found.isEmpty())
    }
}
