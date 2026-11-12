package com.streamflixreborn.streamflix.ui.browse

import com.streamflixreborn.streamflix.utils.CinemaStatus
import android.content.Context
import android.content.res.ColorStateList
import android.view.View
import android.widget.TextView
import com.google.android.material.carousel.MaskableFrameLayout
import com.google.android.material.color.MaterialColors
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.models.WatchItem
import com.streamflixreborn.streamflix.ui.motion.FocusScaleAnimator
import com.streamflixreborn.streamflix.ui.motion.PressScale
import com.streamflixreborn.streamflix.ui.motion.Spring
import com.streamflixreborn.streamflix.ui.motion.springAlpha
import com.streamflixreborn.streamflix.utils.ReleaseStatus
import java.util.Calendar
import java.util.Locale
import kotlin.math.max

/**
 * Shared binding helpers for the browse cards (posters, Continue Watching, genre and people
 * tiles) so the phone and TV ViewHolders stay consistent.
 */
object BrowseCards {

    /** "7.8", or null when the provider has no rating. */
    fun ratingValue(rating: Double?): String? =
        rating?.takeIf { it > 0.0 }?.let { String.format(Locale.ROOT, "%.1f", it) }

    /** Shows "★ 7.8" in [chip], or hides it. */
    fun bindRating(chip: TextView, rating: Double?) {
        val value = ratingValue(rating)
        chip.text = value?.let { chip.context.getString(R.string.browse_rating_star, it) }
        chip.visibility = if (value == null) View.GONE else View.VISIBLE
    }

    /**
     * Rating chip that turns into a tinted "Soon · Nov 12" while the title is not out yet, so it is
     * obvious on the poster. The default look is restored whenever the chip is rebound.
     */
    fun bindRating(chip: TextView, rating: Double?, released: Calendar?) {
        if (!ReleaseStatus.isUnreleased(released) || released == null) {
            chip.backgroundTintList = null
            chip.setTextColor(MaterialColors.getColor(chip, com.google.android.material.R.attr.colorOnSurface))
            bindRating(chip, rating)
            return
        }
        chip.text = chip.context.getString(R.string.browse_coming_soon, ReleaseStatus.shortDate(chip.context, released))
        chip.backgroundTintList = ColorStateList.valueOf(
            MaterialColors.getColor(chip, com.google.android.material.R.attr.colorPrimaryContainer),
        )
        chip.setTextColor(MaterialColors.getColor(chip, com.google.android.material.R.attr.colorOnPrimaryContainer))
        chip.visibility = View.VISIBLE
    }

    /**
     * [bindRating] for a film, plus a tinted "In cinemas" while it is only in theatres (so any copy
     * online is a camera recording). The lookup is cached and runs in the background.
     */
    fun bindMovieRating(chip: TextView, movie: com.streamflixreborn.streamflix.models.Movie) {
        bindRating(chip, movie.rating, movie.released)
        CinemaStatus.whenOnlyInCinemas(movie.id, movie.released?.timeInMillis, chip) { styleInCinemas(chip) }
    }

    /** Tints [chip] and sets it to "In cinemas". */
    fun styleInCinemas(chip: TextView) {
        chip.text = chip.context.getString(R.string.browse_in_cinemas)
        chip.backgroundTintList = ColorStateList.valueOf(
            MaterialColors.getColor(chip, com.google.android.material.R.attr.colorTertiaryContainer),
        )
        chip.setTextColor(MaterialColors.getColor(chip, com.google.android.material.R.attr.colorOnTertiaryContainer))
        chip.visibility = View.VISIBLE
    }

    /** First letter or digit of [title], shown on the tonal placeholder. */
    fun initial(title: String?): String =
        title?.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: ""

    /** Watch progress 0..100. */
    fun progressPercent(history: WatchItem.WatchHistory?): Int {
        if (history == null || history.durationMillis <= 0) return 0
        return (history.lastPlaybackPositionMillis * 100 / history.durationMillis.toDouble())
            .toInt().coerceIn(0, 100)
    }

    /** "23 min left", "1 h 5 min left" or "Almost done"; null when the length is unknown. */
    fun remainingText(context: Context, history: WatchItem.WatchHistory?): String? {
        if (history == null || history.durationMillis <= 0) return null
        val remainingMinutes =
            max(0L, history.durationMillis - history.lastPlaybackPositionMillis) / 60_000L
        return when {
            remainingMinutes < 1 -> context.getString(R.string.browse_almost_done)
            remainingMinutes >= 60 -> context.getString(
                R.string.browse_hours_minutes_left,
                (remainingMinutes / 60).toInt(),
                (remainingMinutes % 60).toInt(),
            )
            else -> context.resources.getQuantityString(
                R.plurals.browse_minutes_left,
                remainingMinutes.toInt(),
                remainingMinutes.toInt(),
            )
        }
    }

    /** Phone press feedback (0.96 spring). Installed once per view. */
    fun attachPress(view: View) {
        if (view.getTag(R.id.sf_browse_press_scale) != null) return
        view.setTag(R.id.sf_browse_press_scale, PressScale.attach(view))
    }

    /**
     * TV focus: the tile springs to 1.08 and lifts (no ring here; the card's stateful stroke
     * draws it). [reveal] views fade in while focused (titles below posters). [onFocus] runs
     * after the animation, e.g. to update the home hero.
     */
    fun attachTvFocus(
        root: View,
        vararg reveal: View,
        ring: Boolean = false,
        onFocus: ((Boolean) -> Unit)? = null,
    ) {
        root.setOnFocusChangeListener { _, hasFocus ->
            for (view in reveal) view.springAlpha(if (hasFocus) 1f else 0f, Spring.EffectsFast)
            onFocus?.invoke(hasFocus)
        }
        if (ring) {
            FocusScaleAnimator.attach(root)
        } else {
            FocusScaleAnimator.attach(root, ringWidthPx = 0)
        }
        val focused = root.isFocused
        for (view in reveal) view.alpha = if (focused) 1f else 0f
        if (!focused) {
            root.scaleX = 1f
            root.scaleY = 1f
            root.translationZ = 0f
        }
    }

    /**
     * Phone hero carousel: as the carousel masks an item down to the small peeking size, keep
     * its text inside the visible part and fade it out, so only the large item reads.
     */
    fun bindHeroMask(container: MaskableFrameLayout, content: View) {
        container.setOnMaskChangedListener { mask ->
            val width = container.width.toFloat()
            if (width <= 0f) return@setOnMaskChangedListener
            content.translationX = mask.left
            val visible = mask.width() / width
            content.alpha = ((visible - 0.55f) / 0.35f).coerceIn(0f, 1f)
        }
    }

    /** Joins the non-empty parts with " · ". */
    fun joinMeta(context: Context, vararg parts: String?): String =
        parts.filter { !it.isNullOrBlank() }
            .joinToString(context.getString(R.string.browse_meta_separator))

    /** Column count for a phone grid: at least 3, more on wide screens. */
    fun gridSpanCount(context: Context): Int {
        val res = context.resources
        val minItem = res.getDimension(R.dimen.browse_grid_min_item_width) / res.displayMetrics.density
        return max(3, (res.configuration.screenWidthDp / minItem).toInt())
    }
}
