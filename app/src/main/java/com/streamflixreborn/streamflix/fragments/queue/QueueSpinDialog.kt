package com.streamflixreborn.streamflix.fragments.queue

import android.app.Dialog
import android.view.LayoutInflater
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.databinding.DialogQueueSpinBinding

/**
 * "Can't decide?": a full-screen wheel of Watchlist posters. Whatever sits under the pointer
 * is shown big at the top, ready to play; spin to let the wheel choose.
 */
object QueueSpinDialog {
    private const val MAX_TITLES = 16

    fun show(fragment: Fragment, entries: List<QueueEntry>, isTv: Boolean) {
        val context = fragment.requireContext()
        if (entries.size < 2) {
            Toast.makeText(context, R.string.queue_spin_need_more, Toast.LENGTH_LONG).show()
            return
        }
        val binding = DialogQueueSpinBinding.inflate(LayoutInflater.from(context))
        val dialog = Dialog(context, R.style.ThemeOverlay_StreamFlix_FullscreenDialog)
        dialog.setContentView(binding.root)

        var pool: List<QueueEntry> = emptyList()
        var current: QueueEntry? = null

        fun showEntry(entry: QueueEntry, settled: Boolean) {
            if (current?.key == entry.key) return
            current = entry
            binding.tvQueueSpinTitle.text = entry.title
            binding.tvQueueSpinMeta.text = listOfNotNull(
                context.getString(if (entry.isTv) R.string.queue_kind_show else R.string.queue_kind_movie),
                entry.year,
                entry.rating?.takeIf { it > 0 }?.let { "★ %.1f".format(it) },
            ).joinToString(" · ")
            binding.tvQueueSpinOverview.text = entry.overview
            binding.tvQueueSpinOverview.isVisible = !entry.overview.isNullOrBlank()
            binding.tvQueueSpinInitial.text = entry.title.take(1).uppercase()
            // Always there: Play, or when the title is not out yet, its release date (disabled).
            val unreleased = QueueNavigator.isUnreleased(entry)
            binding.btnQueueSpinPlay.isVisible = true
            binding.btnQueueSpinPlay.isEnabled = !unreleased
            binding.btnQueueSpinPlay.text = QueueNavigator.releaseLabel(context, entry)
                ?: context.getString(R.string.queue_action_play)
            val request = Glide.with(binding.ivQueueSpinBackdrop).load(entry.banner ?: entry.poster).centerCrop()
            if (settled) request.transition(DrawableTransitionOptions.withCrossFade(180)).into(binding.ivQueueSpinBackdrop)
            else request.into(binding.ivQueueSpinBackdrop)
        }

        fun setPool(filter: QueueViewModel.Filter) {
            val filtered = entries.filterFor(filter).takeIf { it.size >= 2 } ?: entries
            pool = filtered.shuffled().take(MAX_TITLES)
            current = null
            binding.wheelQueue.setItems(pool.map { it.title }, pool.map { it.poster })
            // Warm the backdrops so they keep up with the wheel.
            pool.forEach { Glide.with(context).load(it.banner ?: it.poster).centerCrop().preload() }
            showEntry(pool[binding.wheelQueue.selectedIndex], settled = true)
        }

        binding.wheelQueue.onSelected = { index -> pool.getOrNull(index)?.let { showEntry(it, settled = false) } }
        binding.wheelQueue.onSettled = { index ->
            pool.getOrNull(index)?.let { showEntry(it, settled = true) }
            if (isTv) (if (binding.btnQueueSpinPlay.isEnabled) binding.btnQueueSpinPlay else binding.btnQueueSpinDetails).requestFocus()
        }

        val movies = entries.count { !it.isTv }
        val shows = entries.size - movies
        binding.btnQueueSpinMovies.isEnabled = movies >= 2
        binding.btnQueueSpinShows.isEnabled = shows >= 2
        binding.tgQueueSpinFilter.check(R.id.btn_queue_spin_all)
        binding.tgQueueSpinFilter.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            setPool(
                when (id) {
                    R.id.btn_queue_spin_movies -> QueueViewModel.Filter.MOVIES
                    R.id.btn_queue_spin_shows -> QueueViewModel.Filter.SHOWS
                    else -> QueueViewModel.Filter.ALL
                },
            )
        }

        binding.btnQueueSpin.setOnClickListener { binding.wheelQueue.spin() }
        binding.btnQueueSpinClose.setOnClickListener { dialog.dismiss() }
        binding.btnQueueSpinPlay.setOnClickListener {
            val chosen = current ?: return@setOnClickListener
            binding.btnQueueSpinPlay.isEnabled = false
            QueueNavigator.playResolving(fragment, chosen) { busy ->
                if (!busy) dialog.dismiss()
            }
        }
        binding.btnQueueSpinDetails.setOnClickListener {
            val chosen = current ?: return@setOnClickListener
            dialog.dismiss()
            QueueNavigator.openDetails(fragment.findNavController(), chosen)
        }

        dialog.show()
        binding.wheelQueue.post { setPool(QueueViewModel.Filter.ALL) }
        (if (isTv) binding.btnQueueSpin else binding.wheelQueue).requestFocus()
    }
}
