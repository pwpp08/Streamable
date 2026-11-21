package com.streamflixreborn.streamflix.utils

import java.text.Normalizer
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Typo-tolerant lookups on top of a search that only does exact word matching (TMDb and most
 * providers): when the typed text finds nothing close, the same search is repeated with shortened
 * and single-word variants, and everything found is ranked by how near its title is to the text.
 */
object FuzzySearch {

    /** A title this similar (0..1) to the query counts as a good match. */
    const val GOOD = 0.75
    private const val MIN_KEPT = 0.55
    private const val MAX_VARIANTS = 5

    fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}"), "")
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()

    fun distance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(current[j - 1] + 1, previous[j] + 1, previous[j - 1] + cost)
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
    }

    /**
     * 1.0 for the same text, falling towards 0. A title that merely starts with what was typed is
     * judged on its first words only, so a half-typed name is not punished for being short.
     */
    fun similarity(query: String, title: String): Double {
        val q = normalize(query)
        val t = normalize(title)
        if (q.isEmpty() || t.isEmpty()) return 0.0
        if (q == t) return 1.0
        val whole = 1.0 - distance(q, t).toDouble() / maxOf(q.length, t.length)
        val head = if (t.length > q.length) {
            val prefix = t.take(q.length)
            0.95 * (1.0 - distance(q, prefix).toDouble() / q.length)
        } else 0.0
        return maxOf(whole, head)
    }

    /** Other texts worth searching for when [query] finds nothing: shorter prefixes, then single words. */
    fun variants(query: String): List<String> {
        val q = query.trim().replace(Regex("\\s+"), " ")
        if (q.length < 4) return emptyList()
        val out = linkedSetOf<String>()
        for (keep in listOf(0.8, 0.65, 0.5)) {
            val cut = (q.length * keep).toInt()
            if (cut >= 3) out += q.take(cut).trim()
        }
        val words = q.split(' ').filter { it.length >= 4 }
        if (words.size > 1) {
            out += words.sortedByDescending { it.length }.take(2)
            // A typo in the first word: the rest of the title often still finds it.
            out += q.substringAfter(' ')
        }
        out.remove(q)
        return out.filter { it.length >= 3 }.take(MAX_VARIANTS)
    }

    /**
     * Runs [fetch] for [query]; unless that already found a good match, also for the [variants],
     * and returns everything ranked by closeness to [query] (stable for equal scores, so the
     * source's own order, usually popularity, still breaks ties). [keyOf] removes duplicates.
     */
    suspend fun <T> search(
        query: String,
        titleOf: (T) -> String?,
        keyOf: (T) -> Any,
        fetch: suspend (String) -> List<T>,
    ): List<T> {
        val primary = fetch(query)
        val best = primary.maxOfOrNull { similarity(query, titleOf(it).orEmpty()) } ?: 0.0
        if (best >= GOOD) return primary
        val extra = coroutineScope {
            variants(query).map { variant -> async { runCatching { fetch(variant) }.getOrDefault(emptyList()) } }.awaitAll()
        }.flatten()
        val merged = (primary + extra).distinctBy(keyOf)
        val ranked = merged.withIndex()
            .map { (index, item) -> Triple(index, item, similarity(query, titleOf(item).orEmpty())) }
        val kept = ranked.filter { it.third >= MIN_KEPT }
        // Nothing close at all: better to show what the source gave than an empty screen.
        if (kept.isEmpty()) return primary
        return kept.sortedWith(compareByDescending<Triple<Int, T, Double>> { it.third }.thenBy { it.first }).map { it.second }
    }
}
