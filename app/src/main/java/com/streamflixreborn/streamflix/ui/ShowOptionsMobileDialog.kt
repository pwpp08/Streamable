package com.streamflixreborn.streamflix.ui

import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.NavHostFragment
import com.bumptech.glide.Glide
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.database.AppDatabase
import com.streamflixreborn.streamflix.databinding.DialogShowOptionsMobileBinding
import com.streamflixreborn.streamflix.fragments.home.HomeMobileFragment
import com.streamflixreborn.streamflix.fragments.home.HomeMobileFragmentDirections
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.providers.Provider
import com.streamflixreborn.streamflix.sync.PlayOnTv
import com.streamflixreborn.streamflix.sync.SyncProviders
import com.streamflixreborn.streamflix.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.*

class ShowOptionsMobileDialog(
    context: Context,
    show: AppAdapter.Item,
) : BottomSheetDialog(context) {

    private val binding = DialogShowOptionsMobileBinding.inflate(LayoutInflater.from(context))

    private val database: AppDatabase
        get() = AppDatabase.getInstance(context)

    private fun checkProviderAndRun(show: AppAdapter.Item, action: () -> Unit) {
        val providerName = when (show) {
            is Movie -> show.providerName
            is TvShow -> show.providerName
            is Episode -> show.tvShow?.providerName
            else -> null
        }

        if (!providerName.isNullOrBlank() && providerName != UserPreferences.currentProvider?.name) {
            Provider.providers.keys.find { it.name == providerName }?.let {
                UserPreferences.currentProvider = it
            }
        }
        action()
    }

    /** Opened from the Home "Continue Watching" row. */
    private val isContinueWatching = WatchStateActions.isContinueWatchingItem(show)

    init {
        setContentView(binding.root)

        findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            ?.setBackgroundColor(Color.TRANSPARENT)

        binding.btnOptionEpisodeMarkSeasonWatched.visibility = View.GONE

        when (show) {
            is Episode -> displayEpisode(show)
            is Movie -> displayMovie(show)
            is TvShow -> displayTvShow(show)
        }

        val isRecentlyWatched = show.itemType == AppAdapter.Type.MOVIE_RECENTLY_WATCHED_MOBILE_ITEM ||
                show.itemType == AppAdapter.Type.MOVIE_RECENTLY_WATCHED_TV_ITEM ||
                show.itemType == AppAdapter.Type.EPISODE_RECENTLY_WATCHED_MOBILE_ITEM ||
                show.itemType == AppAdapter.Type.EPISODE_RECENTLY_WATCHED_TV_ITEM ||
                show.itemType == AppAdapter.Type.TV_SHOW_RECENTLY_WATCHED_MOBILE_ITEM ||
                show.itemType == AppAdapter.Type.TV_SHOW_RECENTLY_WATCHED_TV_ITEM

        if (isRecentlyWatched) {
            binding.btnOptionEpisodeOpenTvShow.visibility = View.GONE
            binding.btnOptionEpisodeMarkAllPreviousWatched.visibility = View.GONE
            binding.btnOptionEpisodeMarkSeasonWatched.visibility = View.GONE
            binding.btnOptionProgramClear.visibility = View.GONE

            binding.btnOptionShowWatched.apply {
                text = context.getString(R.string.option_show_remove_recently_watched)
                visibility = View.VISIBLE
                setOnClickListener {
                    context.toActivity()?.lifecycleScope?.launch(Dispatchers.IO) {
                        when (show) {
                            is Movie -> AppDatabase.getInstance(context).movieDao().removeFromRecentlyWatched(show.id)
                            is TvShow -> AppDatabase.getInstance(context).tvShowDao().removeFromRecentlyWatched(show.id)
                            is Episode -> show.tvShow?.let { AppDatabase.getInstance(context).tvShowDao().removeFromRecentlyWatched(it.id) }
                        }
                    }
                    hide()
                }
            }
        }

        binding.btnOptionPlayOnTv.apply {
            val activity = context.toActivity() as? androidx.fragment.app.FragmentActivity
            val supported = show is Movie || show is Episode
            visibility = if (supported && activity != null && PlayOnTv.isAvailable(activity)) {
                View.VISIBLE
            } else {
                View.GONE
            }
            setOnClickListener {
                hide()
                activity?.let { PlayOnTv.play(it, show) }
            }
        }

        if (isContinueWatching && binding.btnOptionProgramClear.visibility == View.VISIBLE) {
            // The row's own action first.
            (binding.btnOptionProgramClear.parent as? ViewGroup)?.let { parent ->
                parent.removeView(binding.btnOptionProgramClear)
                parent.addView(binding.btnOptionProgramClear, 0)
            }
        }

        binding.btnOptionCancel.setOnClickListener {
            hide()
        }
    }


    private fun displayEpisode(episode: Episode) {
        val provider = UserPreferences.currentProvider

        Glide.with(context)
            .load(episode.poster ?: episode.tvShow?.poster)
            .fitCenter()
            .into(binding.ivOptionsShowPoster)

        binding.tvOptionsShowTitle.text = episode.tvShow?.title ?: ""

        binding.tvShowSubtitle.text = episode.season?.takeIf { it.number != 0 }?.let { season ->
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


        binding.btnOptionEpisodeOpenTvShow.apply {
            setOnClickListener {
                when (val fragment = context.toActivity()?.getCurrentFragment()) {
                    is HomeMobileFragment -> episode.tvShow?.let { tvShow ->
                        SyncProviders.switchTo(tvShow.providerName)
                        NavHostFragment.findNavController(fragment).navigate(
                            HomeMobileFragmentDirections.actionHomeToTvShow(
                                id = tvShow.id,
                                poster = tvShow.poster,
                                banner = tvShow.banner,
                            )
                        )
                    }
                }
                hide()
            }

            visibility = when (context.toActivity()?.getCurrentFragment()) {
                is HomeMobileFragment -> View.VISIBLE
                else -> View.GONE
            }
        }

        binding.btnOptionShowFavorite.visibility = View.GONE

        // Room allows main-thread reads; another provider's item carries its own state.
        val freshEpisode = if (WatchStateActions.isOtherProvider(episode)) {
            episode
        } else {
            database.episodeDao().getById(episode.id)?.let { stored ->
                episode.copy().apply { merge(stored) }
            } ?: episode
        }

        binding.btnOptionShowWatched.apply {
            setOnClickListener {
                WatchStateActions.launch(context) {
                    WatchStateActions.setWatched(context, episode, !freshEpisode.isWatched)
                }
                hide()
            }

            text = when {
                freshEpisode.isWatched -> context.getString(R.string.option_show_unwatched)
                else -> context.getString(R.string.option_show_watched)
            }
            visibility = View.VISIBLE
        }
        binding.btnOptionEpisodeMarkAllPreviousWatched.apply {
            setOnClickListener {
                WatchStateActions.launch(context) {
                    WatchStateActions.setWatchedUntil(context, episode, !freshEpisode.isWatched)
                }
                hide()
            }

            text = when {
                freshEpisode.isWatched -> context.getString(R.string.option_show_mark_all_previous_unwatched)
                else -> context.getString(R.string.option_show_mark_all_previous_watched)
            }
            visibility = View.VISIBLE
        }

        binding.btnOptionEpisodeMarkSeasonWatched.apply {
            setOnClickListener {
                WatchStateActions.launch(context) {
                    WatchStateActions.markSeasonWatched(context, episode)
                }
                hide()
            }
            visibility = if (episode.season?.id != null) View.VISIBLE else View.GONE
        }

        binding.btnOptionProgramClear.apply {
            setOnClickListener {
                WatchStateActions.launch(context) {
                    WatchStateActions.removeFromContinueWatching(context, episode)
                }
                hide()
            }

            visibility = when {
                isContinueWatching -> View.VISIBLE
                freshEpisode.watchHistory != null -> View.VISIBLE
                episode.tvShow?.isWatching ?: false -> View.VISIBLE
                else -> View.GONE
            }
        }
    }

    private fun displayMovie(movie: Movie) {
        binding.ivOptionsShowPoster.loadMoviePoster(movie) {
            fitCenter()
        }

        binding.tvOptionsShowTitle.text = movie.title

        binding.tvShowSubtitle.text = movie.released?.format("yyyy")


        binding.btnOptionEpisodeOpenTvShow.visibility = View.GONE

        val freshMovie = if (WatchStateActions.isOtherProvider(movie)) {
            movie
        } else {
            database.movieDao().getById(movie.id) ?: movie
        }

        binding.btnOptionShowFavorite.apply {
            setOnClickListener {
                checkProviderAndRun(freshMovie) {
                    val provider = UserPreferences.currentProvider ?: return@checkProviderAndRun
                    context.toActivity()?.lifecycleScope?.launch(Dispatchers.IO) {
                        val dao = database.movieDao()
                        val current = dao.getById(movie.id)?.isFavorite ?: false
                        val newValue = !current
                        val resolvedMovie = ArtworkRepair.resolveMovieForFavorite(context, movie, newValue)
                        dao.upsertFavorite(resolvedMovie, newValue)
                        if (newValue) {
                            UserDataCache.addMovieToFavorites(context, provider, resolvedMovie.copy().apply { isFavorite = true })
                        } else {
                            UserDataCache.removeMovieFromFavorites(context, provider, freshMovie.id)
                        }
                    }
                }

                hide()
            }

            text = when {
                freshMovie.isFavorite -> context.getString(R.string.option_show_unfavorite)
                else -> context.getString(R.string.option_show_favorite)
            }
            visibility = View.VISIBLE
        }

        binding.btnOptionShowWatched.apply {
            setOnClickListener {
                WatchStateActions.launch(context) {
                    WatchStateActions.setWatched(context, movie, !freshMovie.isWatched)
                }
                hide()
            }

            text = when {
                freshMovie.isWatched -> context.getString(R.string.option_show_unwatched)
                else -> context.getString(R.string.option_show_watched)
            }
            visibility = View.VISIBLE
        }

        binding.btnOptionProgramClear.apply {
            setOnClickListener {
                WatchStateActions.launch(context) {
                    WatchStateActions.removeFromContinueWatching(context, movie)
                }
                hide()
            }

            visibility = when {
                isContinueWatching -> View.VISIBLE
                freshMovie.watchHistory != null -> View.VISIBLE
                else -> View.GONE
            }
        }
    }

    private fun displayTvShow(tvShow: TvShow) {
        binding.ivOptionsShowPoster.loadTvShowPoster(tvShow) {
            fitCenter()
        }

        binding.tvOptionsShowTitle.text = tvShow.title

        binding.tvShowSubtitle.text = tvShow.released?.format("yyyy")


        binding.btnOptionEpisodeOpenTvShow.visibility = View.GONE

        val freshTvShow = database.tvShowDao().getById(tvShow.id) ?: tvShow

        binding.btnOptionShowFavorite.apply {
            setOnClickListener {
                checkProviderAndRun(freshTvShow) {
                    val provider = UserPreferences.currentProvider ?: return@checkProviderAndRun
                    context.toActivity()?.lifecycleScope?.launch(Dispatchers.IO) {
                        val dao = database.tvShowDao()
                        val current = dao.getById(tvShow.id)?.isFavorite ?: false
                        val newValue = !current
                        val resolvedTvShow = ArtworkRepair.resolveTvShowForFavorite(context, tvShow, newValue)

                        dao.upsertFavorite(resolvedTvShow, newValue)
                        if (newValue) {
                            UserDataCache.syncTvShowToCache(
                                context,
                                provider,
                                resolvedTvShow.copy().apply { isFavorite = true })
                        } else {
                            UserDataCache.removeTvShowFromFavorites(context, provider, freshTvShow.id)
                        }
                    }
                }

                hide()
            }

            text = if (freshTvShow.isFavorite) {
                context.getString(R.string.option_show_unfavorite)
            } else {
                context.getString(R.string.option_show_favorite)
            }
            visibility = View.VISIBLE
        }
    }
}
