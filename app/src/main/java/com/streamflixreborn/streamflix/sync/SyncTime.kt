package com.streamflixreborn.streamflix.sync

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** ISO-8601 helpers for Postgres `timestamptz` values, without java.time (minSdk 21). */
internal object SyncTime {
    private val UTC: TimeZone = TimeZone.getTimeZone("UTC")
    private val PATTERN = Regex(
        """^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2}):(\d{2})(?:\.(\d+))?(Z|[+-]\d{2}(?::?\d{2})?)?$""",
    )

    fun iso(millis: Long): String {
        val c = Calendar.getInstance(UTC).apply { timeInMillis = millis }
        return String.format(
            Locale.ROOT,
            "%04d-%02d-%02dT%02d:%02d:%02d.%03dZ",
            c.get(Calendar.YEAR),
            c.get(Calendar.MONTH) + 1,
            c.get(Calendar.DAY_OF_MONTH),
            c.get(Calendar.HOUR_OF_DAY),
            c.get(Calendar.MINUTE),
            c.get(Calendar.SECOND),
            c.get(Calendar.MILLISECOND),
        )
    }

    /** Parses Postgres/ISO timestamps such as `2026-11-02T10:11:12.345678+00:00`. */
    fun parse(value: String?): Long? {
        val match = PATTERN.matchEntire(value?.trim() ?: return null) ?: return null
        val g = match.groupValues
        val c = Calendar.getInstance(UTC).apply {
            clear()
            set(g[1].toInt(), g[2].toInt() - 1, g[3].toInt(), g[4].toInt(), g[5].toInt(), g[6].toInt())
        }
        val millis = g[7].takeIf { it.isNotEmpty() }?.padEnd(3, '0')?.take(3)?.toInt() ?: 0
        val offsetMinutes = g[8].let { zone ->
            if (zone.isEmpty() || zone == "Z") 0 else {
                val sign = if (zone[0] == '-') -1 else 1
                val digits = zone.substring(1).replace(":", "")
                val hours = digits.take(2).toInt()
                val minutes = digits.drop(2).takeIf { it.isNotEmpty() }?.toInt() ?: 0
                sign * (hours * 60 + minutes)
            }
        }
        return c.timeInMillis + millis - offsetMinutes * 60_000L
    }
}
