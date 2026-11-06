package com.streamflixreborn.streamflix.fragments.queue

import android.os.Bundle
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.database.AppDatabase
import com.streamflixreborn.streamflix.databinding.FragmentQueueMobileBinding
import com.streamflixreborn.streamflix.fragments.queue.QueueViewModel.Filter
import com.streamflixreborn.streamflix.fragments.queue.QueueViewModel.Section
import com.streamflixreborn.streamflix.fragments.queue.QueueViewModel.Sort
import com.streamflixreborn.streamflix.ui.SpacingItemDecoration
import com.streamflixreborn.streamflix.ui.browse.BrowseCards
import com.streamflixreborn.streamflix.utils.viewModelsFactory
import kotlinx.coroutines.launch

class QueueMobileFragment : Fragment() {

    private var _binding: FragmentQueueMobileBinding? = null
    private val binding get() = _binding!!
    private val viewModel by viewModelsFactory { QueueViewModel(AppDatabase.getInstance(requireContext())) }
    private lateinit var adapter: QueueAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentQueueMobileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        adapter = QueueAdapter(
            isTv = false,
            section = { viewModel.state.value.section },
            onClick = ::onEntryClick,
            onLongClick = { QueueOptions.show(this, it, viewModel, isTv = false) },
            onMarkWatched = { viewModel.markNextWatched(requireContext().applicationContext, it) },
            onPosterClick = ::onPosterClick,
        )
        val spanCount = BrowseCards.gridSpanCount(requireContext())
        binding.rvQueue.layoutManager = GridLayoutManager(requireContext(), spanCount).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int) = if (adapter.isRow(position)) spanCount else 1
            }
        }
        binding.rvQueue.adapter = adapter
        binding.rvQueue.addItemDecoration(SpacingItemDecoration(resources.getDimensionPixelSize(R.dimen.browse_row_item_gap)))

        binding.tgQueueSection.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            viewModel.setSection(
                when (checkedId) {
                    R.id.btn_queue_up_next -> Section.UP_NEXT
                    R.id.btn_queue_watched -> Section.WATCHED
                    else -> Section.WATCHLIST
                },
            )
            binding.rvQueue.scrollToPosition(0)
        }
        binding.cgQueueFilter.setOnCheckedStateChangeListener { _, ids ->
            viewModel.setFilter(
                when (ids.firstOrNull()) {
                    R.id.chip_queue_movies -> Filter.MOVIES
                    R.id.chip_queue_shows -> Filter.SHOWS
                    else -> Filter.ALL
                },
            )
        }
        buildRatingChips()
        binding.cgQueueRating.setOnCheckedStateChangeListener { _, ids ->
            if (syncingRatingChips) return@setOnCheckedStateChangeListener
            val chosen = ids.firstOrNull()?.let { id -> binding.cgQueueRating.findViewById<Chip>(id)?.tag as? RatingFilter }
            viewModel.setRating(chosen ?: RatingFilter.Everything)
        }
        binding.btnQueueSort.setOnClickListener { showSortDialog() }
        binding.btnQueueSpin.setOnClickListener {
            QueueSpinDialog.show(this, viewModel.state.value.lists.watchlist.filterFor(viewModel.state.value.filter), isTv = false)
        }
        binding.btnQueueSwipe.setOnClickListener { findNavController().navigate(R.id.queue_swipe) }
        binding.btnQueueCalendar.setOnClickListener { QueueCalendarDialog.show(this, viewModel) }
        binding.btnQueueImport.setOnClickListener { QueueImportDialog.show(this, viewModel) }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state.flowWithLifecycle(viewLifecycleOwner.lifecycle, Lifecycle.State.STARTED).collect(::render)
        }
    }

    /** Up next: a show opens its seasons; a film (or anything else) goes where tapping would play. */
    private fun onEntryClick(entry: QueueEntry) {
        when {
            viewModel.state.value.section == Section.UP_NEXT && entry.isTv -> QueueShowDialog.show(this, entry)
            viewModel.state.value.section == Section.UP_NEXT && QueueNavigator.canPlay(entry) ->
                QueueNavigator.play(requireContext(), findNavController(), entry)
            else -> QueueNavigator.openDetails(findNavController(), entry)
        }
    }

    /** The poster in an Up next row starts the next episode (or resumes the film). */
    private fun onPosterClick(entry: QueueEntry) {
        if (QueueNavigator.canPlay(entry)) QueueNavigator.play(requireContext(), findNavController(), entry)
        else QueueNavigator.openDetails(findNavController(), entry)
    }

    private var syncingRatingChips = false

    private fun buildRatingChips() {
        val group = binding.cgQueueRating
        group.removeAllViews()
        val options = listOf<Pair<RatingFilter, String>>(
            RatingFilter.Unrated to getString(R.string.queue_filter_unrated),
        ) + QueueStore.Reaction.entries.map { RatingFilter.Only(it) to "${it.emoji} ${getString(it.labelRes)}" }
        options.forEach { (filter, label) ->
            group.addView(
                Chip(ContextThemeWrapper(requireContext(), R.style.Widget_StreamFlix_Queue_FilterChip), null, 0).apply {
                    id = View.generateViewId()
                    text = label
                    tag = filter
                    isCheckable = true
                },
            )
        }
    }

    private fun syncRatingChips(selected: RatingFilter) {
        syncingRatingChips = true
        val group = binding.cgQueueRating
        val match = (0 until group.childCount).map { group.getChildAt(it) }.firstOrNull { it.tag == selected }
        if (match != null) group.check(match.id) else group.clearCheck()
        syncingRatingChips = false
    }

    private fun render(state: QueueViewModel.UiState) {
        val lists = state.lists
        binding.btnQueueUpNext.text = sectionLabel(R.string.queue_section_up_next, lists.upNext.size)
        binding.btnQueueWatchlist.text = sectionLabel(R.string.queue_section_watchlist, lists.watchlist.size)
        binding.btnQueueWatched.text = sectionLabel(R.string.queue_section_watched, lists.watched.size)
        val checked = when (state.section) {
            Section.UP_NEXT -> R.id.btn_queue_up_next
            Section.WATCHLIST -> R.id.btn_queue_watchlist
            Section.WATCHED -> R.id.btn_queue_watched
        }
        if (binding.tgQueueSection.checkedButtonId != checked) binding.tgQueueSection.check(checked)
        binding.btnQueueSort.isVisible = state.section != Section.UP_NEXT
        binding.llQueueRating.isVisible = state.section == Section.WATCHED
        syncRatingChips(state.rating)

        adapter.submitList(state.visible)
        val empty = lists.loaded && state.visible.isEmpty()
        binding.queueEmpty.root.isVisible = empty
        binding.rvQueue.isVisible = !empty
        if (empty) {
            val (title, message) = when (state.section) {
                Section.UP_NEXT -> R.string.queue_empty_up_next_title to R.string.queue_empty_up_next_message
                Section.WATCHLIST -> R.string.queue_empty_watchlist_title to R.string.queue_empty_watchlist_message
                Section.WATCHED -> R.string.queue_empty_watched_title to R.string.queue_empty_watched_message
            }
            binding.queueEmpty.tvEmptyTitle.setText(title)
            binding.queueEmpty.tvEmptyMessage.setText(message)
            binding.queueEmpty.ivEmptyIcon.setImageResource(R.drawable.ic_menu_queue)
        }
    }

    private fun sectionLabel(res: Int, count: Int): String =
        if (count > 0) getString(R.string.queue_section_count, getString(res), count) else getString(res)

    private fun showSortDialog() {
        val sorts = Sort.entries
        val labels = arrayOf(
            getString(R.string.queue_sort_recent),
            getString(R.string.queue_sort_title),
            getString(R.string.queue_sort_release),
            getString(R.string.queue_sort_rating),
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.queue_sort)
            .setSingleChoiceItems(labels, sorts.indexOf(viewModel.state.value.sort)) { dialog, which ->
                viewModel.setSort(sorts[which])
                dialog.dismiss()
            }
            .setNegativeButton(R.string.option_cancel, null)
            .show()
    }

    override fun onDestroyView() {
        binding.rvQueue.adapter = null
        _binding = null
        super.onDestroyView()
    }
}

internal fun List<QueueEntry>.filterFor(filter: Filter): List<QueueEntry> = filter {
    when (filter) {
        Filter.ALL -> true
        Filter.MOVIES -> !it.isTv
        Filter.SHOWS -> it.isTv
    }
}
