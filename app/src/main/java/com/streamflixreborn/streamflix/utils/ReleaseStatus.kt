package com.streamflixreborn.streamflix.utils

import android.content.Context
import android.text.format.DateUtils
import java.util.Calendar

/**
 * Whether a movie or show is out yet. A title counts as unreleased until its release day starts,
 * so anything dated today or earlier can be played; titles without a date are treated as out.
 */
object ReleaseStatus {

    fun isUnreleased(released: Calendar?, nowMillis: Long = System.currentTimeMillis()): Boolean =
        released != null && released.timeInMillis > nowMillis

    fun isUnreleased(releasedMillis: Long?, nowMillis: Long = System.currentTimeMillis()): Boolean =
        releasedMillis != null && releasedMillis > nowMillis

    /** "Nov 12" (with the year when it is not this year). */
    fun shortDate(context: Context, released: Calendar): String {
        val sameYear = Calendar.getInstance().get(Calendar.YEAR) == released.get(Calendar.YEAR)
        val flags = DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or
            (if (sameYear) DateUtils.FORMAT_NO_YEAR else DateUtils.FORMAT_SHOW_YEAR)
        return DateUtils.formatDateTime(context, released.timeInMillis, flags)
    }

    /** "Nov 12" for a date stored as UTC midnight (TMDb release dates), the same day in every time zone. */
    fun shortDateUtc(context: Context, utcMillis: Long): String {
        val year = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply { timeInMillis = utcMillis }.get(Calendar.YEAR)
        val sameYear = Calendar.getInstance().get(Calendar.YEAR) == year
        val flags = DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_UTC or
            (if (sameYear) DateUtils.FORMAT_NO_YEAR else DateUtils.FORMAT_SHOW_YEAR)
        return DateUtils.formatDateTime(context, utcMillis, flags)
    }

    /** "November 12, 2026". */
    fun longDate(context: Context, released: Calendar): String =
        DateUtils.formatDateTime(context, released.timeInMillis, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR)
}
