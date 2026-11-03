package com.streamflixreborn.streamflix.fragments.settings.homerows

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.activities.main.MainTvActivity
import com.streamflixreborn.streamflix.databinding.FragmentHomeRowsBinding
import com.streamflixreborn.streamflix.fragments.home.HomeRowsConfig
import com.streamflixreborn.streamflix.fragments.home.HomeRowsStore
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.utils.HomeCacheStore
import com.streamflixreborn.streamflix.utils.UserPreferences
import com.streamflixreborn.streamflix.utils.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings > Home screen: order and visibility of the Home rows, shared by the phone and TV
 * navigation graphs. Provider rows come from the last cached Home of the current provider.
 */
class HomeRowsFragment : Fragment() {

    private var _binding: FragmentHomeRowsBinding? = null
    private val binding get() = _binding!!
    private val isTv get() = activity is MainTvActivity

    private var providerCategories: List<String> = emptyList()
    private lateinit var adapter: HomeRowsAdapter
    private lateinit var touchHelper: ItemTouchHelper

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeRowsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        activity?.title = getString(R.string.home_rows_title)
        val context = requireContext()

        if (isTv) {
            binding.homeRowsRoot.setPadding(48.dp(context), 27.dp(context), 48.dp(context), 27.dp(context))
        }
        binding.tvHomeRowsHint.setText(if (isTv) R.string.home_rows_hint_tv else R.string.home_rows_hint_mobile)

        val providerName = UserPreferences.currentProvider?.name.orEmpty()
        adapter = HomeRowsAdapter(
            isTv = isTv,
            titleOf = ::titleOf,
            subtitleOf = { key ->
                if (HomeRowsConfig.isProviderKey(key)) getString(R.string.home_rows_provider_row, providerName)
                else getString(R.string.home_rows_library_row)
            },
            onStartDrag = { holder -> touchHelper.startDrag(holder) },
            onChanged = HomeRowsStore::save,
        )

        touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
            override fun isLongPressDragEnabled() = !isTv

            override fun onMove(rv: RecyclerView, from: RecyclerView.ViewHolder, to: RecyclerView.ViewHolder): Boolean {
                adapter.move(from.bindingAdapterPosition, to.bindingAdapterPosition)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

            override fun clearView(rv: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(rv, viewHolder)
                HomeRowsStore.save(adapter.config)
            }
        })

        binding.rvHomeRows.layoutManager = LinearLayoutManager(context)
        binding.rvHomeRows.adapter = adapter
        // Keep the same holder on change so the focused TV button stays focused.
        (binding.rvHomeRows.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        if (!isTv) touchHelper.attachToRecyclerView(binding.rvHomeRows)

        binding.btnHomeRowsReset.setOnClickListener {
            HomeRowsStore.reset()
            adapter.submit(HomeRowsConfig.default(providerCategories))
            Toast.makeText(context, R.string.home_rows_reset_done, Toast.LENGTH_SHORT).show()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            providerCategories = withContext(Dispatchers.IO) {
                val provider = UserPreferences.currentProvider ?: return@withContext emptyList()
                HomeCacheStore.read(context.applicationContext, provider).orEmpty()
                    .map { it.name }
                    .filter { it != Category.FEATURED }
            }
            val b = _binding ?: return@launch
            b.tvHomeRowsNote.visibility = if (providerCategories.isEmpty()) View.VISIBLE else View.GONE
            val config = (HomeRowsStore.load() ?: HomeRowsConfig.default(providerCategories)).merge(providerCategories)
            adapter.submit(config)
            if (isTv) b.rvHomeRows.post { _binding?.rvHomeRows?.requestFocus() }
        }
    }

    private fun titleOf(key: String): String = when (key) {
        HomeRowsConfig.CONTINUE_WATCHING -> getString(R.string.home_continue_watching)
        HomeRowsConfig.RECENTLY_WATCHED -> getString(R.string.home_recently_watched)
        HomeRowsConfig.FAVORITE_MOVIES -> getString(R.string.home_favorite_movies)
        HomeRowsConfig.FAVORITE_TV_SHOWS -> getString(R.string.home_favorite_tv_shows)
        else -> HomeRowsConfig.providerCategoryName(key) ?: key
    }

    override fun onDestroyView() {
        binding.rvHomeRows.adapter = null
        _binding = null
        super.onDestroyView()
    }
}
