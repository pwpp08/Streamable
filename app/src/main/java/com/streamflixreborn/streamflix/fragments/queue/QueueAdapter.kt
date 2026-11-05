package com.streamflixreborn.streamflix.fragments.queue

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.databinding.ItemQueueCardMobileBinding
import com.streamflixreborn.streamflix.databinding.ItemQueueCardTvBinding
import com.streamflixreborn.streamflix.ui.motion.FocusScaleAnimator
import com.streamflixreborn.streamflix.utils.loadMoviePoster
import com.streamflixreborn.streamflix.utils.loadTvShowPoster

/** Poster grid for the Queue tab; one layout for phones, a focusable one for TV. */
class QueueAdapter(
    private val isTv: Boolean,
    private val section: () -> QueueViewModel.Section,
    private val onClick: (QueueEntry) -> Unit,
    private val onLongClick: (QueueEntry) -> Unit,
) : ListAdapter<QueueEntry, QueueAdapter.Holder>(Diff) {

    class Holder(
        val root: ViewGroup,
        val bindings: Views,
    ) : RecyclerView.ViewHolder(root)

    /** The same views from either layout. */
    class Views(
        val poster: android.widget.ImageView,
        val initial: android.widget.TextView,
        val kind: android.widget.TextView,
        val reaction: android.widget.TextView,
        val progress: com.google.android.material.progressindicator.LinearProgressIndicator,
        val title: android.widget.TextView,
        val meta: android.widget.TextView,
    )

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val inflater = LayoutInflater.from(parent.context)
        return if (isTv) {
            val b = ItemQueueCardTvBinding.inflate(inflater, parent, false)
            FocusScaleAnimator.attach(b.root, ringWidthPx = 0)
            Holder(b.root, Views(b.ivQueuePoster, b.tvQueueInitial, b.tvQueueKind, b.tvQueueReaction, b.pbQueueProgress, b.tvQueueTitle, b.tvQueueMeta))
        } else {
            val b = ItemQueueCardMobileBinding.inflate(inflater, parent, false)
            Holder(b.root, Views(b.ivQueuePoster, b.tvQueueInitial, b.tvQueueKind, b.tvQueueReaction, b.pbQueueProgress, b.tvQueueTitle, b.tvQueueMeta))
        }
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val entry = getItem(position)
        val v = holder.bindings
        val context = holder.root.context

        v.title.text = entry.title
        v.initial.text = entry.title.trim().take(1).uppercase()
        v.kind.setText(if (entry.isTv) R.string.queue_kind_show else R.string.queue_kind_movie)
        v.reaction.isVisible = entry.reaction != null
        v.reaction.text = entry.reaction?.emoji
        v.progress.isVisible = entry.progress != null
        v.progress.progress = entry.progress ?: 0
        v.meta.text = meta(context, entry)

        val movie = entry.movie
        val show = entry.tvShow
        when {
            movie != null && !movie.poster.isNullOrBlank() -> v.poster.loadMoviePoster(movie)
            show != null && !show.poster.isNullOrBlank() -> v.poster.loadTvShowPoster(show)
            else -> Glide.with(v.poster).load(entry.poster).centerCrop().into(v.poster)
        }

        holder.root.setOnClickListener { onClick(entry) }
        holder.root.setOnLongClickListener { onLongClick(entry); true }
        holder.root.contentDescription = listOfNotNull(entry.title, v.meta.text?.toString()?.takeIf { it.isNotBlank() })
            .joinToString(", ")
    }

    private fun meta(context: android.content.Context, entry: QueueEntry): String {
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
                oldItem.remainingMs == newItem.remainingMs &&
                oldItem.inQueue == newItem.inQueue &&
                oldItem.watched == newItem.watched
    }
}
