package com.streamflixreborn.streamflix.ui.details

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.models.Genre
import com.streamflixreborn.streamflix.models.WatchItem
import com.streamflixreborn.streamflix.ui.motion.FocusScaleAnimator
import java.util.Locale

/** Shared bits of the movie / TV-show details headers (phone and TV). */
object DetailsUi {

    /** "2 h 5 min" / "45 min" with the existing runtime strings, or null. */
    fun runtime(context: Context, minutes: Int?, hoursMinutesRes: Int, minutesRes: Int): String? {
        val runtime = minutes ?: return null
        val hours = runtime / 60
        val rest = runtime % 60
        return if (hours > 0) context.getString(hoursMinutesRes, hours, rest)
        else context.getString(minutesRes, rest)
    }

    fun rating(value: Double?): String =
        value?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "N/A"

    /** Sets [text] and hides the view when it is empty (the meta line's dots follow). */
    fun TextView.setTextOrGone(text: CharSequence?) {
        this.text = text
        isVisible = !text.isNullOrEmpty()
    }

    /** Fills [group] with display-only assist chips; hides [container] when there are none. */
    fun bindGenreChips(group: ChipGroup, container: View, genres: List<Genre>) {
        container.isVisible = genres.isNotEmpty()
        val names = genres.map { it.name }
        if (group.tag == names) return
        group.tag = names
        group.removeAllViews()
        val inflater = LayoutInflater.from(group.context)
        names.forEach { name ->
            (inflater.inflate(R.layout.item_details_genre_chip, group, false) as Chip).also {
                it.text = name
                group.addView(it)
            }
        }
    }

    /** "23:10" or "1:02:03". */
    fun playbackTime(millis: Long): String {
        val totalSeconds = (millis / 1000).coerceAtLeast(0)
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
        else String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
    }

    /** Percent watched, or null when there is no resumable progress. */
    fun progressPercent(history: WatchItem.WatchHistory?): Int? {
        if (history == null || history.durationMillis <= 0) return null
        return (history.lastPlaybackPositionMillis * 100 / history.durationMillis.toDouble()).toInt()
    }

    /** "Play" or "Resume 23:10". */
    fun playLabel(context: Context, history: WatchItem.WatchHistory?): String =
        if (history != null && history.lastPlaybackPositionMillis > 0) {
            context.getString(R.string.details_resume_at, playbackTime(history.lastPlaybackPositionMillis))
        } else {
            context.getString(R.string.details_play)
        }

    data class MenuAction(val title: String, val run: () -> Unit)

    /**
     * Split-button chevron: shows [actions] in a popup anchored to the split button and keeps the
     * chevron "checked" (rotated) while the menu is open.
     */
    fun showSplitMenu(chevron: MaterialButton, anchor: View, actions: List<MenuAction>) {
        if (actions.isEmpty()) {
            chevron.isChecked = false
            return
        }
        val popup = PopupMenu(chevron.context, anchor)
        actions.forEachIndexed { index, action -> popup.menu.add(0, index, index, action.title) }
        popup.setOnMenuItemClickListener { item ->
            actions.getOrNull(item.itemId)?.run?.invoke()
            true
        }
        popup.setOnDismissListener { chevron.isChecked = false }
        chevron.isChecked = true
        popup.show()
    }

    /**
     * Phone backdrop parallax: the fixed backdrop behind [list] drifts up at half the scroll
     * speed while the header is on screen. Translation only, so it stays cheap.
     */
    fun attachBackdropParallax(list: RecyclerView, backdrop: View) {
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return
                val first = recyclerView.getChildAt(0)
                val scrolled = if (layoutManager.findFirstVisibleItemPosition() == 0 && first != null) {
                    -first.top
                } else {
                    backdrop.height
                }
                backdrop.translationY = -scrolled.coerceIn(0, backdrop.height) * 0.5f
            }
        })
    }

    /** TV buttons: the style swaps the fill on focus; this adds the spring scale (no ring). */
    fun attachTvButtonFocus(vararg buttons: View) {
        buttons.forEach {
            FocusScaleAnimator.attach(it, focusedScale = 1.06f, liftPx = 0f, ringWidthPx = 0)
        }
    }
}
