package com.streamflixreborn.streamflix.fragments.queue

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.google.android.material.color.MaterialColors
import com.google.android.material.tabs.TabLayout
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.database.AppDatabase
import com.streamflixreborn.streamflix.databinding.FragmentQueueMobileBinding
import com.streamflixreborn.streamflix.databinding.SheetQueueTuneBinding
import com.streamflixreborn.streamflix.fragments.queue.QueueViewModel.Filter
import com.streamflixreborn.streamflix.fragments.queue.QueueViewModel.Section
import com.streamflixreborn.streamflix.ui.SpacingItemDecoration
import com.streamflixreborn.streamflix.ui.browse.BrowseCards
import com.streamflixreborn.streamflix.utils.viewModelsFactory
import kotlinx.coroutines.launch

class QueueMobileFragment : Fragment() {

    private var _binding: FragmentQueueMobileBinding? = null
    private val binding get() = _binding!!
    private val viewModel by viewModelsFactory { QueueViewModel(AppDatabase.getInstance(requireContext())) }
    private lateinit var adapter: QueueAdapter

    private val tabSections = listOf(Section.UP_NEXT, Section.WATCHLIST, Section.WATCHED)
    private var syncingTabs = false
    private var syncingRatingChips = false

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
        binding.rvQueue.addItemDecoration(SpacingItemDecoration(resources.getDimensionPixelSize(R.dimen.queue_grid_gap)))

        binding.llQueueFloating.background = GradientDrawable(
            GradientDrawable.Orientation.BOTTOM_TOP,
            MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorSurface).let {
                intArrayOf(ColorUtils.setAlphaComponent(it, 0xEE), ColorUtils.setAlphaComponent(it, 0x00))
            },
        )

        buildTabs()
        binding.tabsQueue.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                if (syncingTabs) return
                viewModel.setSection(tabSections[tab.position])
                binding.rvQueue.scrollToPosition(0)
            }

            override fun onTabUnselected(tab: TabLayout.Tab) = Unit

            override fun onTabReselected(tab: TabLayout.Tab) {
                binding.rvQueue.smoothScrollToPosition(0)
            }
        })
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
        binding.btnQueueTune.setOnClickListener { showTuneSheet() }
        binding.btnQueueSpin.setOnClickListener {
            QueueSpinDialog.show(this, viewModel.state.value.lists.watchlist.filterFor(viewModel.state.value.filter), isTv = false)
        }
        binding.btnQueueSwipe.setOnClickListener { findNavController().navigate(R.id.queue_swipe) }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state.flowWithLifecycle(viewLifecycleOwner.lifecycle, Lifecycle.State.STARTED).collect(::render)
        }
    }

    private fun buildTabs() {
        val tabs = binding.tabsQueue
        tabs.removeAllTabs()
        val icons = listOf(R.drawable.ic_queue_tab_progress, R.drawable.ic_queue_tab_watchlist, R.drawable.ic_queue_tab_watched)
        val labels = listOf(R.string.queue_section_up_next, R.string.queue_section_watchlist, R.string.queue_section_watched)
        tabSections.indices.forEach { i ->
            tabs.addTab(tabs.newTab().setIcon(icons[i]).setText(labels[i]), false)
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

    /** All, the five ratings as emoji, then the titles with no rating yet. */
    private fun buildRatingChips() {
        val group = binding.cgQueueRating
        group.removeAllViews()
        val options = buildList<Triple<RatingFilter, String, String>> {
            add(Triple(RatingFilter.Everything, getString(R.string.queue_filter_all), getString(R.string.queue_filter_all)))
            QueueStore.Reaction.entries.forEach {
                add(Triple(RatingFilter.Only(it), it.emoji, getString(it.labelRes)))
            }
            add(Triple(RatingFilter.Unrated, getString(R.string.queue_filter_unrated), getString(R.string.queue_filter_unrated)))
        }
        options.forEach { (filter, label, description) ->
            group.addView(
                Chip(ContextThemeWrapper(requireContext(), R.style.Widget_StreamFlix_Queue_FloatChip), null, 0).apply {
                    id = View.generateViewId()
                    text = label
                    contentDescription = description
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
        if (match != null) group.check(match.id)
        syncingRatingChips = false
    }

    private fun render(state: QueueViewModel.UiState) {
        val lists = state.lists
        val counts = listOf(lists.upNext.size, lists.watchlist.size, lists.watched.size)
        val labels = listOf(R.string.queue_section_up_next, R.string.queue_section_watchlist, R.string.queue_section_watched)
        syncingTabs = true
        tabSections.indices.forEach { i ->
            binding.tabsQueue.getTabAt(i)?.text = tabLabel(labels[i], counts[i])
        }
        val selected = tabSections.indexOf(state.section)
        if (binding.tabsQueue.selectedTabPosition != selected) binding.tabsQueue.selectTab(binding.tabsQueue.getTabAt(selected))
        syncingTabs = false

        val watched = state.section == Section.WATCHED
        val kindChip = when (state.filter) {
            Filter.ALL -> R.id.chip_queue_all
            Filter.MOVIES -> R.id.chip_queue_movies
            Filter.SHOWS -> R.id.chip_queue_shows
        }
        if (binding.cgQueueFilter.checkedChipId != kindChip) binding.cgQueueFilter.check(kindChip)
        binding.cgQueueFilter.isVisible = !watched
        binding.cgQueueRating.isVisible = watched
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

    private fun tabLabel(res: Int, count: Int): String =
        if (count > 0) getString(R.string.queue_section_count, getString(res), count) else getString(res)

    /** Sort, which kind of title (in Watched), and the rarely used tools. */
    private fun showTuneSheet() {
        val sheet = SheetQueueTuneBinding.inflate(LayoutInflater.from(requireContext()))
        val dialog = BottomSheetDialog(requireContext())
        dialog.setContentView(sheet.root)

        val state = viewModel.state.value
        val sortable = state.section != Section.UP_NEXT
        sheet.tvQueueTuneSortLabel.isVisible = sortable
        sheet.cgQueueTuneSort.isVisible = sortable
        val sorts = listOf(
            QueueSort.RECENT to R.string.queue_sort_recent,
            QueueSort.NEWEST to R.string.queue_sort_newest,
            QueueSort.OLDEST to R.string.queue_sort_oldest,
            QueueSort.RATING to R.string.queue_sort_rating,
            QueueSort.TITLE to R.string.queue_sort_title,
        )
        sorts.forEach { (sort, label) ->
            sheet.cgQueueTuneSort.addView(
                Chip(ContextThemeWrapper(requireContext(), R.style.Widget_StreamFlix_Chip), null, 0).apply {
                    id = View.generateViewId()
                    setText(label)
                    isCheckable = true
                    isChecked = state.sort == sort
                    setOnClickListener {
                        viewModel.setSort(sort)
                        binding.rvQueue.scrollToPosition(0)
                        dialog.dismiss()
                    }
                },
            )
        }

        val showKind = state.section == Section.WATCHED
        sheet.tvQueueTuneKindLabel.isVisible = showKind
        sheet.cgQueueTuneKind.isVisible = showKind
        sheet.cgQueueTuneKind.check(
            when (state.filter) {
                Filter.ALL -> R.id.chip_queue_tune_all
                Filter.MOVIES -> R.id.chip_queue_tune_movies
                Filter.SHOWS -> R.id.chip_queue_tune_shows
            },
        )
        sheet.cgQueueTuneKind.setOnCheckedStateChangeListener { _, ids ->
            viewModel.setFilter(
                when (ids.firstOrNull()) {
                    R.id.chip_queue_tune_movies -> Filter.MOVIES
                    R.id.chip_queue_tune_shows -> Filter.SHOWS
                    else -> Filter.ALL
                },
            )
            // Keep the (hidden in Watched) floating kind chips in step.
            binding.cgQueueFilter.check(
                when (ids.firstOrNull()) {
                    R.id.chip_queue_tune_movies -> R.id.chip_queue_movies
                    R.id.chip_queue_tune_shows -> R.id.chip_queue_shows
                    else -> R.id.chip_queue_all
                },
            )
        }

        sheet.btnQueueTuneCalendar.setOnClickListener {
            dialog.dismiss()
            QueueCalendarDialog.show(this, viewModel)
        }
        sheet.btnQueueTuneImport.setOnClickListener {
            dialog.dismiss()
            QueueImportDialog.show(this, viewModel)
        }
        dialog.show()
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
