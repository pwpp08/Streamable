package com.streamflixreborn.streamflix.adapters.viewholders

import android.view.KeyEvent
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.ImageView
import android.widget.TextView
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.streamflixreborn.streamflix.ui.motion.FocusScaleAnimator
import androidx.navigation.findNavController
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.databinding.ItemEpisodeContinueWatchingMobileBinding
import com.streamflixreborn.streamflix.databinding.ItemEpisodeContinueWatchingTvBinding
import com.streamflixreborn.streamflix.databinding.ItemEpisodeMobileBinding
import com.streamflixreborn.streamflix.databinding.ItemEpisodeTvBinding
import com.streamflixreborn.streamflix.fragments.home.HomeMobileFragmentDirections
import com.streamflixreborn.streamflix.fragments.home.HomeTvFragment
import com.streamflixreborn.streamflix.fragments.home.HomeTvFragmentDirections
import com.streamflixreborn.streamflix.fragments.season.SeasonMobileFragmentDirections
import com.streamflixreborn.streamflix.fragments.season.SeasonTvFragmentDirections
import com.streamflixreborn.streamflix.fragments.tv_show.TvShowMobileFragmentDirections
import com.streamflixreborn.streamflix.fragments.tv_show.TvShowTvFragmentDirections
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.downloads.DownloadStarter
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.sync.SyncProviders
import com.streamflixreborn.streamflix.ui.ShowOptionsMobileDialog
import com.streamflixreborn.streamflix.ui.ShowOptionsTvDialog
import com.streamflixreborn.streamflix.utils.EpisodeManager
import com.streamflixreborn.streamflix.utils.UserPreferences
import com.streamflixreborn.streamflix.utils.format
import com.streamflixreborn.streamflix.utils.getCurrentFragment
import com.streamflixreborn.streamflix.utils.loadTvShowCardArtwork
import com.streamflixreborn.streamflix.utils.toActivity

class EpisodeViewHolder(
    private val _binding: ViewBinding
) : RecyclerView.ViewHolder(
    _binding.root
) {

    private val context = itemView.context
    private lateinit var episode: Episode

    fun bind(episode: Episode) {
        this.episode = episode

        when (_binding) {
            is ItemEpisodeMobileBinding -> displayMobileItem(_binding)
            is ItemEpisodeTvBinding -> displayTvItem(_binding)
            is ItemEpisodeContinueWatchingMobileBinding -> displayContinueWatchingMobileItem(_binding)
            is ItemEpisodeContinueWatchingTvBinding -> displayContinueWatchingTvItem(_binding)
        }
    }


    private fun displayMobileItem(binding: ItemEpisodeMobileBinding) {
        binding.root.apply {
            setOnClickListener {
                findNavController().navigate(
                    SeasonMobileFragmentDirections.actionSeasonToPlayer(
                        id = episode.id,
                        title = episode.tvShow?.title ?: "",
                        subtitle = episode.season?.takeIf { it.number != 0 }?.let { season ->
                            context.getString(
                                R.string.player_subtitle_tv_show,
                                season.number,
                                episode.number,
                                episode.title ?: context.getString(
                                    R.string.episode_number,
                                    episode.number
                                )
                            )
                        } ?: context.getString(
                            R.string.player_subtitle_tv_show_episode_only,
                            episode.number,
                            episode.title ?: context.getString(
                                R.string.episode_number,
                                episode.number
                            )
                        ),
                        videoType = Video.Type.Episode(
                            id = episode.id,
                            number = episode.number,
                            title = episode.title,
                            poster = episode.poster,
                            overview = episode.overview,
                            tvShow = Video.Type.Episode.TvShow(
                                id = episode.tvShow?.id ?: "",
                                title = episode.tvShow?.title ?: "",
                                poster = episode.tvShow?.poster,
                                banner = episode.tvShow?.banner,
                                releaseDate = episode.tvShow?.released?.format("yyyy-MM-dd"),
                                imdbId = episode.tvShow?.imdbId,
                            ),
                            season = Video.Type.Episode.Season(
                                number = episode.season?.number ?: 0,
                                title = episode.season?.title,
                            ),
                        ),
                    )
                )
            }
            setOnLongClickListener {
                ShowOptionsMobileDialog(context, episode)
                    .show()
                true
            }
        }

        binding.ivEpisodePoster.apply {
            clipToOutline = true
            Glide.with(context)
                .load(episode.poster)
                .error(R.drawable.glide_fallback_cover)
                .centerCrop()
                .transition(DrawableTransitionOptions.withCrossFade())
                .into(this)
        }
        bindEpisodeCard(
            binding.ivEpisodeWatchedRibbon,
            binding.pbEpisodeProgress,
            binding.tvEpisodeTitle,
            binding.tvEpisodeReleased,
            episode.released?.format("yyyy-MM-dd"),
        )
        binding.tvEpisodeOverview.text = episode.overview ?: ""

        // Offline download (phone only; the button hides itself on TV).
        binding.btnEpisodeDownload.bind(
            (episode.tvShow?.providerName ?: UserPreferences.currentProvider?.name)?.let { providerName ->
                DownloadStarter.Target(
                    providerName = providerName,
                    videoType = Video.Type.Episode(
                        id = episode.id,
                        number = episode.number,
                        title = episode.title,
                        poster = episode.poster,
                        overview = episode.overview,
                        tvShow = Video.Type.Episode.TvShow(
                            id = episode.tvShow?.id ?: "",
                            title = episode.tvShow?.title ?: "",
                            poster = episode.tvShow?.poster,
                            banner = episode.tvShow?.banner,
                            releaseDate = episode.tvShow?.released?.format("yyyy-MM-dd"),
                            imdbId = episode.tvShow?.imdbId,
                        ),
                        season = Video.Type.Episode.Season(
                            number = episode.season?.number ?: 0,
                            title = episode.season?.title,
                        ),
                    ),
                    title = episode.tvShow?.title ?: "",
                    subtitle = mobilePlayerSubtitle(),
                    poster = episode.poster ?: episode.tvShow?.poster,
                )
            }
        )
        binding.btnEpisodeDownload.beforeStart = { SyncProviders.switchTo(episode.tvShow?.providerName) }
    }

    /**
     * Episode card body (season screen): "E5 · Title", the release date plus the time left when
     * partly watched, a wavy progress bar only while in progress, and the watched check.
     */
    private fun bindEpisodeCard(
        watchedBadge: View,
        progressBar: LinearProgressIndicator,
        title: TextView,
        meta: TextView,
        released: String?,
    ) {
        watchedBadge.visibility = if (episode.isWatched) View.VISIBLE else View.GONE

        val watchHistory = episode.watchHistory
        val inProgress = watchHistory != null && !episode.isWatched
        progressBar.visibility = if (inProgress) View.VISIBLE else View.GONE
        progressBar.progress = watchHistory?.let {
            if (it.durationMillis > 0) (it.lastPlaybackPositionMillis * 100 / it.durationMillis.toDouble()).toInt() else 0
        } ?: 0

        title.text = context.getString(
            R.string.episode_card_title,
            episode.number,
            episode.title ?: context.getString(R.string.episode_number, episode.number)
        )

        val timeLeft = watchHistory
            ?.takeIf { inProgress && it.durationMillis > 0 }
            ?.let { ((it.durationMillis - it.lastPlaybackPositionMillis) / 60_000L).coerceAtLeast(1L) }
            ?.let { context.getString(R.string.episode_time_left, it.toInt()) }
        val text = listOfNotNull(released, timeLeft).joinToString("  \u00B7  ")
        meta.text = text
        meta.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
    }

    /** Player header line for this episode ("S1 E2 • Title"). */
    private fun mobilePlayerSubtitle(): String =
        episode.season?.takeIf { it.number != 0 }?.let { season ->
            context.getString(
                R.string.player_subtitle_tv_show,
                season.number,
                episode.number,
                episode.title ?: context.getString(R.string.episode_number, episode.number)
            )
        } ?: context.getString(
            R.string.player_subtitle_tv_show_episode_only,
            episode.number,
            episode.title ?: context.getString(R.string.episode_number, episode.number)
        )

    private fun displayTvItem(binding: ItemEpisodeTvBinding) {
        binding.root.apply {
            setOnClickListener {
                findNavController().navigate(
                    SeasonTvFragmentDirections.actionSeasonToPlayer(
                        id = episode.id,
                        title = episode.tvShow?.title ?: "",
                        subtitle = episode.season?.takeIf { it.number != 0 }?.let { season ->
                            context.getString(
                                R.string.player_subtitle_tv_show,
                                season.number,
                                episode.number,
                                episode.title ?: context.getString(
                                    R.string.episode_number,
                                    episode.number
                                )
                            )
                        } ?: context.getString(
                            R.string.player_subtitle_tv_show_episode_only,
                            episode.number,
                            episode.title ?: context.getString(
                                R.string.episode_number,
                                episode.number
                            )
                        ),
                        videoType = Video.Type.Episode(
                            id = episode.id,
                            number = episode.number,
                            title = episode.title,
                            poster = episode.poster,
                            overview = episode.overview,
                            tvShow = Video.Type.Episode.TvShow(
                                id = episode.tvShow?.id ?: "",
                                title = episode.tvShow?.title ?: "",
                                poster = episode.tvShow?.poster,
                                banner = episode.tvShow?.banner,
                                releaseDate = episode.tvShow?.released?.format("yyyy-MM-dd"),
                                imdbId = episode.tvShow?.imdbId,
                            ),
                            season = Video.Type.Episode.Season(
                                number = episode.season?.number ?: 0,
                                title = episode.season?.title,
                            ),
                        ),
                    )
                )
            }
            setOnLongClickListener {
                ShowOptionsTvDialog(context, episode)
                    .show()
                true
            }
            // Spring scale; the ring is the thumbnail's focus foreground (fg_item_episode_image_tv).
            FocusScaleAnimator.attach(this, focusedScale = 1.05f, liftPx = 0f, ringWidthPx = 0)
        }

        binding.ivEpisodePoster.apply {
            Glide.with(context)
                .load(episode.poster)
                .error(R.drawable.glide_fallback_cover)
                .fallback(R.drawable.glide_fallback_cover)
                .centerCrop()
                .transition(DrawableTransitionOptions.withCrossFade())
                .into(this)
        }
        bindEpisodeCard(
            binding.ivEpisodeWatchedRibbon,
            binding.pbEpisodeProgress,
            binding.tvEpisodeTitle,
            binding.tvEpisodeReleased,
            episode.released?.format("EEEE - MMMM dd, yyyy"),
        )
        binding.tvEpisodeOverview.text = episode.overview ?: ""
    }

    private fun displayContinueWatchingMobileItem(binding: ItemEpisodeContinueWatchingMobileBinding) {
        binding.root.apply {
            setOnClickListener {
                // Items from another provider (cross-provider Continue Watching).
                SyncProviders.switchTo(episode.tvShow?.providerName)
                findNavController().navigate(
                    HomeMobileFragmentDirections.actionHomeToTvShow(
                        id = episode.tvShow?.id ?: "",
                        poster = episode.tvShow?.poster,
                        banner = episode.tvShow?.banner,
                    )
                )
                findNavController().navigate(
                    TvShowMobileFragmentDirections.actionTvShowToPlayer(
                        id = episode.id,
                        title = episode.tvShow?.title ?: "",
                        subtitle = episode.season?.takeIf { it.number != 0 }?.let { season ->
                            context.getString(
                                R.string.player_subtitle_tv_show,
                                season.number,
                                episode.number,
                                episode.title ?: context.getString(
                                    R.string.episode_number,
                                    episode.number
                                )
                            )
                        } ?: context.getString(
                            R.string.player_subtitle_tv_show_episode_only,
                            episode.number,
                            episode.title ?: context.getString(
                                R.string.episode_number,
                                episode.number
                            )
                        ),
                        videoType = Video.Type.Episode(
                            id = episode.id,
                            number = episode.number,
                            title = episode.title,
                            poster = episode.poster,
                            overview = episode.overview,
                            tvShow = Video.Type.Episode.TvShow(
                                id = episode.tvShow?.id ?: "",
                                title = episode.tvShow?.title ?: "",
                                poster = episode.tvShow?.poster,
                                banner = episode.tvShow?.banner,
                                releaseDate = episode.tvShow?.released?.format("yyyy-MM-dd"),
                                imdbId = episode.tvShow?.imdbId,
                            ),
                            season = Video.Type.Episode.Season(
                                number = episode.season?.number ?: 0,
                                title = episode.season?.title,
                            ),
                        ),
                    )
                )
            }
            setOnLongClickListener {
                ShowOptionsMobileDialog(context, episode)
                    .show()
                true
            }
        }

        binding.ivEpisodeTvShowPoster.apply {
            clipToOutline = true
            loadContinueWatchingArtwork()
        }

        binding.pbEpisodeProgress.apply {
            val watchHistory = episode.watchHistory

            progress = when {
                watchHistory != null -> (watchHistory.lastPlaybackPositionMillis * 100 / watchHistory.durationMillis.toDouble()).toInt()
                else -> 0
            }
            visibility = when {
                watchHistory != null -> View.VISIBLE
                else -> View.GONE
            }
        }

        binding.tvEpisodeTvShowTitle.text = episode.tvShow?.title ?: ""

        binding.tvEpisodeInfo.text = episode.season?.takeIf { it.number != 0 }?.let { season ->
            context.getString(
                R.string.episode_item_info,
                season.number,
                episode.number,
                episode.title ?: context.getString(
                    R.string.episode_number,
                    episode.number
                )
            )
        } ?: context.getString(
            R.string.episode_item_info_episode_only,
            episode.number,
            episode.title ?: context.getString(
                R.string.episode_number,
                episode.number
            )
        )
    }

    private fun displayContinueWatchingTvItem(binding: ItemEpisodeContinueWatchingTvBinding) {
        binding.root.apply {
            setOnClickListener {
                // Items from another provider (cross-provider Continue Watching).
                SyncProviders.switchTo(episode.tvShow?.providerName)
                findNavController().navigate(
                    HomeTvFragmentDirections.actionHomeToTvShow(
                        id = episode.tvShow?.id ?: "",
                        poster = episode.tvShow?.poster,
                        banner = episode.tvShow?.banner,
                    )
                )
                findNavController().navigate(
                    TvShowTvFragmentDirections.actionTvShowToPlayer(
                        id = episode.id,
                        title = episode.tvShow?.title ?: "",
                        subtitle = episode.season?.takeIf { it.number != 0 }?.let { season ->
                            context.getString(
                                R.string.player_subtitle_tv_show,
                                season.number,
                                episode.number,
                                episode.title ?: context.getString(
                                    R.string.episode_number,
                                    episode.number
                                )
                            )
                        } ?: context.getString(
                            R.string.player_subtitle_tv_show_episode_only,
                            episode.number,
                            episode.title ?: context.getString(
                                R.string.episode_number,
                                episode.number
                            )
                        ),
                        videoType = Video.Type.Episode(
                            id = episode.id,
                            number = episode.number,
                            title = episode.title,
                            poster = episode.poster,
                            overview = episode.overview,
                            tvShow = Video.Type.Episode.TvShow(
                                id = episode.tvShow?.id ?: "",
                                title = episode.tvShow?.title ?: "",
                                poster = episode.tvShow?.poster,
                                banner = episode.tvShow?.banner,
                                releaseDate = episode.tvShow?.released?.format("yyyy-MM-dd"),
                                imdbId = episode.tvShow?.imdbId,
                            ),
                            season = Video.Type.Episode.Season(
                                number = episode.season?.number ?: 0,
                                title = episode.season?.title,
                            ),
                        ),
                    )
                )
            }
            setOnLongClickListener {
                ShowOptionsTvDialog(context, episode)
                    .show()
                true
            }
            setOnKeyListener { _, keyCode, event ->
                // Menu opens the same options as a long press on OK.
                if (keyCode != KeyEvent.KEYCODE_MENU) return@setOnKeyListener false
                if (event.action == KeyEvent.ACTION_UP) performLongClick()
                true
            }
            setOnFocusChangeListener { _, hasFocus ->
                val animation = when {
                    hasFocus -> AnimationUtils.loadAnimation(context, R.anim.zoom_in)
                    else -> AnimationUtils.loadAnimation(context, R.anim.zoom_out)
                }
                binding.root.startAnimation(animation)
                animation.fillAfter = true

                when (val fragment = context.toActivity()?.getCurrentFragment()) {
                    is HomeTvFragment -> {
                        if (hasFocus) {
                            fragment.pinBackground(episode.tvShow?.banner)
                        } else {
                            fragment.releasePinnedBackground()
                        }
                    }
                }
            }
        }

        binding.ivEpisodeTvShowPoster.apply {
            clipToOutline = true
            loadContinueWatchingArtwork(withFallback = true)
        }

        binding.pbEpisodeProgress.apply {
            val watchHistory = episode.watchHistory

            progress = when {
                watchHistory != null -> (watchHistory.lastPlaybackPositionMillis * 100 / watchHistory.durationMillis.toDouble()).toInt()
                episode.isWatched -> 100
                else -> 0
            }
            visibility = when {
                watchHistory != null -> View.VISIBLE
                episode.isWatched -> View.VISIBLE
                else -> View.GONE
            }
        }

        binding.tvEpisodeTvShowTitle.text = episode.tvShow?.title ?: ""

        binding.tvEpisodeInfo.text = episode.season?.takeIf { it.number != 0 }?.let { season ->
            context.getString(
                R.string.episode_item_info,
                season.number,
                episode.number,
                episode.title ?: context.getString(
                    R.string.episode_number,
                    episode.number
                )
            )
        } ?: context.getString(
            R.string.episode_item_info_episode_only,
            episode.number,
            episode.title ?: context.getString(
                R.string.episode_number,
                episode.number
            )
        )
    }

    private fun ImageView.loadContinueWatchingArtwork(withFallback: Boolean = false) {
        val tvShow = episode.tvShow
        if (tvShow == null) {
            Glide.with(context)
                .load(episode.poster)
                .error(R.drawable.glide_fallback_cover)
                .apply {
                    if (withFallback) fallback(R.drawable.glide_fallback_cover)
                }
                .centerCrop()
                .transition(DrawableTransitionOptions.withCrossFade())
                .into(this)
            return
        }

        loadTvShowCardArtwork(tvShow) {
            error(R.drawable.glide_fallback_cover)
            apply {
                if (withFallback) fallback(R.drawable.glide_fallback_cover)
            }
            centerCrop()
            transition(DrawableTransitionOptions.withCrossFade())
        }
    }
}
