package com.streamflixreborn.streamflix.adapters.viewholders

import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.os.postDelayed
import androidx.core.view.children
import androidx.navigation.findNavController
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.carousel.CarouselLayoutManager
import com.google.android.material.carousel.CarouselSnapHelper
import com.google.android.material.carousel.HeroCarouselStrategy
import com.streamflixreborn.streamflix.ui.browse.BrowseCards
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.databinding.ContentCategorySwiperMobileBinding
import com.streamflixreborn.streamflix.databinding.ContentCategorySwiperTvBinding
import com.streamflixreborn.streamflix.databinding.ItemCategoryMobileBinding
import com.streamflixreborn.streamflix.databinding.ItemCategoryTvBinding
import com.streamflixreborn.streamflix.fragments.home.HomeMobileFragment
import com.streamflixreborn.streamflix.fragments.home.HomeTvFragment
import com.streamflixreborn.streamflix.fragments.home.HomeTvFragmentDirections
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.Show
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.ui.SpacingItemDecoration
import com.streamflixreborn.streamflix.utils.format
import com.streamflixreborn.streamflix.utils.getCurrentFragment
import com.streamflixreborn.streamflix.utils.toActivity
import java.util.Locale
import com.streamflixreborn.streamflix.utils.UserPreferences
import com.streamflixreborn.streamflix.providers.Provider
import com.streamflixreborn.streamflix.database.AppDatabase

class CategoryViewHolder(
    private val _binding: ViewBinding
) : RecyclerView.ViewHolder(
    _binding.root
) {

    private val context = itemView.context
    private lateinit var category: Category

    val childRecyclerView: RecyclerView?
        get() = when (_binding) {
            is ItemCategoryMobileBinding -> _binding.rvCategory
            is ItemCategoryTvBinding -> _binding.hgvCategory
            is ContentCategorySwiperMobileBinding -> _binding.rvCategorySwiper
            else -> null
        }

    /** Phone hero auto-advance (every 8 s, paused while the user drags). */
    private val swiperHandler = Handler(Looper.getMainLooper())
    private var swiperAdvance: Runnable? = null

    /** Shares one view pool across all nested rows (see AppAdapter.childRecycledViewPool). */
    fun useSharedRowPool(pool: RecyclerView.RecycledViewPool) {
        when (_binding) {
            is ItemCategoryMobileBinding -> _binding.rvCategory.setRecycledViewPool(pool)
            is ItemCategoryTvBinding -> _binding.hgvCategory.setRecycledViewPool(pool)
        }
    }

    fun bind(
        category: Category,
        onMovieClick: ((Movie) -> Unit)? = null,
        onTvShowClick: ((TvShow) -> Unit)? = null,
        onMovieLongClick: ((Movie) -> Unit)? = null,
        onTvShowLongClick: ((TvShow) -> Unit)? = null,
    ) {
        this.category = category

        when (_binding) {
            is ItemCategoryMobileBinding -> displayMobileItem(_binding, onMovieClick, onTvShowClick, onMovieLongClick, onTvShowLongClick)
            is ItemCategoryTvBinding -> displayTvItem(_binding, onMovieClick, onTvShowClick, onMovieLongClick, onTvShowLongClick)
            is ContentCategorySwiperMobileBinding -> displayMobileSwiper(_binding, onMovieClick, onTvShowClick, onMovieLongClick, onTvShowLongClick)
            is ContentCategorySwiperTvBinding -> displayTvSwiper(_binding)
        }
    }

    private fun displayMobileItem(
        binding: ItemCategoryMobileBinding,
        onMovieClick: ((Movie) -> Unit)?,
        onTvShowClick: ((TvShow) -> Unit)?,
        onMovieLongClick: ((Movie) -> Unit)?,
        onTvShowLongClick: ((TvShow) -> Unit)?,
    ) {
        binding.tvCategoryTitle.text = category.name

        binding.rvCategory.apply {
            (layoutManager as? LinearLayoutManager)?.initialPrefetchItemCount = 4
            val categoryAdapter = (adapter as? AppAdapter) ?: AppAdapter().also { adapter = it }
            categoryAdapter.apply {
                this.onMovieClickListener = onMovieClick
                this.onTvShowClickListener = onTvShowClick
                this.onMovieLongClickListener = onMovieLongClick
                this.onTvShowLongClickListener = onTvShowLongClick
                submitList(category.list)
            }
            if (itemDecorationCount == 0) {
                addItemDecoration(SpacingItemDecoration(category.itemSpacing))
            }
        }
    }

    private fun displayTvItem(
        binding: ItemCategoryTvBinding,
        onMovieClick: ((Movie) -> Unit)?,
        onTvShowClick: ((TvShow) -> Unit)?,
        onMovieLongClick: ((Movie) -> Unit)?,
        onTvShowLongClick: ((TvShow) -> Unit)?,
    ) {
        binding.tvCategoryTitle.text = category.name
        binding.hgvCategory.apply {
            setRowHeight(ViewGroup.LayoutParams.WRAP_CONTENT)
            setInitialPrefetchItemCount(4)

            val categoryAdapter = (adapter as? AppAdapter) ?: AppAdapter().also { adapter = it }
            categoryAdapter.apply {
                this.onMovieClickListener = onMovieClick
                this.onTvShowClickListener = onTvShowClick
                this.onMovieLongClickListener = onMovieLongClick
                this.onTvShowLongClickListener = onTvShowLongClick
                submitList(category.list)
            }
            setItemSpacing(category.itemSpacing)

            isFocusable = true
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
    }


    private fun displayMobileSwiper(
        binding: ContentCategorySwiperMobileBinding,
        onMovieClick: ((Movie) -> Unit)?,
        onTvShowClick: ((TvShow) -> Unit)?,
        onMovieLongClick: ((Movie) -> Unit)?,
        onTvShowLongClick: ((TvShow) -> Unit)?,
    ) {
        binding.tvCategoryTitle.text = category.name
        val carousel = binding.rvCategorySwiper

        if (carousel.layoutManager !is CarouselLayoutManager) {
            carousel.layoutManager = CarouselLayoutManager(HeroCarouselStrategy())
            CarouselSnapHelper().attachToRecyclerView(carousel)
            carousel.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                        scheduleSwiperAdvance(carousel)
                    } else {
                        swiperHandler.removeCallbacksAndMessages(null)
                    }
                }
            })
            carousel.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) = scheduleSwiperAdvance(carousel)
                override fun onViewDetachedFromWindow(v: View) {
                    swiperHandler.removeCallbacksAndMessages(null)
                }
            })
        }

        val swiperAdapter = (carousel.adapter as? AppAdapter) ?: AppAdapter().also { carousel.adapter = it }
        swiperAdapter.apply {
            this.onMovieClickListener = onMovieClick
            this.onTvShowClickListener = onTvShowClick
            this.onMovieLongClickListener = onMovieLongClick
            this.onTvShowLongClickListener = onTvShowLongClick
            submitList(category.list)
        }
        scheduleSwiperAdvance(carousel)
    }

    private fun scheduleSwiperAdvance(carousel: RecyclerView) {
        swiperHandler.removeCallbacksAndMessages(null)
        val advance = swiperAdvance ?: Runnable {
            val count = carousel.adapter?.itemCount ?: 0
            if (count > 1 && carousel.isAttachedToWindow) {
                // The large (snapped) item is the child closest to the start padding.
                var current = 0
                var bestDistance = Int.MAX_VALUE
                for (index in 0 until carousel.childCount) {
                    val child = carousel.getChildAt(index)
                    val distance = kotlin.math.abs(child.left - carousel.paddingLeft)
                    if (distance < bestDistance) {
                        bestDistance = distance
                        current = carousel.getChildAdapterPosition(child)
                    }
                }
                carousel.smoothScrollToPosition((current.coerceAtLeast(0) + 1) % count)
            }
        }.also { swiperAdvance = it }
        swiperHandler.postDelayed(advance, 8_000)
    }

    private fun displayTvSwiper(binding: ContentCategorySwiperTvBinding) {
        binding.tvCategoryTitle.text = category.name
        val selected = category.list.getOrNull(category.selectedIndex) as? Show ?: return

        fun checkProviderAndRun(show: Show, action: () -> Unit) {
            val providerName = when (show) {
                is Movie -> show.providerName
                is TvShow -> show.providerName
            }

            if (!providerName.isNullOrBlank() && providerName != UserPreferences.currentProvider?.name) {
                Provider.providers.keys.find { it.name == providerName }?.let {
                    UserPreferences.currentProvider = it
                }
            }
            action()
        }

        // The featured item's backdrop, title and synopsis live in the fragment's hero.
        (context.toActivity()?.getCurrentFragment() as? HomeTvFragment)?.let { fragment ->
            fragment.setFeatured(selected)
            if (category.selectedIndex == category.list.indexOf(selected)) {
                fragment.resetSwiperSchedule()
            }
        }

        binding.btnSwiperWatchNow.apply {
            setOnClickListener {
                checkProviderAndRun(selected) {
                    findNavController().navigate(
                        when (selected) {
                            is Movie -> HomeTvFragmentDirections.actionHomeToMovie(selected.id)
                            is TvShow -> HomeTvFragmentDirections.actionHomeToTvShow(
                                id = selected.id,
                                poster = selected.poster,
                                banner = selected.banner,
                            )
                        }
                    )
                }
            }
            setOnKeyListener { _, _, event ->
                if (event.action == KeyEvent.ACTION_DOWN) {
                    when (event.keyCode) {
                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            when (val fragment = context.toActivity()?.getCurrentFragment()) {
                                is HomeTvFragment -> fragment.resetSwiperSchedule()
                            }
                            category.selectedIndex = (category.selectedIndex + 1) % category.list.size
                            bindingAdapter?.notifyItemChanged(bindingAdapterPosition)
                            return@setOnKeyListener true
                        }
                    }
                }
                false
            }
        }

        binding.pbSwiperProgress.apply {
            val watchHistory = when (selected) {
                is Movie -> selected.watchHistory
                is TvShow -> null
            }
            progress = BrowseCards.progressPercent(watchHistory)
            visibility = when {
                watchHistory != null -> View.VISIBLE
                else -> View.GONE
            }
        }

        binding.llDotsIndicator.apply {
            if (childCount != category.list.size) {
                removeAllViews()
                val size = (8 * resources.displayMetrics.density).toInt()
                val gap = (3 * resources.displayMetrics.density).toInt()
                repeat(category.list.size) {
                    addView(View(context).apply {
                        layoutParams = LinearLayout.LayoutParams(size, size).apply {
                            setMargins(gap, 0, gap, 0)
                        }
                        setBackgroundResource(R.drawable.bg_browse_dot_indicator)
                    })
                }
            }
            children.forEachIndexed { index, view -> view.isSelected = category.selectedIndex == index }
        }
    }
}
