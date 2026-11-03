package com.streamflixreborn.streamflix.adapters.viewholders

import androidx.core.view.isVisible
import androidx.navigation.findNavController
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.databinding.ItemSeasonMobileBinding
import com.streamflixreborn.streamflix.databinding.ItemSeasonTvBinding
import com.streamflixreborn.streamflix.fragments.tv_show.TvShowMobileFragmentDirections
import com.streamflixreborn.streamflix.fragments.tv_show.TvShowTvFragmentDirections
import com.streamflixreborn.streamflix.models.Season
import com.streamflixreborn.streamflix.ui.motion.FocusScaleAnimator

class SeasonViewHolder(
    private val _binding: ViewBinding
) : RecyclerView.ViewHolder(
    _binding.root
) {

    private val context = itemView.context
    private lateinit var season: Season

    fun bind(season: Season) {
        this.season = season

        when (_binding) {
            is ItemSeasonMobileBinding -> displayMobileItem(_binding)
            is ItemSeasonTvBinding -> displayTvItem(_binding)
        }
    }


    private fun displayMobileItem(binding: ItemSeasonMobileBinding) {
        binding.root.apply {
            setOnClickListener {
                findNavController().navigate(
                    TvShowMobileFragmentDirections.actionTvShowToSeason(
                        tvShowId = season.tvShow?.id ?: "",
                        tvShowTitle = season.tvShow?.title ?: "",
                        tvShowPoster = season.tvShow?.poster,
                        tvShowBanner = season.tvShow?.banner,
                        seasonId = season.id,
                        seasonNumber = season.number,
                        seasonTitle = season.displayTitle(),
                    )
                )
            }
        }

        binding.btnSeason.apply {
            text = season.displayTitle()
            // Fully watched seasons carry a check, like the poster ribbon did.
            icon = if (season.isFullyWatched()) {
                androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_ud_check)
            } else {
                null
            }
        }
    }

    private fun displayTvItem(binding: ItemSeasonTvBinding) {
        binding.root.apply {
            setOnClickListener {
                findNavController().navigate(
                    TvShowTvFragmentDirections.actionTvShowToSeason(
                        tvShowId = season.tvShow?.id ?: "",
                        tvShowTitle = season.tvShow?.title ?: "",
                        tvShowPoster = season.tvShow?.poster,
                        tvShowBanner = season.tvShow?.banner,
                        seasonId = season.id,
                        seasonNumber = season.number,
                        seasonTitle = season.displayTitle(),
                    )
                )
            }
            // Spring scale; the ring is the poster's focus foreground (fg_item_season_image_tv).
            FocusScaleAnimator.attach(this, liftPx = 0f, ringWidthPx = 0)
        }

        binding.ivSeasonPoster.apply {
            Glide.with(context)
                .load(season.poster)
                .error(R.drawable.glide_fallback_cover)
                .fallback(R.drawable.glide_fallback_cover)
                .centerCrop()
                .transition(DrawableTransitionOptions.withCrossFade())
                .into(this)
        }
        binding.ivSeasonWatchedRibbon.isVisible = season.isFullyWatched()

        binding.tvSeasonTitle.text = season.displayTitle()
    }

    private fun Season.displayTitle(): String {
        return title ?: context.getString(R.string.season_number, number)
    }

    private fun Season.isFullyWatched(): Boolean {
        return episodes.isNotEmpty() && episodes.all { it.isWatched }
    }
}
