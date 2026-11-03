package com.streamflixreborn.streamflix.fragments.home

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.databinding.FragmentHomeTvBinding
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.utils.viewModelsFactory
import kotlinx.coroutines.Runnable
import com.streamflixreborn.streamflix.utils.CacheUtils
import kotlinx.coroutines.launch
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.streamflixreborn.streamflix.utils.LoggingUtils
import com.streamflixreborn.streamflix.utils.UserPreferences
import com.streamflixreborn.streamflix.utils.ProviderChangeNotifier
import com.streamflixreborn.streamflix.utils.format
import com.streamflixreborn.streamflix.ui.browse.BrowseCards
import com.streamflixreborn.streamflix.ui.motion.Spring
import com.streamflixreborn.streamflix.ui.motion.springAlpha
import androidx.leanback.widget.BaseGridView

class HomeTvFragment : Fragment() {

    private var hasAutoCleared409: Boolean = false

    private var _binding: FragmentHomeTvBinding? = null
    private val binding get() = _binding!!

    private val viewModel: HomeViewModel by lazy {
        val providerKey = UserPreferences.currentProvider?.name ?: "default"
        val factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return HomeViewModel() as T
            }
        }
        ViewModelProvider(this, factory).get(providerKey, HomeViewModel::class.java)
    }

    private val appAdapter = AppAdapter()
    private val rowViewPool = RecyclerView.RecycledViewPool().apply {
        for (type in listOf(
            AppAdapter.Type.MOVIE_TV_ITEM,
            AppAdapter.Type.TV_SHOW_TV_ITEM,
            AppAdapter.Type.EPISODE_TV_ITEM,
            AppAdapter.Type.MOVIE_RECENTLY_WATCHED_TV_ITEM,
            AppAdapter.Type.TV_SHOW_RECENTLY_WATCHED_TV_ITEM,
            AppAdapter.Type.EPISODE_RECENTLY_WATCHED_TV_ITEM,
        )) setMaxRecycledViews(type.ordinal, 20)
    }

    private val swiperHandler = Handler(Looper.getMainLooper())
    private var isBackgroundPinned = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeTvBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initializeHome()

        // Lightweight refresh when provider changes
        viewLifecycleOwner.lifecycleScope.launch {
            ProviderChangeNotifier.providerChangeFlow.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect {
                viewModel.getHome()
            }
        }

        // Initial load
        viewModel.getHome()

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect { state ->
                when (state) {
                    HomeViewModel.State.Loading -> binding.isLoading.apply {
                        root.visibility = View.VISIBLE
                        pbIsLoading.visibility = View.VISIBLE
                        gIsLoadingRetry.visibility = View.GONE
                    }
                    is HomeViewModel.State.SuccessLoading -> {
                        displayHome(state.categories)
                        binding.vgvHome.visibility = View.VISIBLE
                        binding.isLoading.root.visibility = View.GONE
                    }
                    is HomeViewModel.State.FailedLoading -> {
                        val code = (state.error as? retrofit2.HttpException)?.code()
                        if (code == 409 && !hasAutoCleared409) {
                            hasAutoCleared409 = true
                            CacheUtils.clearAppCache(requireContext())
                            android.widget.Toast.makeText(requireContext(), getString(com.streamflixreborn.streamflix.R.string.clear_cache_done_409), android.widget.Toast.LENGTH_SHORT).show()
                            viewModel.getHome()
                            return@collect
                        }
                        Toast.makeText(
                            requireContext(),
                            state.error.message ?: "",
                            Toast.LENGTH_SHORT
                        ).show()
                        binding.isLoading.apply {
                            pbIsLoading.visibility = View.GONE
                            gIsLoadingRetry.visibility = View.VISIBLE
                            btnIsLoadingRetry.setOnClickListener { viewModel.getHome() }
                            btnIsLoadingClearCache.setOnClickListener {
                                CacheUtils.clearAppCache(requireContext())
                                android.widget.Toast.makeText(requireContext(), getString(com.streamflixreborn.streamflix.R.string.clear_cache_done), android.widget.Toast.LENGTH_SHORT).show()
                                viewModel.getHome()
                            }
                            btnIsLoadingErrorDetails.setOnClickListener {
                                LoggingUtils.showErrorDialog(requireContext(), state.error)
                            }
                            binding.vgvHome.visibility = View.GONE
                        }
                    }
                }
            }
        }
    }
    
    override fun onStart() {
        super.onStart()
        appAdapter.items
            .filterIsInstance<Category>()
            .firstOrNull { it.name == Category.FEATURED }
            ?.let {
                resetSwiperSchedule()
            }
    }

    override fun onStop() {
        super.onStop()
        swiperHandler.removeCallbacksAndMessages(null)
        heroHandler.removeCallbacks(renderHeroRunnable)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        appAdapter.onSaveInstanceState(binding.vgvHome)
        rowViewPool.clear()
        heroHandler.removeCallbacks(renderHeroRunnable)
        heroItem = null
        heroBackdrop = null
        pinnedItem = null
        isBackgroundPinned = false
        _binding = null
    }


    /** The featured item (swiper row); shown in the hero while no row item is focused. */
    private var featuredItem: Any? = null
    /** The row item that currently has focus; pins the hero to it. */
    private var pinnedItem: Any? = null
    private var heroItem: Any? = null
    private var heroBackdrop: String? = null
    private val heroHandler = Handler(Looper.getMainLooper())
    private val renderHeroRunnable = Runnable { renderHero(pinnedItem ?: featuredItem) }

    /** Called by the featured row whenever its selected item changes. */
    fun setFeatured(item: Any) {
        featuredItem = item
        if (pinnedItem == null) scheduleHero(immediate = heroItem == null)
    }

    /** A row card (movie, show, episode) gained focus: the hero follows it. */
    fun focusHero(item: Any) {
        isBackgroundPinned = true
        pinnedItem = item
        scheduleHero()
    }

    /** Kept for callers that only know the backdrop URL. */
    fun pinBackground(uri: String?) {
        isBackgroundPinned = true
        loadHeroBackdrop(uri)
    }

    /** Kept for compatibility: the featured hero now comes from [setFeatured]. */
    fun updateBackground(uri: String?, swiperHasFocus: Boolean? = false) {
        if (pinnedItem != null) return
        loadHeroBackdrop(uri)
    }

    fun releasePinnedBackground() {
        if (!isBackgroundPinned) return
        isBackgroundPinned = false
        pinnedItem = null
        // Focus usually lands on a neighbour within a frame; the debounce keeps the hero still.
        scheduleHero()
    }

    private fun scheduleHero(immediate: Boolean = false) {
        heroHandler.removeCallbacks(renderHeroRunnable)
        if (immediate) renderHeroRunnable.run() else heroHandler.postDelayed(renderHeroRunnable, HERO_DEBOUNCE_MS)
    }

    private fun renderHero(item: Any?) {
        val binding = _binding ?: return
        if (item == null || item === heroItem) return
        heroItem = item

        val context = binding.root.context
        val title: String?
        val meta: String
        val overview: String?
        val backdrop: String?
        when (item) {
            is Movie -> {
                title = item.title
                meta = BrowseCards.joinMeta(
                    context,
                    item.released?.format("yyyy"),
                    BrowseCards.ratingValue(item.rating)?.let { getString(R.string.browse_rating_star, it) },
                    item.quality,
                )
                overview = item.overview
                backdrop = item.banner ?: item.poster
            }
            is TvShow -> {
                title = item.title
                meta = BrowseCards.joinMeta(
                    context,
                    item.released?.format("yyyy"),
                    BrowseCards.ratingValue(item.rating)?.let { getString(R.string.browse_rating_star, it) },
                    item.quality,
                )
                overview = item.overview
                backdrop = item.banner ?: item.poster
            }
            is Episode -> {
                title = item.tvShow?.title ?: item.title
                val episodeLabel = item.season?.takeIf { it.number != 0 }?.let { season ->
                    getString(R.string.tv_show_item_season_number_episode_number, season.number, item.number)
                } ?: getString(R.string.tv_show_item_episode_number, item.number)
                meta = BrowseCards.joinMeta(
                    context,
                    episodeLabel,
                    item.title,
                    BrowseCards.remainingText(context, item.watchHistory),
                )
                overview = item.overview?.takeIf { it.isNotBlank() } ?: item.tvShow?.overview
                backdrop = item.tvShow?.banner ?: item.poster
            }
            else -> return
        }

        binding.tvHomeHeroTitle.text = title
        binding.tvHomeHeroMeta.text = meta
        binding.tvHomeHeroOverview.text = overview
        for (view in arrayOf(binding.tvHomeHeroTitle, binding.tvHomeHeroMeta, binding.tvHomeHeroOverview)) {
            view.alpha = 0f
            view.springAlpha(1f, Spring.EffectsSlow)
        }
        loadHeroBackdrop(backdrop)
    }

    private fun loadHeroBackdrop(uri: String?) {
        val binding = _binding ?: return
        if (uri == heroBackdrop) return
        heroBackdrop = uri
        Glide.with(binding.ivHomeBackground)
            .load(uri)
            .transition(DrawableTransitionOptions.withCrossFade(HERO_CROSSFADE_MS))
            .into(binding.ivHomeBackground)
    }

    private fun initializeHome() {
        binding.vgvHome.apply {
            adapter = appAdapter.apply {
                stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
                childRecycledViewPool = rowViewPool
            }
            setItemSpacing(resources.getDimensionPixelSize(R.dimen.browse_tv_row_item_gap))
            // Keep the focused row right under the hero instead of centred in the short grid.
            windowAlignment = BaseGridView.WINDOW_ALIGN_NO_EDGE
            windowAlignmentOffset = paddingTop
            windowAlignmentOffsetPercent = 0f
            itemAlignmentOffsetPercent = 0f
        }

        binding.root.requestFocus()
    }

    private fun displayHome(categories: List<Category>) {
        categories
            .find { it.name == Category.FEATURED }
            ?.also {
                val index = appAdapter.items
                    .filterIsInstance<Category>()
                    .find { item -> item.name == Category.FEATURED }
                    ?.selectedIndex
                    ?: 0
                it.selectedIndex = index
                
                // Show the featured item in the hero right away, without waiting for focus.
                it.list.getOrNull(index)?.let { firstItem -> setFeatured(firstItem) }
                
                resetSwiperSchedule()
            }

        categories
            .find { it.name == Category.CONTINUE_WATCHING }
            ?.also {
                it.name = getString(R.string.home_continue_watching)
                it.list.forEach { show ->
                    when (show) {
                        is Episode -> show.itemType = AppAdapter.Type.EPISODE_CONTINUE_WATCHING_TV_ITEM
                        is Movie -> show.itemType = AppAdapter.Type.MOVIE_CONTINUE_WATCHING_TV_ITEM
                    }
                }
            }

        categories
            .find { it.name == Category.RECENTLY_WATCHED }
            ?.also {
                it.name = getString(R.string.home_recently_watched)
            }

        categories
            .find { it.name == Category.FAVORITE_MOVIES }
            ?.also { it.name = getString(R.string.home_favorite_movies) }

        categories
            .find { it.name == Category.FAVORITE_TV_SHOWS }
            ?.also { it.name = getString(R.string.home_favorite_tv_shows) }

        appAdapter.submitList(
            categories
                .filter { it.list.isNotEmpty() }
                .onEach { category ->
                    if (category.name != getString(R.string.home_continue_watching)) {
                        val isRecentlyWatched = category.name == getString(R.string.home_recently_watched)
                        category.list.forEach { show ->
                            when (show) {
                                is Episode -> show.itemType = if (isRecentlyWatched) AppAdapter.Type.EPISODE_RECENTLY_WATCHED_TV_ITEM else AppAdapter.Type.EPISODE_TV_ITEM
                                is Movie -> show.itemType = if (isRecentlyWatched) AppAdapter.Type.MOVIE_RECENTLY_WATCHED_TV_ITEM else AppAdapter.Type.MOVIE_TV_ITEM
                                is TvShow -> show.itemType = if (isRecentlyWatched) AppAdapter.Type.TV_SHOW_RECENTLY_WATCHED_TV_ITEM else AppAdapter.Type.TV_SHOW_TV_ITEM
                            }
                        }
                    }
                    category.itemSpacing = resources.getDimensionPixelSize(R.dimen.browse_tv_row_item_gap)
                    category.itemType = when (category.name) {
                        Category.FEATURED -> AppAdapter.Type.CATEGORY_TV_SWIPER
                        else -> AppAdapter.Type.CATEGORY_TV_ITEM
                    }
                }
        )
    }

    fun resetSwiperSchedule() {
        swiperHandler.removeCallbacksAndMessages(null)
        swiperHandler.postDelayed(object : Runnable {
            override fun run() {
                if (isBackgroundPinned) {
                    swiperHandler.postDelayed(this, 8_000)
                    return
                }

                val position = appAdapter.items
                    .filterIsInstance<Category>()
                    .find { it.name == Category.FEATURED }
                    ?.let { category ->
                        category.selectedIndex = (category.selectedIndex + 1) % category.list.size
                        
                        // The rebind below calls setFeatured() with the new item.

                        appAdapter.items.indexOf(category)
                    }
                    ?.takeIf { it != -1 }

                if (position == null) {
                    swiperHandler.removeCallbacksAndMessages(null)
                    return
                }

                appAdapter.notifyItemChanged(position)
                swiperHandler.postDelayed(this, 8_000)
            }
        }, 8_000)
    }

    private companion object {
        const val HERO_DEBOUNCE_MS = 180L
        const val HERO_CROSSFADE_MS = 300
    }
}
