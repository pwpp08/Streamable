package com.streamflixreborn.streamflix.fragments.queue

import android.content.Context
import android.text.SpannableStringBuilder
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.text.bold
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.databinding.ItemQueueCardMobileBinding
import com.streamflixreborn.streamflix.databinding.ItemQueueCardTvBinding
import com.streamflixreborn.streamflix.databinding.ItemQueueProgressMobileBinding
import com.streamflixreborn.streamflix.ui.motion.FocusScaleAnimator
import com.streamflixreborn.streamflix.utils.loadMoviePoster
import com.streamflixreborn.streamflix.utils.loadTvShowPoster

/**
 * Queue items: poster tiles (phone and TV), and on the phone's Up next a full-width row with
 * the season as segments and a tick that marks the episode watched.
 */
class QueueAdapter(
    private val isTv: Boolean,
    private val section: () -> QueueViewModel.Section,
    private val onClick: (QueueEntry) -> Unit,
    private val onLongClick: (QueueEntry) -> Unit,
    private val onMarkWatched: (QueueEntry) -> Unit = {},
) : ListAdapter<QueueEntry, RecyclerView.ViewHolder>(Diff) {

    /** The same views from either tile layout. */
    private class TileHolder(
        root: View,
        val poster: ImageView,
        val initial: TextView,
        val kind: TextView,
        val reaction: TextView,
        val progress: LinearProgressIndicator,
        val title: TextView,
        val meta: TextView,
    ) : RecyclerView.ViewHolder(root)

    private class RowHolder(val binding: ItemQueueProgressMobileBinding) : RecyclerView.ViewHolder(binding.root)

    /** Rows span the whole grid. */
    fun isRow(position: Int): Boolean = getItemViewType(position) == TYPE_ROW

    override fun getItemViewType(position: Int): Int =
        if (!isTv && section() == QueueViewModel.Section.UP_NEXT) TYPE_ROW else TYPE_TILE

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        if (viewType == TYPE_ROW) return RowHolder(ItemQueueProgressMobileBinding.inflate(inflater, parent, false))
        return if (isTv) {
            val b = ItemQueueCardTvBinding.inflate(inflater, parent, false)
            FocusScaleAnimator.attach(b.root, ringWidthPx = 0)
            TileHolder(b.root, b.ivQueuePoster, b.tvQueueInitial, b.tvQueueKind, b.tvQueueReaction, b.pbQueueProgress, b.tvQueueTitle, b.tvQueueMeta)
        } else {
            val b = ItemQueueCardMobileBinding.inflate(inflater, parent, false)
            TileHolder(b.root, b.ivQueuePoster, b.tvQueueInitial, b.tvQueueKind, b.tvQueueReaction, b.pbQueueProgress, b.tvQueueTitle, b.tvQueueMeta)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val entry = getItem(position)
        when (holder) {
            is RowHolder -> bindRow(holder.binding, entry)
            is TileHolder -> bindTile(holder, entry)
        }
        holder.itemView.setOnClickListener { onClick(entry) }
        holder.itemView.setOnLongClickListener { onLongClick(entry); true }
    }

    private fun bindTile(v: TileHolder, entry: QueueEntry) {
        val context = v.itemView.context
        v.title.text = entry.title
        v.initial.text = entry.title.trim().take(1).uppercase()
        v.kind.setText(if (entry.isTv) R.string.queue_kind_show else R.string.queue_kind_movie)
        v.reaction.isVisible = entry.reaction != null
        v.reaction.text = entry.reaction?.emoji
        v.progress.isVisible = entry.progress != null
        v.progress.progress = entry.progress ?: 0
        v.meta.text = meta(context, entry)
        loadPoster(v.poster, entry)
        v.itemView.contentDescription = listOfNotNull(entry.title, v.meta.text?.toString()?.takeIf { it.isNotBlank() })
            .joinToString(", ")
    }

    private fun bindRow(b: ItemQueueProgressMobileBinding, entry: QueueEntry) {
        val context = b.root.context
        b.tvProgressTitle.text = entry.title
        b.tvProgressInitial.text = entry.title.trim().take(1).uppercase()
        loadPoster(b.ivProgressPoster, entry)

        val number = entry.episodeNumber
        if (entry.isTv && number != null) {
            val code = context.getString(R.string.queue_progress_episode, entry.seasonNumber ?: 0, number)
            b.tvProgressEpisode.text = SpannableStringBuilder()
                .bold { append(code) }
                .apply { entry.episode?.title?.takeIf { it.isNotBlank() }?.let { append("  ").append(it) } }
            val left = entry.episodesLeft
            b.tvProgressLeft.isVisible = left != null && left > 0
            if (left != null) b.tvProgressLeft.text = context.resources.getQuantityString(R.plurals.queue_progress_episodes_left, left, left)
        } else {
            b.tvProgressEpisode.text = listOfNotNull(
                context.getString(R.string.queue_kind_movie),
                entry.year,
            ).joinToString(" · ")
            val remaining = entry.remainingMs
            b.tvProgressLeft.isVisible = remaining != null && remaining > 60_000
            if (remaining != null) b.tvProgressLeft.text = context.getString(R.string.queue_meta_minutes_left, (remaining / 60_000).toInt())
        }

        val season = entry.seasonWatched
        b.spvProgress.isVisible = season != null
        season?.let(b.spvProgress::setWatched)
        // Films (and shows without a stored season) show the playback position instead.
        b.pbProgressMovie.isVisible = season == null && entry.progress != null
        b.pbProgressMovie.progress = entry.progress ?: 0

        b.btnProgressWatched.setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
            onMarkWatched(entry)
        }
        b.root.contentDescription = listOf(b.tvProgressTitle.text, b.tvProgressEpisode.text, b.tvProgressLeft.text)
            .joinToString(", ")
    }

    private fun loadPoster(view: ImageView, entry: QueueEntry) {
        val movie = entry.movie
        val show = entry.tvShow
        when {
            movie != null && !movie.poster.isNullOrBlank() -> view.loadMoviePoster(movie)
            show != null && !show.poster.isNullOrBlank() -> view.loadTvShowPoster(show)
            else -> Glide.with(view).load(entry.poster).centerCrop().into(view)
        }
    }

    private fun meta(context: Context, entry: QueueEntry): String {
        if (section() == QueueViewModel.Section.UP_NEXT) {
            val season = entry.seasonNumber
            val number = entry.episodeNumber
            if (entry.isTv && number != null) {
                return when {
                    season == null || season == 0 -> context.getString(R.string.queue_meta_episode_only, number)
                    entry.progress != null -> context.getString(R.string.queue_meta_episode, season, number)
                    else -> context.getString(R.string.queue_meta_next_episode, season, number)
                }
            }
            val remaining = entry.remainingMs
            if (remaining != null && remaining > 60_000) {
                return context.getString(R.string.queue_meta_minutes_left, (remaining / 60_000).toInt())
            }
        }
        return listOfNotNull(entry.year, entry.rating?.takeIf { it > 0 }?.let { "★ %.1f".format(it) }).joinToString(" · ")
    }

    private object Diff : DiffUtil.ItemCallback<QueueEntry>() {
        override fun areItemsTheSame(oldItem: QueueEntry, newItem: QueueEntry) = oldItem.key == newItem.key
        override fun areContentsTheSame(oldItem: QueueEntry, newItem: QueueEntry) =
            oldItem.title == newItem.title &&
                oldItem.poster == newItem.poster &&
                oldItem.reaction == newItem.reaction &&
                oldItem.progress == newItem.progress &&
                oldItem.seasonNumber == newItem.seasonNumber &&
                oldItem.episodeNumber == newItem.episodeNumber &&
                oldItem.episodesLeft == newItem.episodesLeft &&
                oldItem.seasonWatched == newItem.seasonWatched &&
                oldItem.remainingMs == newItem.remainingMs &&
                oldItem.inQueue == newItem.inQueue &&
                oldItem.watched == newItem.watched
    }

    private companion object {
        const val TYPE_TILE = 0
        const val TYPE_ROW = 1
    }
}
