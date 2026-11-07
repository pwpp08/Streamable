package com.streamflixreborn.streamflix.adapters.viewholders

import com.streamflixreborn.streamflix.utils.CinemaStatus
import com.streamflixreborn.streamflix.utils.FavoriteSync
import com.streamflixreborn.streamflix.utils.ReleaseStatus
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.Toast
import android.widget.TextView
import androidx.core.view.isVisible
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.streamflixreborn.streamflix.ui.details.DetailsUi
import com.streamflixreborn.streamflix.ui.details.DetailsUi.setTextOrGone
import com.streamflixreborn.streamflix.utils.WatchStateActions
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.navigation.findNavController
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.database.AppDatabase
import com.streamflixreborn.streamflix.databinding.ContentMovieCastMobileBinding
import com.streamflixreborn.streamflix.databinding.ContentMovieCastTvBinding
import com.streamflixreborn.streamflix.databinding.ContentMovieMobileBinding
import com.streamflixreborn.streamflix.databinding.ContentMovieRecommendationsMobileBinding
import com.streamflixreborn.streamflix.databinding.ContentMovieRecommendationsTvBinding
import com.streamflixreborn.streamflix.databinding.ContentMovieTvBinding
import com.streamflixreborn.streamflix.databinding.ItemCategorySwiperMobileBinding
import com.streamflixreborn.streamflix.databinding.ItemMovieContinueWatchingMobileBinding
import com.streamflixreborn.streamflix.databinding.ItemMovieContinueWatchingTvBinding
import com.streamflixreborn.streamflix.databinding.ItemMovieGridMobileBinding
import android.widget.ImageView
import com.streamflixreborn.streamflix.databinding.ItemMovieGridTvBinding
import com.streamflixreborn.streamflix.databinding.ItemMovieMobileBinding
import com.streamflixreborn.streamflix.databinding.ItemMovieTvBinding
import com.streamflixreborn.streamflix.fragments.queue.QueueMobileFragment
import com.streamflixreborn.streamflix.fragments.queue.QueueMobileFragmentDirections
import com.streamflixreborn.streamflix.fragments.queue.QueueTvFragment
import com.streamflixreborn.streamflix.fragments.queue.QueueTvFragmentDirections
import com.streamflixreborn.streamflix.fragments.genre.GenreMobileFragment
import com.streamflixreborn.streamflix.fragments.genre.GenreMobileFragmentDirections
import com.streamflixreborn.streamflix.fragments.genre.GenreTvFragment
import com.streamflixreborn.streamflix.fragments.genre.GenreTvFragmentDirections
import com.streamflixreborn.streamflix.fragments.home.HomeMobileFragment
import com.streamflixreborn.streamflix.fragments.home.HomeMobileFragmentDirections
import com.streamflixreborn.streamflix.fragments.home.HomeTvFragment
import com.streamflixreborn.streamflix.fragments.home.HomeTvFragmentDirections
import com.streamflixreborn.streamflix.fragments.movie.MovieMobileFragment
import com.streamflixreborn.streamflix.fragments.movie.MovieMobileFragmentDirections
import com.streamflixreborn.streamflix.fragments.movie.MovieTvFragment
import com.streamflixreborn.streamflix.fragments.movie.MovieTvFragmentDirections
import com.streamflixreborn.streamflix.fragments.movies.MoviesMobileFragment
import com.streamflixreborn.streamflix.fragments.movies.MoviesMobileFragmentDirections
import com.streamflixreborn.streamflix.fragments.movies.MoviesTvFragment
import com.streamflixreborn.streamflix.fragments.movies.MoviesTvFragmentDirections
import com.streamflixreborn.streamflix.fragments.people.PeopleMobileFragment
import com.streamflixreborn.streamflix.fragments.people.PeopleMobileFragmentDirections
import com.streamflixreborn.streamflix.fragments.people.PeopleTvFragment
import com.streamflixreborn.streamflix.fragments.people.PeopleTvFragmentDirections
import com.streamflixreborn.streamflix.fragments.search.SearchMobileFragment
import com.streamflixreborn.streamflix.fragments.search.SearchMobileFragmentDirections
import com.streamflixreborn.streamflix.fragments.search.SearchTvFragment
import com.streamflixreborn.streamflix.fragments.search.SearchTvFragmentDirections
import com.streamflixreborn.streamflix.fragments.tv_show.TvShowMobileFragment
import com.streamflixreborn.streamflix.fragments.tv_show.TvShowMobileFragmentDirections
import com.streamflixreborn.streamflix.fragments.tv_show.TvShowTvFragment
import com.streamflixreborn.streamflix.fragments.tv_show.TvShowTvFragmentDirections
import com.streamflixreborn.streamflix.fragments.tv_shows.TvShowsTvFragment
import com.streamflixreborn.streamflix.fragments.tv_shows.TvShowsTvFragmentDirections
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.downloads.DownloadStarter
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.ui.ShowOptionsMobileDialog
import com.streamflixreborn.streamflix.sync.PlayOnTv
import com.streamflixreborn.streamflix.sync.SyncProviders
import com.streamflixreborn.streamflix.ui.ShowOptionsTvDialog
import com.streamflixreborn.streamflix.ui.SpacingItemDecoration
import com.streamflixreborn.streamflix.ui.browse.BrowseCards
import com.streamflixreborn.streamflix.utils.dp
import androidx.preference.Preference
import com.streamflixreborn.streamflix.utils.format
import com.streamflixreborn.streamflix.utils.getCurrentFragment
import com.streamflixreborn.streamflix.utils.loadMovieBanner
import com.streamflixreborn.streamflix.utils.loadMovieCardArtwork
import com.streamflixreborn.streamflix.utils.loadMoviePoster
import com.streamflixreborn.streamflix.utils.ArtworkRepair
import com.streamflixreborn.streamflix.utils.toActivity
import java.util.Locale
import com.streamflixreborn.streamflix.utils.UserPreferences
import com.streamflixreborn.streamflix.providers.Provider
import android.view.KeyEvent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import com.streamflixreborn.streamflix.databinding.ContentMovieDirectorsMobileBinding
import com.streamflixreborn.streamflix.databinding.ContentMovieDirectorsTvBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MovieViewHolder(
    private val _binding: ViewBinding
) : RecyclerView.ViewHolder(
    _binding.root
) {

    private val context = itemView.context
    private val database: AppDatabase
        get() = AppDatabase.getInstance(context)
    private lateinit var movie: Movie
    private var onMovieClick: ((Movie) -> Unit)? = null
    private var onMovieLongClick: ((Movie) -> Unit)? = null
    private var onMovieKey: ((Movie, KeyEvent) -> Boolean)? = null
    private var itemSelected: Boolean = false
    private var ribbonStateJob: Job? = null
    private val TAG = "TrailerChoiceDebug" // Logging Tag

    companion object {
        private const val KEY_PREFERRED_PLAYER = "preferred_player"
        private const val KEY_SMARTTUBE_PACKAGE = "preferred_smarttube_package" // New key for saving the exact package
        private const val PLAYER_YOUTUBE = "youtube"
        private const val PLAYER_SMARTTUBE = "smarttube"
        private const val PLAYER_SMARTTUBE_STABLE = "smarttube_stable"
        private const val PLAYER_SMARTTUBE_BETA = "smarttube_beta"
        private const val PLAYER_TIZENTUBE = "tizentube"
        private const val PLAYER_ASK = "ask"
        private const val SMARTTUBE_STABLE_PACKAGE = "org.smarttube.stable"
        private const val SMARTTUBE_BETA_PACKAGE = "org.smarttube.beta"
        private const val TIZENTUBE_PACKAGE = "io.gh.reisxd.tizentube.cobalt"
        private const val YOUTUBE_PACKAGE = "com.google.android.youtube"
        private const val YOUTUBE_TV_PACKAGE = "com.google.android.tv.youtube"
    }

    val childRecyclerView: RecyclerView?
        get() = when (_binding) {
            is ContentMovieCastMobileBinding -> _binding.rvMovieCast
            is ContentMovieCastTvBinding -> _binding.hgvMovieCast
            is ContentMovieRecommendationsMobileBinding -> _binding.rvMovieRecommendations
            is ContentMovieRecommendationsTvBinding -> _binding.hgvMovieRecommendations
            else -> null
        }

    fun bind(
        movie: Movie,
        onMovieClick: ((Movie) -> Unit)? = null,
        onMovieLongClick: ((Movie) -> Unit)? = null,
        onMovieKey: ((Movie, KeyEvent) -> Boolean)? = null,
        itemSelected: Boolean = false,
    ) {
        this.movie = movie
        this.onMovieClick = onMovieClick
        this.onMovieLongClick = onMovieLongClick
        this.onMovieKey = onMovieKey
        this.itemSelected = itemSelected

        when (_binding) {
            is ItemMovieMobileBinding -> displayMobileItem(_binding)
            is ItemMovieTvBinding -> displayTvItem(_binding)
            is ItemMovieContinueWatchingMobileBinding -> displayContinueWatchingMobileItem(_binding)
            is ItemMovieContinueWatchingTvBinding -> displayContinueWatchingTvItem(_binding)
            is ItemMovieGridMobileBinding -> displayGridMobileItem(_binding)
            is ItemMovieGridTvBinding -> displayGridTvItem(_binding)
            is ItemCategorySwiperMobileBinding -> displaySwiperMobileItem(_binding)

            is ContentMovieMobileBinding -> displayMovieMobile(_binding)
            is ContentMovieTvBinding -> displayMovieTv(_binding)
            is ContentMovieDirectorsMobileBinding -> displayDirectorsMobile(_binding)
            is ContentMovieDirectorsTvBinding -> displayDirectorsTv(_binding)
            is ContentMovieCastMobileBinding -> displayCastMobile(_binding)
            is ContentMovieCastTvBinding -> displayCastTv(_binding)
            is ContentMovieRecommendationsMobileBinding -> displayRecommendationsMobile(_binding)
            is ContentMovieRecommendationsTvBinding -> displayRecommendationsTv(_binding)
        }
    }

    fun setItemSelected(selected: Boolean) {
        itemSelected = selected
        when (_binding) {
            is ItemMovieGridMobileBinding -> {
                _binding.root.isActivated = selected
                applyMobileSelection(_binding.root)
            }
            is ItemMovieGridTvBinding -> _binding.root.isActivated = selected
        }
    }

    private fun checkProviderAndRun(action: () -> Unit) {
        // Also resolves TMDb providers (cross-provider Continue Watching).
        SyncProviders.switchTo(movie.providerName)
        action()
    }

    private fun isPackageInstalled(packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun getInstalledSmartTubePackages(): List<String> {
        val installed = mutableListOf<String>()
        if (isPackageInstalled(SMARTTUBE_STABLE_PACKAGE)) installed.add(SMARTTUBE_STABLE_PACKAGE)
        if (isPackageInstalled(SMARTTUBE_BETA_PACKAGE)) installed.add(SMARTTUBE_BETA_PACKAGE)
        return installed
    }

    private fun launchSmartTube(packageName: String, trailerUrl: String) {
        val intent = Intent(Intent.ACTION_VIEW, trailerUrl.toUri())
        intent.setPackage(packageName)
        context.startActivity(intent)
    }

    private fun launchTizenTube(trailerUrl: String) {
        val intent = Intent(Intent.ACTION_VIEW, trailerUrl.toUri())
        intent.setPackage(TIZENTUBE_PACKAGE)
        context.startActivity(intent)
    }

    private fun showSmartTubeVersionDialog(packages: List<String>, trailerUrl: String, shouldSavePreference: Boolean) {
        
        val items = packages.map { pkg ->
            if (pkg == SMARTTUBE_STABLE_PACKAGE) context.getString(R.string.smarttube_stable)
            else context.getString(R.string.smarttube_beta)
        }.toTypedArray()

        AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.choose_smarttube_version))
            .setItems(items) { _, which ->
                val selectedPackage = packages[which]
                
                if (shouldSavePreference) {
                    // Salva la scelta dell'utente se la preferenza principale è "smarttube"
                    UserPreferences.setProfilePreferenceString(KEY_SMARTTUBE_PACKAGE, selectedPackage)
                    Log.d(TAG, "SmartTube version saved: $selectedPackage")
                }
                
                launchSmartTube(selectedPackage, trailerUrl)
            }.show()
    }

    private fun safeLaunchYoutube(intent: Intent) {
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch YouTube intent", e)
            Toast.makeText(context, context.getString(R.string.player_external_player_error_video), Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleSmartTubeSelection(trailerUrl: String, logPrefix: String) {
        val savedPackage = UserPreferences.getProfilePreferenceString(KEY_SMARTTUBE_PACKAGE)
        val stPackages = getInstalledSmartTubePackages()

        Log.d(TAG, "$logPrefix: SmartTube packages found: ${stPackages.size}. Saved package: $savedPackage")
        
        if (stPackages.isEmpty()) {
            // Caso 1: Nessuna SmartTube installata. Fallback su YouTube.
            Log.d(TAG, "$logPrefix: No SmartTube installed, falling back to YouTube")
            safeLaunchYoutube(Intent(Intent.ACTION_VIEW, trailerUrl.toUri()))
            return
        }

        if (stPackages.size == 1) {
            // Caso 2: Una sola SmartTube installata. Avvia direttamente.
            Log.d(TAG, "$logPrefix: Only one SmartTube installed: ${stPackages[0]}. Launching directly.")
            launchSmartTube(stPackages[0], trailerUrl)
            return
        }
        
        // Caso 3: Stable e Beta installate.
        if (savedPackage != null && stPackages.contains(savedPackage)) {
            // Caso 3a: Versione preferita è installata. Avvia direttamente la versione salvata.
            Log.d(TAG, "$logPrefix: Saved SmartTube version found: $savedPackage. Launching directly.")
            launchSmartTube(savedPackage, trailerUrl)
        } else {
            // Caso 3b: Nessuna preferenza salvata O la versione salvata non è più installata. Chiedi all'utente e salva la nuova scelta.
            Log.d(TAG, "$logPrefix: Saved version invalid or missing. Asking user which version to use.")
            showSmartTubeVersionDialog(stPackages, trailerUrl, true)
        }
    }

    private fun handleTrailerClick(trailer: String, logPrefix: String) {
        Log.d(TAG, "$logPrefix: Clicked. Trailer URL: $trailer")

        val youtubeIntent = Intent(Intent.ACTION_VIEW, trailer.toUri())
        val preferredPlayer = UserPreferences.getProfilePreferenceString(KEY_PREFERRED_PLAYER, PLAYER_ASK)
        Log.d(TAG, "$logPrefix: Preferred player from settings: $preferredPlayer")

        when (preferredPlayer) {
            PLAYER_SMARTTUBE -> {
                handleSmartTubeSelection(trailer, logPrefix)
            }
            PLAYER_SMARTTUBE_STABLE -> {
                Log.d(TAG, "$logPrefix: Launching SmartTube Stable (Preferred)")
                launchSmartTube(SMARTTUBE_STABLE_PACKAGE, trailer)
            }
            PLAYER_SMARTTUBE_BETA -> {
                Log.d(TAG, "$logPrefix: Launching SmartTube Beta (Preferred)")
                launchSmartTube(SMARTTUBE_BETA_PACKAGE, trailer)
            }
            PLAYER_TIZENTUBE -> {
                Log.d(TAG, "$logPrefix: Launching TizenTube (Preferred)")
                if (isPackageInstalled(TIZENTUBE_PACKAGE)) {
                    launchTizenTube(trailer)
                } else {
                    Log.d(TAG, "$logPrefix: TizenTube not installed, falling back to YouTube")
                    safeLaunchYoutube(youtubeIntent)
                }
            }
            PLAYER_YOUTUBE -> {
                Log.d(TAG, "$logPrefix: Launching YouTube (Preferred)")
                safeLaunchYoutube(youtubeIntent)
            }
            else -> { // PLAYER_ASK or nothing set
                val stPackages = getInstalledSmartTubePackages()
                val isTizenInstalled = isPackageInstalled(TIZENTUBE_PACKAGE)

                val options = mutableListOf<Pair<String, () -> Unit>>()
                options.add(context.getString(R.string.youtube) to {
                    Log.d(TAG, "$logPrefix: Dialog (Ask): YouTube selected")
                    safeLaunchYoutube(youtubeIntent)
                })

                if (stPackages.isNotEmpty()) {
                    options.add(context.getString(R.string.smarttube) to {
                        Log.d(TAG, "$logPrefix: Dialog (Ask): SmartTube selected")
                        if (stPackages.size > 1) {
                            showSmartTubeVersionDialog(stPackages, trailer, false)
                        } else {
                            launchSmartTube(stPackages[0], trailer)
                        }
                    })
                }

                if (isTizenInstalled) {
                    options.add(context.getString(R.string.tizentube) to {
                        Log.d(TAG, "$logPrefix: Dialog (Ask): TizenTube selected")
                        launchTizenTube(trailer)
                    })
                }

                if (options.size > 1) {
                    Log.d(TAG, "$logPrefix: Showing choice dialog (Ask) with ${options.size} options")
                    AlertDialog.Builder(context)
                        .setTitle(context.getString(R.string.watch_trailer_with))
                        .setItems(options.map { it.first }.toTypedArray()) { _, which ->
                            options[which].second.invoke()
                        }.show()
                } else {
                    Log.d(TAG, "$logPrefix: No alternative players installed, launching YouTube directly")
                    safeLaunchYoutube(youtubeIntent)
                }
            }
        }
    }

    private fun displayMobileItem(binding: ItemMovieMobileBinding) {
        bindMobileClicks(binding.root)

        BrowseCards.attachPress(binding.root)
        binding.tvMovieInitial.text = BrowseCards.initial(movie.title)
        binding.ivMoviePoster.loadMoviePoster(movie) {
            centerCrop()
            transition(DrawableTransitionOptions.withCrossFade())
        }
        bindRibbons(binding.ivMovieFavoriteRibbon, binding.ivMovieWatchedRibbon)
        BrowseCards.bindMovieRating(binding.tvMovieRating, movie)

        binding.tvMovieQuality.apply {
            text = movie.quality ?: ""
            visibility = when {
                text.isNullOrEmpty() -> View.GONE
                else -> View.VISIBLE
            }
        }

        binding.tvMovieReleasedYear.text = movie.released?.format("yyyy")
            ?: context.getString(R.string.movie_item_type)

        binding.pbMovieProgress.apply {
            val watchHistory = movie.watchHistory
            progress = BrowseCards.progressPercent(watchHistory)
            visibility = when {
                watchHistory != null -> View.VISIBLE
                else -> View.GONE
            }
        }

        binding.tvMovieTitle.text = movie.title
    }

    private fun displayTvItem(binding: ItemMovieTvBinding) {
        bindTvClicks(binding.root)
        BrowseCards.attachTvFocus(
            binding.root,
            binding.tvMovieTitle,
            binding.tvMovieReleasedYear,
            binding.tvMovieQuality,
        ) { hasFocus ->
            (context.toActivity()?.getCurrentFragment() as? HomeTvFragment)?.let { fragment ->
                if (hasFocus) fragment.focusHero(movie) else fragment.releasePinnedBackground()
            }
        }

        binding.tvMovieInitial.text = BrowseCards.initial(movie.title)
        binding.ivMoviePoster.loadMoviePoster(movie) {
            centerCrop()
            transition(DrawableTransitionOptions.withCrossFade())
        }
        bindRibbons(binding.ivMovieFavoriteRibbon, binding.ivMovieWatchedRibbon)
        BrowseCards.bindMovieRating(binding.tvMovieRating, movie)
        binding.pbMovieProgress.apply {
            val watchHistory = movie.watchHistory
            progress = BrowseCards.progressPercent(watchHistory)
            visibility = when {
                watchHistory != null -> View.VISIBLE
                else -> View.GONE
            }
        }
        binding.tvMovieQuality.apply {
            text = movie.quality ?: ""
            visibility = when {
                text.isNullOrEmpty() -> View.GONE
                else -> View.VISIBLE
            }
        }
        binding.tvMovieReleasedYear.text = movie.released?.format("yyyy")
            ?: context.getString(R.string.movie_item_type)
        binding.tvMovieTitle.text = movie.title
    }

    private fun bindMobileClicks(root: View) {
        root.apply {
            setOnClickListener {
                onMovieClick?.let { listener ->
                    listener(movie)
                    return@setOnClickListener
                }
                checkProviderAndRun {
                    when (context.toActivity()?.getCurrentFragment()) {
                        is HomeMobileFragment -> {
                            findNavController().navigate(HomeMobileFragmentDirections.actionHomeToMovie(id = movie.id))
                            if (movie.itemType == AppAdapter.Type.MOVIE_CONTINUE_WATCHING_MOBILE_ITEM) {
                                findNavController().navigate(MovieMobileFragmentDirections.actionMovieToPlayer(
                                    id = movie.id,
                                    title = movie.title,
                                    subtitle = movie.released?.format("yyyy") ?: "",
                                    videoType = Video.Type.Movie(id = movie.id, title = movie.title, releaseDate = movie.released?.format("yyyy-MM-dd") ?: "", poster = movie.poster ?: "", imdbId = movie.imdbId),
                                ))
                            }
                        }
                        is MovieMobileFragment -> findNavController().navigate(MovieMobileFragmentDirections.actionMovieToMovie(id = movie.id))
                        is TvShowMobileFragment -> findNavController().navigate(TvShowMobileFragmentDirections.actionTvShowToMovie(id = movie.id))
                        is QueueMobileFragment -> findNavController().navigate(QueueMobileFragmentDirections.actionFavoritesToMovie(id = movie.id))
                        // Recommendation rows on the search page.
                        is SearchMobileFragment -> findNavController().navigate(SearchMobileFragmentDirections.actionSearchToMovie(id = movie.id))
                    }
                }
            }
            setOnLongClickListener {
                onMovieLongClick?.let { listener ->
                    listener(movie)
                    return@setOnLongClickListener true
                }
                ShowOptionsMobileDialog(context, movie).show()
                true
            }
        }
    }

    private fun bindTvClicks(root: View) {
        root.apply {
            isFocusable = true
            setOnClickListener {
                onMovieClick?.let { listener ->
                    listener(movie)
                    return@setOnClickListener
                }
                checkProviderAndRun {
                    when (context.toActivity()?.getCurrentFragment()) {
                        is HomeTvFragment -> {
                            if (movie.itemType == AppAdapter.Type.MOVIE_CONTINUE_WATCHING_TV_ITEM) {
                                findNavController().navigate(
                                    R.id.action_global_player,
                                    Bundle().apply {
                                        putString("id", movie.id)
                                        putString("title", movie.title)
                                        putString("subtitle", movie.released?.format("yyyy") ?: "")
                                        putSerializable(
                                            "videoType",
                                            Video.Type.Movie(
                                                id = movie.id,
                                                title = movie.title,
                                                releaseDate = movie.released?.format("yyyy-MM-dd") ?: "",
                                                poster = movie.poster ?: movie.banner ?: "",
                                                imdbId = movie.imdbId,
                                            )
                                        )
                                    }
                                )
                            } else {
                                findNavController().navigate(HomeTvFragmentDirections.actionHomeToMovie(id = movie.id))
                            }
                        }
                        is MoviesTvFragment -> findNavController().navigate(MoviesTvFragmentDirections.actionMoviesToMovie(id = movie.id))
                        is GenreTvFragment -> findNavController().navigate(GenreTvFragmentDirections.actionGenreToMovie(id = movie.id))
                        is SearchTvFragment -> findNavController().navigate(SearchTvFragmentDirections.actionSearchToMovie(id = movie.id))
                        is MovieTvFragment -> findNavController().navigate(MovieTvFragmentDirections.actionMovieToMovie(id = movie.id))
                        is TvShowTvFragment -> findNavController().navigate(TvShowTvFragmentDirections.actionTvShowToMovie(id = movie.id))
                        is PeopleTvFragment -> findNavController().navigate(PeopleTvFragmentDirections.actionPeopleToMovie(id = movie.id))
                        is QueueTvFragment -> findNavController().navigate(QueueTvFragmentDirections.actionFavoritesToMovie(id = movie.id))
                    }
                }
            }


            setOnLongClickListener {
                onMovieLongClick?.let { listener ->
                    listener(movie)
                    return@setOnLongClickListener true
                }
                ShowOptionsTvDialog(context, movie).show()
                true
            }
            setOnKeyListener { _, keyCode, event ->
                // Menu opens the same options as a long press on OK.
                if (keyCode != KeyEvent.KEYCODE_MENU) return@setOnKeyListener false
                if (event.action == KeyEvent.ACTION_UP) performLongClick()
                true
            }
        }
    }

    private fun displayContinueWatchingMobileItem(binding: ItemMovieContinueWatchingMobileBinding) {
        bindMobileClicks(binding.root)
        BrowseCards.attachPress(binding.root)
        bindContinueWatchingContent(
            binding.tvMovieInitial, binding.ivMoviePoster, binding.tvMovieTitle,
            binding.tvMovieInfo, binding.pbMovieProgress, binding.tvMovieRemaining,
        )
    }

    private fun displayContinueWatchingTvItem(binding: ItemMovieContinueWatchingTvBinding) {
        bindTvClicks(binding.root)
        BrowseCards.attachTvFocus(binding.root) { hasFocus ->
            (context.toActivity()?.getCurrentFragment() as? HomeTvFragment)?.let { fragment ->
                if (hasFocus) fragment.focusHero(movie) else fragment.releasePinnedBackground()
            }
        }
        bindContinueWatchingContent(
            binding.tvMovieInitial, binding.ivMoviePoster, binding.tvMovieTitle,
            binding.tvMovieInfo, binding.pbMovieProgress, binding.tvMovieRemaining,
        )
    }

    private fun bindContinueWatchingContent(
        initial: TextView,
        backdrop: ImageView,
        title: TextView,
        info: TextView,
        progressView: LinearProgressIndicator,
        remainingView: TextView,
    ) {
        initial.text = BrowseCards.initial(movie.title)
        if (!movie.banner.isNullOrBlank()) {
            backdrop.loadMovieBanner(movie) {
                centerCrop()
                transition(DrawableTransitionOptions.withCrossFade())
            }
        } else {
            backdrop.loadMoviePoster(movie) {
                centerCrop()
                transition(DrawableTransitionOptions.withCrossFade())
            }
        }
        title.text = movie.title
        info.text = BrowseCards.joinMeta(
            context,
            movie.released?.format("yyyy") ?: context.getString(R.string.movie_item_type),
            movie.quality,
        )
        val watchHistory = movie.watchHistory
        progressView.visibility = if (watchHistory != null) View.VISIBLE else View.GONE
        progressView.setProgressCompat(BrowseCards.progressPercent(watchHistory), false)
        val remaining = BrowseCards.remainingText(context, watchHistory)
        remainingView.text = remaining
        remainingView.visibility = if (remaining != null) View.VISIBLE else View.GONE
    }

    private fun displayGridMobileItem(binding: ItemMovieGridMobileBinding) {
        binding.root.apply {
            alpha = 1f
            isActivated = itemSelected
            applyMobileSelection(this)
            setOnKeyListener { _, _, event -> onMovieKey?.invoke(movie, event) ?: false }
            setOnClickListener {
                onMovieClick?.let { listener ->
                    listener(movie)
                    return@setOnClickListener
                }
                checkProviderAndRun {
                    when (context.toActivity()?.getCurrentFragment()) {
                        is GenreMobileFragment -> findNavController().navigate(GenreMobileFragmentDirections.actionGenreToMovie(id = movie.id))
                        is MoviesMobileFragment -> findNavController().navigate(MoviesMobileFragmentDirections.actionMoviesToMovie(id = movie.id))
                        is PeopleMobileFragment -> findNavController().navigate(PeopleMobileFragmentDirections.actionPeopleToMovie(id = movie.id))
                        is SearchMobileFragment -> findNavController().navigate(SearchMobileFragmentDirections.actionSearchToMovie(id = movie.id))
                        is QueueMobileFragment -> findNavController().navigate(QueueMobileFragmentDirections.actionFavoritesToMovie(id = movie.id))
                    }
                }
            }
            setOnLongClickListener {
                onMovieLongClick?.let { listener ->
                    listener(movie)
                    return@setOnLongClickListener true
                }
                ShowOptionsMobileDialog(context, movie).show()
                true
            }
        }

        BrowseCards.attachPress(binding.root)
        binding.tvMovieInitial.text = BrowseCards.initial(movie.title)
        binding.ivMoviePoster.loadMoviePoster(movie) {
            centerCrop()
            transition(DrawableTransitionOptions.withCrossFade())
        }
        bindRibbons(binding.ivMovieFavoriteRibbon, binding.ivMovieWatchedRibbon)
        BrowseCards.bindMovieRating(binding.tvMovieRating, movie)

        binding.tvMovieQuality.apply {
            text = movie.quality ?: ""
            visibility = when {
                text.isNullOrEmpty() -> View.GONE
                else -> View.VISIBLE
            }
        }

        binding.tvMovieReleasedYear.text = movie.released?.format("yyyy")
            ?: context.getString(R.string.movie_item_type)

        binding.pbMovieProgress.apply {
            val watchHistory = movie.watchHistory
            progress = BrowseCards.progressPercent(watchHistory)
            visibility = when {
                watchHistory != null -> View.VISIBLE
                else -> View.GONE
            }
        }

        binding.tvMovieTitle.text = movie.title
    }

    private fun displayGridTvItem(binding: ItemMovieGridTvBinding) {
        binding.root.apply {
            isFocusable = true
            alpha = 1f
            isActivated = itemSelected
            setOnKeyListener { _, _, event -> onMovieKey?.invoke(movie, event) ?: false }
            setOnClickListener {
                onMovieClick?.let { listener ->
                    listener(movie)
                    return@setOnClickListener
                }
                checkProviderAndRun {
                    when (context.toActivity()?.getCurrentFragment()) {
                        is HomeTvFragment -> findNavController().navigate(HomeTvFragmentDirections.actionHomeToMovie(id = movie.id))
                        is MoviesTvFragment -> findNavController().navigate(MoviesTvFragmentDirections.actionMoviesToMovie(id = movie.id))
                        is GenreTvFragment -> findNavController().navigate(GenreTvFragmentDirections.actionGenreToMovie(id = movie.id))
                        is SearchTvFragment -> findNavController().navigate(SearchTvFragmentDirections.actionSearchToMovie(id = movie.id))
                        is PeopleTvFragment -> findNavController().navigate(PeopleTvFragmentDirections.actionPeopleToMovie(id = movie.id))
                        is QueueTvFragment -> findNavController().navigate(QueueTvFragmentDirections.actionFavoritesToMovie(id = movie.id))
                    }
                }
            }

            setOnLongClickListener {
                onMovieLongClick?.let { listener ->
                    listener(movie)
                    return@setOnLongClickListener true
                }
                ShowOptionsTvDialog(context, movie).show()
                true
            }
        }
        BrowseCards.attachTvFocus(
            binding.root,
            binding.tvMovieTitle,
            binding.tvMovieReleasedYear,
            binding.tvMovieQuality,
        )
        binding.tvMovieInitial.text = BrowseCards.initial(movie.title)
        binding.ivMoviePoster.loadMoviePoster(movie) {
            centerCrop()
            transition(DrawableTransitionOptions.withCrossFade())
        }
        bindRibbons(binding.ivMovieFavoriteRibbon, binding.ivMovieWatchedRibbon)
        BrowseCards.bindMovieRating(binding.tvMovieRating, movie)
        binding.pbMovieProgress.apply {
            val watchHistory = movie.watchHistory
            progress = BrowseCards.progressPercent(watchHistory)
            visibility = when {
                watchHistory != null -> View.VISIBLE
                else -> View.GONE
            }
        }
        binding.tvMovieQuality.apply {
            text = movie.quality ?: ""
            visibility = when {
                text.isNullOrEmpty() -> View.GONE
                else -> View.VISIBLE
            }
        }
        binding.tvMovieReleasedYear.text = movie.released?.format("yyyy")
            ?: context.getString(R.string.movie_item_type)
        binding.tvMovieTitle.text = movie.title
    }

    private fun applyMobileSelection(view: View) {
        if (itemSelected) {
            val width = (4 * context.resources.displayMetrics.density).toInt()
            view.background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                setStroke(width, ContextCompat.getColor(context, R.color.favorite_selected))
            }
            view.setPadding(width, width, width, width)
        } else {
            view.background = null
            view.setPadding(0, 0, 0, 0)
        }
    }
    private fun bindRibbons(favoriteRibbon: View, watchedRibbon: View) {
        favoriteRibbon.visibility = if (movie.isFavorite) View.VISIBLE else View.GONE
        watchedRibbon.visibility = if (movie.isWatched) View.VISIBLE else View.GONE

        ribbonStateJob?.cancel()
        val boundMovieId = movie.id
        val lifecycleOwner = itemView.findViewTreeLifecycleOwner()
            ?: context.toActivity()
            ?: return

        ribbonStateJob = lifecycleOwner.lifecycleScope.launch {
            database.movieDao().getByIdAsFlow(boundMovieId).collect { persistedMovie ->
                if (movie.id != boundMovieId || persistedMovie == null) return@collect
                favoriteRibbon.visibility = if (persistedMovie.isFavorite) View.VISIBLE else View.GONE
                watchedRibbon.visibility = if (persistedMovie.isWatched) View.VISIBLE else View.GONE
            }
        }
    }

    private fun displaySwiperMobileItem(binding: ItemCategorySwiperMobileBinding) {
        BrowseCards.bindHeroMask(binding.mflSwiper, binding.clSwiperContent)
        binding.ivSwiperBackground.loadMovieCardArtwork(movie) {
            centerCrop()
            transition(DrawableTransitionOptions.withCrossFade())
        }

        binding.tvSwiperTitle.text = movie.title

        binding.tvSwiperTvShowLastEpisode.text = context.getString(R.string.movie_item_type)

        binding.tvSwiperQuality.apply {
            text = movie.quality
            visibility = when {
                text.isNullOrEmpty() -> View.GONE
                else -> View.VISIBLE
            }
        }

        binding.tvSwiperReleased.apply {
            text = movie.released?.format("yyyy")
            visibility = when {
                text.isNullOrEmpty() -> View.GONE
                else -> View.VISIBLE
            }
        }

        binding.tvSwiperRating.apply {
            text = BrowseCards.ratingValue(movie.rating)
            visibility = when {
                text.isNullOrEmpty() -> View.GONE
                else -> View.VISIBLE
            }
        }

        binding.ivSwiperRatingIcon.visibility = binding.tvSwiperRating.visibility

        binding.tvSwiperOverview.text = movie.overview

        val openDetails = {
            binding.root.findNavController().navigate(
                HomeMobileFragmentDirections.actionHomeToMovie(
                    id = movie.id,
                )
            )
        }
        binding.root.setOnClickListener { openDetails() }
        binding.root.setOnLongClickListener {
            ShowOptionsMobileDialog(context, movie).show()
            true
        }
        binding.btnSwiperMoreInfo.visibility = View.GONE
        binding.btnSwiperWatchNow.apply {
            setText(R.string.browse_play)
            setOnClickListener {
                // Same path as a Continue Watching movie card: details, then the player on top.
                val navController = findNavController()
                navController.navigate(HomeMobileFragmentDirections.actionHomeToMovie(id = movie.id))
                navController.navigate(
                    MovieMobileFragmentDirections.actionMovieToPlayer(
                        id = movie.id,
                        title = movie.title,
                        subtitle = movie.released?.format("yyyy") ?: "",
                        videoType = Video.Type.Movie(
                            id = movie.id,
                            title = movie.title,
                            releaseDate = movie.released?.format("yyyy-MM-dd") ?: "",
                            poster = movie.poster ?: "",
                            imdbId = movie.imdbId,
                        ),
                    )
                )
            }
        }

        binding.pbSwiperProgress.apply {
            val watchHistory = movie.watchHistory
            progress = BrowseCards.progressPercent(watchHistory)
            visibility = when {
                watchHistory != null -> View.VISIBLE
                else -> View.GONE
            }
        }
    }


    private fun movieVideoType() = Video.Type.Movie(
        id = movie.id,
        title = movie.title,
        releaseDate = movie.released?.format("yyyy-MM-dd") ?: "",
        poster = movie.poster ?: movie.banner ?: "",
        imdbId = movie.imdbId,
    )

    /** Shared header metadata: title, year · runtime · rating · quality, overview. */
    private fun bindMovieMeta(
        title: TextView,
        released: TextView,
        runtime: TextView,
        rating: TextView,
        quality: TextView,
        overview: TextView,
    ) {
        title.text = movie.title
        released.setTextOrGone(movie.released?.format("yyyy"))
        runtime.setTextOrGone(
            DetailsUi.runtime(context, movie.runtime, R.string.movie_runtime_hours_minutes, R.string.movie_runtime_minutes)
        )
        rating.text = DetailsUi.rating(movie.rating)
        (rating.parent as? View)?.contentDescription =
            context.getString(R.string.details_rating_content_description, rating.text)
        quality.setTextOrGone(movie.quality)
        overview.text = movie.overview
        bindCinemaOnly(quality)
    }

    /** "In cinemas only" next to the rating while no home release exists: any stream is a camera copy. */
    private fun bindCinemaOnly(quality: TextView) {
        quality.tag = movie.id
        if (!CinemaStatus.applies() || !CinemaStatus.worthChecking(movie.released?.timeInMillis)) return
        fun show() {
            quality.text = context.getString(R.string.movie_cinema_only)
            quality.visibility = View.VISIBLE
        }
        when (CinemaStatus.peek(movie.id)) {
            true -> show()
            null -> CinemaStatus.request(movie.id) { onlyInCinemas ->
                if (onlyInCinemas) quality.post { if (quality.tag == movie.id) show() }
            }
            false -> Unit
        }
    }

    private fun isUnreleased() = ReleaseStatus.isUnreleased(movie.released)

    /**
     * A film that is not out yet cannot be played, so the main button says when it comes out and
     * tapping it puts the film in the Queue (where the Coming up calendar lists it).
     */
    private fun bindUnreleased(button: MaterialButton, favorite: MaterialButton) {
        val released = movie.released
        if (released == null || !isUnreleased()) {
            button.alpha = 1f
            return
        }
        button.text = context.getString(R.string.movie_releases_on, ReleaseStatus.shortDate(context, released))
        button.alpha = 0.78f
        button.setOnClickListener {
            if (!movie.isFavorite) {
                favorite.performClick()
                android.widget.Toast.makeText(context, R.string.movie_not_out_added, android.widget.Toast.LENGTH_LONG).show()
            } else {
                android.widget.Toast.makeText(
                    context,
                    context.getString(R.string.movie_not_out_info, ReleaseStatus.longDate(context, released)),
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun bindMovieProgress(progressBar: LinearProgressIndicator) {
        progressBar.isVisible = movie.watchHistory != null
        progressBar.progress = DetailsUi.progressPercent(movie.watchHistory) ?: 0
    }

    private fun bindMovieFavorite(button: MaterialButton) {
        button.setOnClickListener {
            // The coroutine below sets the real state; undo the button's own toggle meanwhile.
            button.isChecked = movie.isFavorite
            checkProviderAndRun {
                itemView.findViewTreeLifecycleOwner()?.lifecycleScope?.launch(Dispatchers.IO) {
                    val dao = database.movieDao()
                    val current = dao.getById(movie.id)?.isFavorite ?: false
                    val newValue = !current
                    val resolvedMovie = ArtworkRepair.resolveMovieForFavorite(context, movie, newValue)

                    dao.upsertFavorite(resolvedMovie, newValue)
                    FavoriteSync.movie(context, resolvedMovie, newValue)

                    withContext(Dispatchers.Main) {
                        movie.poster = resolvedMovie.poster
                        movie.banner = resolvedMovie.banner
                        movie.isFavorite = newValue
                        button.isChecked = newValue
                    }
                }
            }
        }
        button.isChecked = movie.isFavorite
    }

    /** Same action as "Mark as (un)watched" in the options sheet. */
    private fun bindMovieWatched(button: MaterialButton) {
        button.isChecked = movie.isWatched
        button.setOnClickListener {
            val watched = !movie.isWatched
            movie.isWatched = watched
            button.isChecked = watched
            WatchStateActions.launch(context) {
                WatchStateActions.setWatched(context, movie, watched)
            }
        }
    }

    private fun bindMovieTrailer(button: View, logPrefix: String) {
        val trailer = movie.trailer
        button.setOnClickListener {
            if (trailer != null) handleTrailerClick(trailer, logPrefix)
        }
        button.isVisible = trailer != null
    }

    private fun displayMovieMobile(binding: ContentMovieMobileBinding) {
        binding.ivMoviePoster.run {
            loadMoviePoster(movie) {
                transition(DrawableTransitionOptions.withCrossFade())
            }
            visibility = when {
                movie.poster.isNullOrEmpty() -> View.GONE
                else -> View.VISIBLE
            }
        }

        bindMovieMeta(
            binding.tvMovieTitle,
            binding.tvMovieReleased,
            binding.tvMovieRuntime,
            binding.tvMovieRating,
            binding.tvMovieQuality,
            binding.tvMovieOverview,
        )
        DetailsUi.bindGenreChips(binding.cgMovieGenres, binding.hsvMovieGenres, movie.genres)

        binding.btnMovieWatchNow.apply {
            text = DetailsUi.playLabel(context, movie.watchHistory)
            bindUnreleased(this, binding.btnMovieFavorite)
            if (isUnreleased()) return@apply
            setOnClickListener {
                // Este botón ya navega al reproductor, no a otra página de detalles.
                // Generalmente non necesita el cambio de proveedor, pero lo añadimos por seguridad.
                checkProviderAndRun {
                    findNavController().navigate(MovieMobileFragmentDirections.actionMovieToPlayer(
                        id = movie.id,
                        title = movie.title,
                        subtitle = movie.released?.format("yyyy") ?: "",
                        videoType = movieVideoType(),
                    ))
                }
            }
        }
        bindMovieProgress(binding.pbMovieProgress)

        val activity = context.toActivity() as? androidx.fragment.app.FragmentActivity
        val canPlayOnTv = activity != null && PlayOnTv.isAvailable(activity)

        binding.btnMoviePlayOptions.setOnClickListener {
            val actions = buildList {
                movie.trailer?.let { trailer ->
                    add(DetailsUi.MenuAction(context.getString(R.string.movie_trailer)) {
                        handleTrailerClick(trailer, "MovieMobile")
                    })
                }
                if (canPlayOnTv) {
                    add(DetailsUi.MenuAction(context.getString(R.string.sync_play_on_tv)) {
                        activity?.let { PlayOnTv.play(it, movie) }
                    })
                }
                add(DetailsUi.MenuAction(context.getString(R.string.details_more_options)) {
                    ShowOptionsMobileDialog(context, movie).show()
                })
            }
            DetailsUi.showSplitMenu(binding.btnMoviePlayOptions, binding.sbMoviePlay, actions)
        }

        bindMovieFavorite(binding.btnMovieFavorite)
        bindMovieWatched(binding.btnMovieWatched)
        bindMovieTrailer(binding.btnMovieTrailer, "MovieMobile")

        binding.btnMoviePlayOnTv.apply {
            visibility = if (canPlayOnTv) View.VISIBLE else View.GONE
            setOnClickListener { activity?.let { PlayOnTv.play(it, movie) } }
        }

        // Offline download (phone only; the button hides itself on TV).
        binding.btnMovieDownload.apply {
            showLabel = false
            beforeStart = { SyncProviders.switchTo(movie.providerName) }
            bind((movie.providerName ?: UserPreferences.currentProvider?.name)?.let { providerName ->
                DownloadStarter.Target(
                    providerName = providerName,
                    videoType = movieVideoType(),
                    title = movie.title,
                    subtitle = movie.released?.format("yyyy") ?: "",
                    poster = movie.poster ?: movie.banner,
                )
            })
        }
        binding.flMovieDownload.isVisible = binding.btnMovieDownload.isVisible
    }

    private fun displayMovieTv(binding: ContentMovieTvBinding) {
        bindMovieMeta(
            binding.tvMovieTitle,
            binding.tvMovieReleased,
            binding.tvMovieRuntime,
            binding.tvMovieRating,
            binding.tvMovieQuality,
            binding.tvMovieOverview,
        )
        binding.tvMovieGenres.setTextOrGone(movie.genres.joinToString("  ·  ") { it.name })

        binding.btnMovieWatchNow.apply {
            text = DetailsUi.playLabel(context, movie.watchHistory)
            bindUnreleased(this, binding.btnMovieFavorite)
            if (isUnreleased()) return@apply
            setOnClickListener {
                checkProviderAndRun {
                    findNavController().navigate(MovieTvFragmentDirections.actionMovieToPlayer(
                        id = movie.id,
                        title = movie.title,
                        subtitle = movie.released?.format("yyyy") ?: "",
                        videoType = movieVideoType(),
                    ))
                }
            }
        }
        bindMovieProgress(binding.pbMovieProgress)

        bindMovieTrailer(binding.btnMovieTrailer, "MovieTv")
        bindMovieFavorite(binding.btnMovieFavorite)
        bindMovieWatched(binding.btnMovieWatched)

        DetailsUi.attachTvButtonFocus(
            binding.btnMovieWatchNow,
            binding.btnMovieTrailer,
            binding.btnMovieFavorite,
            binding.btnMovieWatched,
        )
    }

    private fun displayCastMobile(binding: ContentMovieCastMobileBinding) {
        binding.rvMovieCast.apply {
            adapter = AppAdapter().apply {
                submitList(movie.cast.onEach {
                    it.itemType = AppAdapter.Type.PEOPLE_MOBILE_ITEM
                })
            }
            if (itemDecorationCount == 0) {
                addItemDecoration(SpacingItemDecoration(20.dp(context)))
            }
        }
    }

    private fun displayCastTv(binding: ContentMovieCastTvBinding) {
        binding.hgvMovieCast.apply {
            setRowHeight(ViewGroup.LayoutParams.WRAP_CONTENT)
            adapter = AppAdapter().apply {
                submitList(movie.cast.onEach {
                    it.itemType = AppAdapter.Type.PEOPLE_TV_ITEM
                })
            }
            setItemSpacing(80)
        }
    }

    private fun displayDirectorsMobile(binding: ContentMovieDirectorsMobileBinding) {
        binding.rvMovieDirectors.text = movie.directors.joinToString (separator =", ") { it.name }
    }
    private fun displayDirectorsTv(binding: ContentMovieDirectorsTvBinding) {
        binding.rvMovieDirectors.text = movie.directors.joinToString (separator =", ") { it.name }
    }

    private fun displayRecommendationsMobile(binding: ContentMovieRecommendationsMobileBinding) {
        binding.rvMovieRecommendations.apply {
            adapter = AppAdapter().apply {
                submitList(movie.recommendations.onEach {
                    when (it) {
                        is Movie -> it.itemType = AppAdapter.Type.MOVIE_MOBILE_ITEM
                        is TvShow -> it.itemType = AppAdapter.Type.TV_SHOW_MOBILE_ITEM
                    }
                })
            }
            if (itemDecorationCount == 0) {
                addItemDecoration(SpacingItemDecoration(10.dp(context)))
            }
        }
    }

    private fun displayRecommendationsTv(binding: ContentMovieRecommendationsTvBinding) {
        binding.hgvMovieRecommendations.apply {
            setRowHeight(ViewGroup.LayoutParams.WRAP_CONTENT)
            adapter = AppAdapter().apply {
                submitList(movie.recommendations.onEach {
                    when (it) {
                        is Movie -> it.itemType = AppAdapter.Type.MOVIE_TV_ITEM
                        is TvShow -> it.itemType = AppAdapter.Type.TV_SHOW_TV_ITEM
                    }
                })
            }
            setItemSpacing(20)
        }
    }
}
