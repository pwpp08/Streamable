package com.streamflixreborn.streamflix.utils

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

// The plain-Kotlin helpers from the Android app's Extensions.kt that the shared code uses.

fun String.toCalendar(): Calendar? {
    val patterns = listOf(
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ENGLISH),
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm'Z'", Locale.ENGLISH),
        SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH),
        SimpleDateFormat("d MMMM yyyy ('USA')", Locale.ENGLISH),
        SimpleDateFormat("d MMMM yyyy", Locale.FRENCH),
        SimpleDateFormat("yyyy", Locale.ENGLISH),
        SimpleDateFormat("MMM d, yyyy", Locale.ENGLISH),
        SimpleDateFormat("MMMM d, yyyy ('United' 'States')", Locale.ENGLISH),
        SimpleDateFormat("MMM. d, yyyy", Locale.ENGLISH),
    )
    patterns.forEach { sdf ->
        try {
            return Calendar.getInstance().apply { time = sdf.parse(this@toCalendar)!! }
        } catch (_: Exception) {
        }
    }
    return null
}

fun Calendar.format(pattern: String): String? = try {
    SimpleDateFormat(pattern, Locale.getDefault()).format(this.time)
} catch (e: Exception) {
    null
}

suspend fun <T> retry(retries: Int, predicate: suspend (attempt: Int) -> T): T {
    require(retries > 0) { "Expected positive amount of retries, but had $retries" }
    var throwable: Throwable? = null
    (1..retries).forEach { attempt ->
        try {
            return predicate(attempt)
        } catch (e: Throwable) {
            throwable = e
        }
    }
    throw throwable!!
}

fun <T> List<T>.safeSubList(fromIndex: Int, toIndex: Int): List<T> {
    if (fromIndex > toIndex) return emptyList()
    return subList(
        max(min(fromIndex.coerceAtLeast(0), size), 0),
        max(min(toIndex.coerceAtMost(size), size), 0),
    )
}

@Suppress("UNCHECKED_CAST")
fun <K, V> Map<K, V?>.filterNotNullValues() = filterValues { it != null } as Map<K, V>
