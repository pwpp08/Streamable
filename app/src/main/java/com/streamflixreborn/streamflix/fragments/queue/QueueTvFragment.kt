package com.streamflixreborn.streamflix.fragments.queue

import android.os.Bundle
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
import androidx.recyclerview.widget.RecyclerView
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.database.AppDatabase
import com.streamflixreborn.streamflix.databinding.FragmentQueueTvBinding
import com.streamflixreborn.streamflix.ui.SpacingItemDecoration
import com.streamflixreborn.streamflix.utils.dp
import com.streamflixreborn.streamflix.utils.viewModelsFactory
import kotlinx.coroutines.launch

/**
 * Queue on TV: only the list kept in sync with the phone (what's in progress, then the
 * watchlist) and the Spin wheel. Everything else is managed on the phone.
 */
class QueueTvFragment : Fragment() {

    private var _binding: FragmentQueueTvBinding? = null
    private val binding get() = _binding!!
    private val viewModel by viewModelsFactory { QueueViewModel(AppDatabase.getInstance(requireContext())) }
    private lateinit var adapter: QueueAdapter
    private var columns = 6
    private var entries: List<QueueEntry> = emptyList()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentQueueTvBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        columns = maxOf(4, resources.configuration.screenWidthDp / 180)
        adapter = QueueAdapter(
            isTv = true,
            // Episode and "min left" labels for the in-progress titles; year and rating otherwise.
            section = { QueueViewModel.Section.UP_NEXT },
            onClick = ::onEntryClick,
            onLongClick = { QueueOptions.show(this, it, viewModel, isTv = true) },
        )
        binding.rvQueue.layoutManager = object : GridLayoutManager(requireContext(), columns) {
            // Up from the first row goes to Spin.
            override fun onInterceptFocusSearch(focused: View, direction: Int): View? {
                if (direction == View.FOCUS_UP) {
                    val position = binding.rvQueue.findContainingItemView(focused)
                        ?.let(binding.rvQueue::getChildAdapterPosition) ?: RecyclerView.NO_POSITION
                    if (position in 0 until columns) return binding.btnQueueSpin
                }
                return super.onInterceptFocusSearch(focused, direction)
            }
        }
        binding.rvQueue.adapter = adapter
        binding.rvQueue.addItemDecoration(SpacingItemDecoration(10.dp(requireContext())))
        binding.btnQueueSpin.setOnClickListener { QueueSpinDialog.show(this, entries, isTv = true) }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state.flowWithLifecycle(viewLifecycleOwner.lifecycle, Lifecycle.State.STARTED).collect(::render)
        }
        binding.btnQueueSpin.requestFocus()
    }

    private fun onEntryClick(entry: QueueEntry) {
        val inProgress = entry.progress != null || entry.episode != null
        if (inProgress && QueueNavigator.canPlay(entry)) {
            QueueNavigator.play(requireContext(), findNavController(), entry)
        } else {
            QueueNavigator.openDetails(findNavController(), entry)
        }
    }

    private fun render(state: QueueViewModel.UiState) {
        val lists = state.lists
        entries = lists.upNext + lists.watchlist
        binding.tvQueueStats.text = getString(R.string.queue_stats, lists.watchlist.size, lists.upNext.size, lists.watched.size)
        binding.btnQueueSpin.isEnabled = entries.size >= 2
        adapter.submitList(entries)
        val empty = lists.loaded && entries.isEmpty()
        binding.queueEmpty.root.isVisible = empty
        binding.rvQueue.isVisible = !empty
        if (empty) {
            binding.queueEmpty.tvEmptyTitle.setText(R.string.queue_empty_watchlist_title)
            binding.queueEmpty.tvEmptyMessage.setText(R.string.queue_empty_tv_message)
            binding.queueEmpty.ivEmptyIcon.setImageResource(R.drawable.ic_menu_queue)
        }
    }

    override fun onDestroyView() {
        binding.rvQueue.adapter = null
        _binding = null
        super.onDestroyView()
    }
}
