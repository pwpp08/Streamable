package com.streamflixreborn.streamflix.fragments.player

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.ui.SubtitleView
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.database.AppDatabase
import com.streamflixreborn.streamflix.databinding.FragmentPlayerMobileBinding
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.Season
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.models.WatchItem
import com.streamflixreborn.streamflix.utils.MediaServer
import com.streamflixreborn.streamflix.utils.IdleDimHost
import com.streamflixreborn.streamflix.utils.UserPreferences
import com.streamflixreborn.streamflix.utils.UserDataCache
import com.streamflixreborn.streamflix.utils.dp
import com.streamflixreborn.streamflix.utils.getFileName
import com.streamflixreborn.streamflix.utils.next
import com.streamflixreborn.streamflix.utils.plus
import com.streamflixreborn.streamflix.utils.setMediaServerId
import com.streamflixreborn.streamflix.utils.setMediaServers
import com.streamflixreborn.streamflix.utils.toSubtitleMimeType
import com.streamflixreborn.streamflix.utils.viewModelsFactory
import kotlinx.coroutines.launch
import java.util.Calendar
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import androidx.core.net.toUri
import androidx.lifecycle.repeatOnLifecycle
import com.streamflixreborn.streamflix.fragments.player.settings.PlayerSettingsView
import java.util.Base64 
import java.io.File
import java.io.FileOutputStream
import androidx.core.content.FileProvider
import androidx.navigation.NavOptions
import com.streamflixreborn.streamflix.utils.EpisodeManager
import com.streamflixreborn.streamflix.utils.UserDataCache.toEpisode
import com.streamflixreborn.streamflix.utils.UserDataCache.toMovie
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import com.streamflixreborn.streamflix.fragments.player.engine.NextUiBinder
import com.streamflixreborn.streamflix.fragments.player.engine.OverlayUi
import com.streamflixreborn.streamflix.nextui.NextUiCallbacks
import com.streamflixreborn.streamflix.nextui.model.VideoContentScale
import com.streamflixreborn.streamflix.fragments.player.engine.PlaybackAnalytics
import com.streamflixreborn.streamflix.fragments.player.engine.PlayerFactory
import com.streamflixreborn.streamflix.fragments.player.engine.ProgressSaver
import com.streamflixreborn.streamflix.downloads.OfflineMediaProvider
import com.streamflixreborn.streamflix.fragments.player.engine.SkipSegmentController
import com.streamflixreborn.streamflix.fragments.player.engine.StreamDataSourceFactory
import com.streamflixreborn.streamflix.fragments.player.engine.SubtitleStyler
import com.streamflixreborn.streamflix.fragments.player.engine.PlaybackSnapshot
import com.streamflixreborn.streamflix.fragments.player.engine.TrackPreferences
import com.streamflixreborn.streamflix.fragments.player.engine.UpNextController
import com.streamflixreborn.streamflix.sync.CloudSyncHooks
import com.streamflixreborn.streamflix.utils.IntroDbClient
import com.streamflixreborn.streamflix.utils.LanguageMatcher
import java.util.Locale

class PlayerMobileFragment : Fragment() {
    companion object {
        private const val NEXT_EPISODE_PREFETCH_THRESHOLD_MS = 60_000L
        private const val NEXT_EPISODE_OVERLAY_MIN_THRESHOLD_MS = 30_000L
    }

    private var _binding: FragmentPlayerMobileBinding? = null
    private val binding get() = _binding!!
    private var isSetupDone = false

    private val args by navArgs<PlayerMobileFragmentArgs>()
    private val database by lazy { AppDatabase.getInstance(requireContext()) }
    private val viewModel by viewModelsFactory { PlayerViewModel(args.videoType, args.id) }

    private lateinit var player: ExoPlayer
    private lateinit var dataSources: StreamDataSourceFactory
    private lateinit var mediaSession: MediaSession
    private var analytics: PlaybackAnalytics? = null
    private var autoSubtitles: TrackPreferences.AutoSubtitles? = null
    private val progressSaver by lazy { ProgressSaver(requireContext(), database) { args.videoType } }
    private val skipSegments by lazy { SkipSegmentController(viewLifecycleOwner.lifecycleScope, skipSegmentsUi) }
    private val upNext by lazy { UpNextController(upNextUi) }

    /** Position to restore after a server switch (failover or manual). */
    private var pendingResumeMs: Long? = null

    /** Subtitle/audio/speed/sideloaded subtitles of the stream being replaced (see PlaybackSnapshot). */
    private var pendingSnapshot: PlaybackSnapshot? = null
    private var stickyReportedServerId: String? = null
    private lateinit var progressHandler: android.os.Handler
    private lateinit var progressRunnable: Runnable
    private lateinit var nextUi: NextUiBinder

    private var servers = listOf<Video.Server>()

    private var currentVideo: Video? = null
    private var currentServer: Video.Server? = null
    private var isIgnoringPip = false
    private var nextEpisodePrefetchTargetId: String? = null
    private var nextEpisodePrefetchJob: Job? = null

    private val chooserReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                val clickedComponent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent?.getParcelableExtra(Intent.EXTRA_CHOSEN_COMPONENT, android.content.ComponentName::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent?.getParcelableExtra(Intent.EXTRA_CHOSEN_COMPONENT)
                }
                Log.i("ExternalPlayer", "Mobile - App selezionata: ${clickedComponent?.packageName ?: "Sconosciuta"}")
            }
        }
    }

    private val pickLocalSubtitle = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        requireContext().contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )

        val fileName = uri.getFileName(requireContext()) ?: uri.toString()

        val currentPosition = player.currentPosition
        val currentSubtitleConfigurations =
            player.currentMediaItem?.localConfiguration?.subtitleConfigurations?.map {
                MediaItem.SubtitleConfiguration.Builder(it.uri)
                    .setMimeType(it.mimeType)
                    .setLabel(it.label)
                    .setLanguage(it.language)
                    .setSelectionFlags(0)
                    .build()
            } ?: listOf()
        player.setMediaItem(
            MediaItem.Builder()
                .setUri(player.currentMediaItem?.localConfiguration?.uri)
                .setMimeType(player.currentMediaItem?.localConfiguration?.mimeType)
                .setSubtitleConfigurations(
                    currentSubtitleConfigurations
                            + MediaItem.SubtitleConfiguration.Builder(uri)
                        .setMimeType(fileName.toSubtitleMimeType())
                        .setLabel(fileName)
                        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                        .build()
                )
                .setMediaMetadata(player.mediaMetadata)
                .build()
        )
        TrackPreferences.preferSideloadedSubtitle(player)
        player.seekTo(currentPosition)
        player.play()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPlayerMobileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onResume() {
        super.onResume()
        if (!isSetupDone) {
            requireActivity().requestedOrientation =
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            
            val window = requireActivity().window
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            
            val insetsController = WindowInsetsControllerCompat(window, window.decorView)
            insetsController.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
            isSetupDone = true
        }
        isIgnoringPip = false
        if (::player.isInitialized) {
            // Resume playback after returning from bypass or any pause
            if (!player.isPlaying) {
                player.play()
            }
        }
        
        try {
            val filter = IntentFilter("ACTION_PLAYER_CHOSEN")
            ContextCompat.registerReceiver(
                requireContext(),
                chooserReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        } catch (ignored: Exception) {}
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupNextUi()
        initializePlayer(false)
        initializeVideo()
        // Stato Video
        viewLifecycleOwner.lifecycleScope.launch { 
            viewModel.state.flowWithLifecycle(lifecycle, Lifecycle.State.CREATED).collect { state ->
                when (state) {
                    PlayerViewModel.State.LoadingServers -> {}
                    is PlayerViewModel.State.SuccessLoadingServers -> {
                        servers = state.servers
                        nextUi.setServers(servers, currentServer)
                        if (servers.isEmpty()) {
                            Toast.makeText(requireContext(), unavailableMessage("No servers found for this content."), Toast.LENGTH_LONG).show()
                            findNavController().navigateUp()
                            return@collect
                        }

                        player.playlistMetadata = MediaMetadata.Builder()
                            .setTitle(state.toString())
                            .setMediaServers(state.servers.map {
                                MediaServer(
                                    id = it.id,
                                    name = it.name,
                                )
                            })
                            .build()
                        binding.settings.setOnServerSelectedListener { server ->
                            val selected = state.servers.find { server.id == it.id } ?: return@setOnServerSelectedListener
                            pendingResumeMs = player.currentPosition.takeIf { it > 0 }
                            viewModel.getVideo(selected)
                        }
                        viewModel.startPlayback(state.servers, args.preferredServerName)
                    }

                    is PlayerViewModel.State.FailedLoadingServers -> {
                        Toast.makeText(
                            requireContext(),
                            state.error.message ?: "",
                            Toast.LENGTH_LONG
                        ).show()
                        findNavController().navigateUp()
                    }

                    is PlayerViewModel.State.LoadingVideo -> {
                        if (pendingSnapshot == null) pendingSnapshot = captureSnapshot()
                        player.setMediaItem(
                            MediaItem.Builder()
                                .setUri("".toUri())
                                .setMediaMetadata(
                                    MediaMetadata.Builder()
                                        .setMediaServerId(state.server.id)
                                        .build()
                                )
                                .build()
                        )
                    }

                    is PlayerViewModel.State.SuccessLoadingVideo -> {
                        PlayerSettingsView.Settings.ExtraBuffering.init(state.video.extraBuffering)
                        PlayerSettingsView.Settings.SoftwareDecoder.init(false)
                        displayVideo(state.video, state.server, failover = state.failover)
                    }

                    is PlayerViewModel.State.FailedLoadingVideo -> {
                        // The ViewModel already tried every server.
                        Toast.makeText(
                            requireContext(),
                            unavailableMessage(getString(R.string.player_all_servers_failed)),
                            Toast.LENGTH_LONG
                        ).show()
                        findNavController().navigateUp()
                    }
                }
            }
        }

        // Stato Sottotitoli
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.subtitleState.flowWithLifecycle(lifecycle, Lifecycle.State.CREATED).collect { state ->
                when (state) {
                    PlayerViewModel.SubtitleState.Loading -> {}
                    is PlayerViewModel.SubtitleState.SuccessOpenSubtitles -> {
                        binding.settings.openSubtitles = state.subtitles
                    }
                    is PlayerViewModel.SubtitleState.FailedOpenSubtitles -> {}

                    PlayerViewModel.SubtitleState.DownloadingOpenSubtitle -> {}
                    is PlayerViewModel.SubtitleState.SuccessDownloadingOpenSubtitle -> {
                        val fileName = state.uri.getFileName(requireContext()) ?: state.uri.toString()
                        val currentPosition = player.currentPosition
                        val currentSubtitleConfigurations = player.currentMediaItem?.localConfiguration?.subtitleConfigurations?.map {
                            MediaItem.SubtitleConfiguration.Builder(it.uri)
                                .setMimeType(it.mimeType)
                                .setLabel(it.label)
                                .setLanguage(it.language)
                                .setSelectionFlags(0)
                                .build()
                        } ?: listOf()
                        player.setMediaItem(
                            MediaItem.Builder()
                                .setUri(player.currentMediaItem?.localConfiguration?.uri)
                                .setMimeType(player.currentMediaItem?.localConfiguration?.mimeType)
                                .setSubtitleConfigurations(
                                    currentSubtitleConfigurations + MediaItem.SubtitleConfiguration.Builder(state.uri)
                                        .setMimeType(fileName.toSubtitleMimeType())
                                        .setLabel(fileName)
                                        .setLanguage(state.subtitle.languageName)
                                        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                                        .build()
                                )
                                .setMediaMetadata(player.mediaMetadata)
                                .build()
                        )
                        UserPreferences.subtitleName = (state.subtitle.languageName ?: fileName).substringBefore(" ")
                        TrackPreferences.preferSideloadedSubtitle(player)
                        player.seekTo(currentPosition)
                        player.play()
                    }
                    is PlayerViewModel.SubtitleState.FailedDownloadingOpenSubtitle -> {
                        Toast.makeText(requireContext(), "${state.subtitle.subFileName}: ${state.error.message}", Toast.LENGTH_LONG).show()
                    }

                    is PlayerViewModel.SubtitleState.SuccessSubDLSubtitles -> {
                        binding.settings.subDLSubtitles = state.subtitles
                    }
                    is PlayerViewModel.SubtitleState.FailedSubDLSubtitles -> {}

                    PlayerViewModel.SubtitleState.DownloadingSubDLSubtitle -> {}
                    is PlayerViewModel.SubtitleState.SuccessDownloadingSubDLSubtitle -> {
                        val fileName = state.uri.getFileName(requireContext()) ?: state.uri.toString()
                        val currentPosition = player.currentPosition
                        val currentSubtitleConfigurations = player.currentMediaItem?.localConfiguration?.subtitleConfigurations?.map {
                            MediaItem.SubtitleConfiguration.Builder(it.uri)
                                .setMimeType(it.mimeType)
                                .setLabel(it.label)
                                .setLanguage(it.language)
                                .setSelectionFlags(0)
                                .build()
                        } ?: listOf()
                        player.setMediaItem(
                            MediaItem.Builder()
                                .setUri(player.currentMediaItem?.localConfiguration?.uri)
                                .setMimeType(player.currentMediaItem?.localConfiguration?.mimeType)
                                .setSubtitleConfigurations(
                                    currentSubtitleConfigurations + MediaItem.SubtitleConfiguration.Builder(state.uri)
                                        .setMimeType(fileName.toSubtitleMimeType())
                                        .setLabel(state.subtitle.releaseName ?: state.subtitle.name ?: fileName)
                                        .setLanguage(state.subtitle.lang ?: state.subtitle.language ?: "Unknown")
                                        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                                        .build()
                                )
                                .setMediaMetadata(player.mediaMetadata)
                                .build()
                        )
                        UserPreferences.subtitleName = (state.subtitle.releaseName ?: state.subtitle.name ?: fileName).substringBefore(" ")
                        TrackPreferences.preferSideloadedSubtitle(player)
                        player.seekTo(currentPosition)
                        player.play()
                    }
                    is PlayerViewModel.SubtitleState.FailedDownloadingSubDLSubtitle -> {
                        Toast.makeText(requireContext(), "${state.subtitle.name}: ${state.error.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
                viewLifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    viewModel.playPreviousOrNextEpisode.collect { nextEpisode ->
                    releasePlayer()
                    isSetupDone = false
                    val action = PlayerMobileFragmentDirections
                        .actionPlayerMobileFragmentSelf(
                            id = nextEpisode.id,
                            videoType = nextEpisode,
                            title = nextEpisode.tvShow.title,
                            subtitle = "S${nextEpisode.season.number} E${nextEpisode.number}  •  ${nextEpisode.title}",
                            preferredServerName = currentServer?.name,
                        )

                    hideNextEpisodeOverlay()
                    findNavController().navigate(
                        action,
                        NavOptions.Builder()
                            .setPopUpTo(
                                findNavController().currentDestination?.id ?: return@collect, true
                            )
                            .setLaunchSingleTop(false) 
                            .build()
                    )
                }
            }
        }


    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean) {
        if (_binding != null) binding.nextUi.model.isInPictureInPictureMode = isInPictureInPictureMode
        super.onPictureInPictureModeChanged(isInPictureInPictureMode)
    }

    fun onUserLeaveHint() {
        if (!isIgnoringPip && ::player.isInitialized && player.isPlaying) {
            enterPIPMode()
        }
    }

    override fun onStop() {
        super.onStop()
        if (::player.isInitialized) {
            player.pause()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        (activity as? IdleDimHost)?.idleDimmer?.setPlaying(false)
        nextEpisodePrefetchJob?.cancel()
        progressSaver.release()
        val window = requireActivity().window
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
        }
        WindowCompat.getInsetsController(
            window,
            window.decorView
        ).run {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            show(WindowInsetsCompat.Type.systemBars())
        }
        requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        releasePlayer()
        try {
            requireContext().unregisterReceiver(chooserReceiver)
        } catch (ignored: Exception) {}
        _binding = null
        isSetupDone = false
    }

    fun onBackPressed(): Boolean = when {
        binding.settings.isVisible -> binding.settings.onBackPressed()
        binding.nextUi.handleBack() -> true
        else -> false
    }

    private fun unavailableMessage(fallback: String): String {
        val providerName = UserPreferences.currentProvider?.name ?: ""
        val isTmdb = providerName.contains("TMDb", ignoreCase = true)
        val isAD = providerName.contains("AfterDark", ignoreCase = true)
        if (!isTmdb && !isAD) return fallback
        if (!isTmdb) return getString(R.string.player_retry_later_message)
        val langCode = providerName.substringAfter("(").substringBefore(")")
        val langDisplayName = Locale.forLanguageTag(langCode).getDisplayLanguage(Locale.getDefault())
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
        return getString(R.string.player_not_available_lang_message, langDisplayName)
    }

    private val skipSegmentsUi = object : SkipSegmentController.Ui {
        override fun showSkipButton(segment: IntroDbClient.Segment) {
            if (_binding == null) return
            nextUi.skipIsFallback = false
            nextUi.showSkip(getString(OverlayUi.skipLabel(segment)))
        }

        override fun hideSkipButton() {
            if (_binding == null) return
            nextUi.showSkip(null)
        }

        override fun showAutoSkipped(segment: IntroDbClient.Segment) {
            showPlayerMessage(getString(OverlayUi.skippedLabel(segment)))
        }

        override fun seekTo(positionMs: Long) {
            player.seekTo(positionMs)
        }
    }

    private val upNextUi = object : UpNextController.Ui {
        override fun nextEpisode(): Video.Type.Episode? = EpisodeManager.peekNextEpisode()

        override fun showUpNext(next: Video.Type.Episode, secondsLeft: Int?) {
            if (_binding == null) return
            nextUi.showUpNext(next, secondsLeft)
        }

        override fun hideUpNext() = hideNextEpisodeOverlay()

        override fun playNext() {
            // Pausing first saves the progress through the normal pause path.
            if (::player.isInitialized && player.isPlaying) player.pause()
            playNextEpisodeAcrossSeasons()
        }

        override fun prepareNext() {
            val current = currentVideoTypeForUi() as? Video.Type.Episode ?: return
            ensureNextEpisodePrepared(current)
        }
    }

    /** The NextPlayer controls, wired to this fragment. */
    private fun setupNextUi() {
        nextUi = NextUiBinder(binding.nextUi, binding.settings, isTv = false)
        binding.nextUi.callbacks = object : NextUiCallbacks() {
            override fun onBack() {
                findNavController().navigateUp()
            }

            override fun onServers() = nextUi.showServers()
            override fun onQuality() = nextUi.showQuality()
            override fun onAudio() = nextUi.showAudio()
            override fun onSubtitles() = nextUi.showSubtitles()
            override fun onMore() = binding.settings.show()
            override fun onExternalPlayer() = openExternalPlayer()
            override fun onPreviousEpisode() = goToEpisode(EpisodeManager::hasPreviousEpisode, viewModel::playPreviousEpisode)
            override fun onNextEpisode() = goToEpisode(EpisodeManager::hasNextEpisode, ::playNextEpisodeAcrossSeasons)

            override fun onSkipSegment() {
                if (nextUi.skipIsFallback) {
                    player.seekTo(player.currentPosition + 85000)
                    nextUi.skipIsFallback = false
                    nextUi.showSkip(null)
                } else {
                    skipSegments.skip(player.duration)
                }
            }

            override fun onSkipDismiss() {
                if (nextUi.skipIsFallback) {
                    nextUi.skipIsFallback = false
                    nextUi.showSkip(null)
                } else {
                    skipSegments.dismiss()
                }
            }

            override fun onUpNextPlay() {
                if (!upNext.launchNext()) playNextEpisodeAcrossSeasons()
            }

            override fun onUpNextCancel() {
                upNext.dismiss()
            }

            override fun onPictureInPicture() = enterPIPMode()

            override fun onVideoContentScaleChanged(scale: VideoContentScale) {
                UserPreferences.playerResize = when (scale) {
                    VideoContentScale.STRETCH -> UserPreferences.PlayerResize.Fill
                    VideoContentScale.CROP -> UserPreferences.PlayerResize.Zoom
                    VideoContentScale.BEST_FIT -> UserPreferences.PlayerResize.Fit
                }
                binding.pvPlayer.resizeMode = UserPreferences.playerResize.resizeMode
            }

            override fun onVideoTransform(scale: VideoContentScale, zoom: Float, offsetX: Float, offsetY: Float) {
                binding.pvPlayer.videoSurfaceView?.apply {
                    scaleX = zoom
                    scaleY = zoom
                    translationX = offsetX
                    translationY = offsetY
                }
            }
        }
        binding.settings.onHidden = { nextUi.refreshGestureSetting() }
    }

    private fun showPlayerMessage(message: String) {
        if (_binding == null) return
        nextUi.showMessage(message)
    }


    private fun initializeVideo() {
        WindowCompat.getInsetsController(
            requireActivity().window,
            requireActivity().window.decorView
        ).run {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        when (val type = args.videoType) {
            is Video.Type.Episode -> {
                nextEpisodePrefetchTargetId = null
                if (EpisodeManager.listIsEmpty(type)) {
                    EpisodeManager.clearEpisodes()
                    lifecycleScope.launch(Dispatchers.IO) {
                        EpisodeManager.addEpisodesFromDb(type, database)
                        withContext(Dispatchers.Main) {
                            EpisodeManager.setCurrentEpisode(type)
                            updatePlayerHeader(type)
                            setupEpisodeNavigationButtons()
                            refreshEpisodeNavigation(type)
                        }
                    }
                } else {
                    EpisodeManager.setCurrentEpisode(type)
                    setupEpisodeNavigationButtons()
                    refreshEpisodeNavigation(type)
                }
            }
            is Video.Type.Movie -> {
                nextEpisodePrefetchTargetId = null
                EpisodeManager.clearEpisodes()
                hideNextEpisodeOverlay()
            }
        }


        binding.settings.onSubtitlesClicked = {
            viewModel.getSubtitles(args.videoType)
        }
        binding.settings.setOnExtraBufferingSelectedListener {
            displayVideo(
                currentVideo ?: return@setOnExtraBufferingSelectedListener,
                currentServer ?: return@setOnExtraBufferingSelectedListener
            )
        }
        binding.settings.setOnSoftwareDecoderSelectedListener { useSoftware ->
            currentSoftwareDecoder = useSoftware
            displayVideo(
                currentVideo ?: return@setOnSoftwareDecoderSelectedListener,
                currentServer ?: return@setOnSoftwareDecoderSelectedListener
            )
        }
        binding.pvPlayer.resizeMode = UserPreferences.playerResize.resizeMode
        SubtitleStyler.apply(binding.pvPlayer.subtitleView)
        upNext.reset()
        skipSegments.load(UserPreferences.currentProvider, args.videoType)
        setupEpisodeNavigationButtons()

        updatePlayerHeader()
        nextUi.setServers(servers, currentServer)

        binding.settings.setOnLocalSubtitlesClickedListener {
            isIgnoringPip = true
            pickLocalSubtitle.launch(
                arrayOf(
                    "text/plain",
                    "text/str",
                    "application/octet-stream",
                    MimeTypes.TEXT_UNKNOWN,
                    MimeTypes.TEXT_VTT,
                    MimeTypes.TEXT_SSA,
                    MimeTypes.APPLICATION_TTML,
                    MimeTypes.APPLICATION_MP4VTT,
                    MimeTypes.APPLICATION_SUBRIP,
                )
            )
        }

        binding.settings.setOnOpenSubtitleSelectedListener { subtitle ->
            viewModel.downloadSubtitle(subtitle.openSubtitle)
        }

        binding.settings.setOnSubDLSubtitleSelectedListener { subtitle ->
            viewModel.downloadSubDLSubtitle(subtitle.subDLSubtitle)
        }

        binding.settings.setOnExtraBufferingSelectedListener {
            displayVideo(
                currentVideo ?: return@setOnExtraBufferingSelectedListener,
                currentServer ?: return@setOnExtraBufferingSelectedListener
            )
        }

    }

    fun setupEpisodeNavigationButtons() {
        if (!::nextUi.isInitialized || _binding == null) return
        val isEpisode = args.videoType is Video.Type.Episode
        nextUi.setEpisodeNavigation(
            hasPrevious = isEpisode && EpisodeManager.hasPreviousEpisode(),
            hasNext = isEpisode && EpisodeManager.hasNextEpisode(),
        )
    }

    /** Saves the watch state of the title being left, then plays the neighbouring episode. */
    private fun goToEpisode(hasEpisode: () -> Boolean, playEpisode: () -> Unit) {
        if (!hasEpisode()) return

        val videoType = args.videoType

        val watchItem: WatchItem? = when (videoType) {
            is Video.Type.Movie -> database.movieDao().getById(videoType.id)
            is Video.Type.Episode -> database.episodeDao().getById(videoType.id)
        }

        when (videoType) {
            is Video.Type.Movie -> {
                val provider = UserPreferences.currentProvider ?: return
                val movie = watchItem as? Movie
                movie?.let { database.movieDao().update(it) }
                movie?.let { UserDataCache.addMovieToContinueWatching(requireContext(), provider, it) }
            }

            is Video.Type.Episode -> {
                val provider = UserPreferences.currentProvider ?: return
                val episode = watchItem as? Episode
                episode?.let {
                    if (player.hasFinished()) {
                        database.episodeDao().resetProgressionFromEpisode(videoType.id)
                        UserDataCache.removeEpisodeFromContinueWatching(requireContext(), provider, it.id)
                    }
                    database.episodeDao().update(it)

                    if (!player.hasFinished()) {
                        UserDataCache.addEpisodeToContinueWatching(requireContext(), provider, it)
                    }

                    it.tvShow?.let { tvShow ->
                        database.tvShowDao().getById(tvShow.id)
                    }?.let { tvShow ->
                        val episodeDao = database.episodeDao()
                        val isStillWatching = episodeDao.hasAnyWatchHistoryForTvShow(tvShow.id)

                        val updatedTvShow = tvShow.copy().apply {
                            merge(tvShow)
                            isWatching = !player.hasReallyFinished() || isStillWatching
                        }
                        database.tvShowDao().update(updatedTvShow)
                        CloudSyncHooks.tvShow(requireContext(), provider, updatedTvShow)
                    }
                }
            }
        }

        playEpisode()
    }

    private fun refreshEpisodeNavigation(type: Video.Type.Episode) {
        lifecycleScope.launch(Dispatchers.IO) {
            EpisodeManager.ensureNextEpisodeAvailable(type, database)
            withContext(Dispatchers.Main) {
                setupEpisodeNavigationButtons()
            }
        }
    }

    private fun playNextEpisodeAcrossSeasons(autoplay: Boolean = false) {
        val type = args.videoType as? Video.Type.Episode ?: return

        lifecycleScope.launch {
            val hasNextEpisode = withContext(Dispatchers.IO) {
                EpisodeManager.ensureNextEpisodeAvailable(type, database)
            }

            setupEpisodeNavigationButtons()

            if (!hasNextEpisode) return@launch
            if (autoplay && !UserPreferences.autoplay) return@launch

            viewModel.playNextEpisode()
        }
    }

    private fun decodeBase64Uri(uri: String): String? {
        return try {
            val parts = uri.split(",")
            if (parts.size == 2 && parts[0].contains(";base64")) {
                val base64Data = parts[1]
                val decodedBytes = Base64.getDecoder().decode(base64Data)
                String(decodedBytes, Charsets.UTF_8)
            } else {
                null
            }
        } catch (ignored: Exception) {
            null
        }
    }

    private fun extractUrlFromPlaylist(playlist: String): String? {
        return try {
            val lines = playlist.lines().map { it.trim() }
            lines.firstOrNull { it.startsWith("http") }
                ?: lines.firstNotNullOfOrNull { line ->
                    val regex = """URI=["'](http[^"']+)["']""".toRegex()
                    regex.find(line)?.groupValues?.get(1)
                }
        } catch (ignored: Exception) {
            null
        }
    }


    /** Snapshot of the stream that is playing now, or null when none is loaded yet. */
    private fun captureSnapshot(): PlaybackSnapshot? {
        if (!::player.isInitialized || currentVideo == null) return null
        if (player.currentMediaItem?.localConfiguration?.uri?.toString().isNullOrEmpty()) return null
        val serverFiles = currentVideo?.subtitles.orEmpty().map { it.file.toUri().toString() }.toSet()
        return PlaybackSnapshot.capture(player, serverFiles)
    }

    private fun displayVideo(
        video: Video,
        server: Video.Server,
        startPositionMs: Long? = null,
        failover: Boolean = false,
    ) {
        val previousPositionMs = if (::player.isInitialized) player.currentPosition else 0L
        val snapshot = pendingSnapshot ?: captureSnapshot()
        pendingSnapshot = null
        currentVideo = video
        currentServer = server
        updatePlayerHeader()
        nextUi.setServers(servers, server)

        val extraBuffering = PlayerSettingsView.Settings.ExtraBuffering.isEnabled
        val softwareDecoder = PlayerSettingsView.Settings.SoftwareDecoder.isEnabled

        if (extraBuffering != currentExtraBuffering || softwareDecoder != currentSoftwareDecoder) {
            initializePlayer(extraBuffering, softwareDecoder)
            player.playlistMetadata = MediaMetadata.Builder()
                .setTitle(resolvePlayerTitle())
                .setMediaServers(servers.map {
                    MediaServer(
                        id = it.id,
                        name = it.name,
                    )
                })
                .build()
        }

        val resumeMs = startPositionMs ?: pendingResumeMs ?: snapshot?.positionMs?.takeIf { it > 0 } ?: previousPositionMs.takeIf { it > 0 }
        pendingResumeMs = null
        stickyReportedServerId = null

        dataSources.applyHeaders(video)

        player.setMediaItem(
            // A downloaded title keeps its stream keys and cache key (offline playback).
            (OfflineMediaProvider.mediaItemBuilder(video) ?: MediaItem.Builder()
                .setUri(video.source.toUri())
                .setMimeType(video.type))
                .setSubtitleConfigurations(video.subtitles.map { subtitle ->
                    MediaItem.SubtitleConfiguration.Builder(subtitle.file.toUri())
                        .setMimeType(subtitle.file.toSubtitleMimeType())
                        .setLabel(subtitle.label)
                        .setLanguage(LanguageMatcher.normalize(subtitle.label))
                        .setSelectionFlags(if (subtitle.default) C.SELECTION_FLAG_DEFAULT else 0)
                        .build()
                } + snapshot?.sideloaded.orEmpty())
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setMediaServerId(server.id)
                        .build()
                )
                .build()
        )

        snapshot?.restore(player)

        binding.nextUi.model.showExternalPlayerButton = true
        val remoteStartMs = if (failover) null else com.streamflixreborn.streamflix.sync.PlayerStartPosition.consume(args.id)
        player.seekTo(remoteStartMs ?: resumeMs ?: savedResumePositionMs() ?: 0)

        if (failover) showPlayerMessage(getString(R.string.player_switched_server, server.name))

        analytics?.markPlayRequested(server.name)
        player.prepare()
        player.play()
    }

    private fun openExternalPlayer() {
        val video = currentVideo
        if (video == null) {
            Toast.makeText(
                requireContext(),
                getString(R.string.player_external_player_error_video),
                Toast.LENGTH_SHORT
            ).show()
            return
        }
            isIgnoringPip = true
            
            val videoTitle = when (val type = args.videoType) {
                is Video.Type.Movie -> type.title
                is Video.Type.Episode -> "${type.tvShow.title} • S${type.season.number} E${type.number}"
            }
            
            var sourceUri: Uri
            val mimeType = "video/*"
            
            val initialSource = video.source

            if (initialSource.startsWith("data:application/vnd.apple.mpegurl;base64,")) {
                val playlistContent = decodeBase64Uri(initialSource)
                val extractedUrl = if (playlistContent != null) extractUrlFromPlaylist(playlistContent) else null
                
                if (extractedUrl != null) {
                    sourceUri = extractedUrl.toUri()
                    Log.i("ExternalPlayer", "Link reale estratto: $sourceUri")
                } else {
                    try {
                        val file = File(requireContext().cacheDir, "stream.m3u8")
                        FileOutputStream(file).use { it.write(playlistContent?.toByteArray() ?: ByteArray(0)) }
                        sourceUri = FileProvider.getUriForFile(requireContext(), "${requireContext().packageName}.provider", file)
                    } catch (ignored: Exception) {
                        sourceUri = initialSource.toUri()
                    }
                }
            } else {
                sourceUri = initialSource.toUri()
            }

            Log.i("ExternalPlayer", "Avvio intent con URI: $sourceUri e MIME: $mimeType")

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(sourceUri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                
                putExtra("title", videoTitle)
                putExtra("position", player.currentPosition.toInt())
                putExtra("return_result", true)
                
                putExtra("extra_headers", video.headers?.map { "${it.key}: ${it.value}" }?.toTypedArray())
                
                if (video.headers != null) {
                    val headersArray = video.headers.flatMap { listOf(it.key, it.value) }.toTypedArray()
                    putExtra("headers", headersArray)
                }
            }

            try {
                val receiverIntent = Intent("ACTION_PLAYER_CHOSEN").apply {
                    setPackage(requireContext().packageName)
                }
                
                val pendingIntent = PendingIntent.getBroadcast(
                    requireContext(), 0, receiverIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                )

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                    startActivity(
                        Intent.createChooser(
                            intent,
                            getString(R.string.player_external_player_title),
                            pendingIntent.intentSender
                        )
                    )
                } else {
                    startActivity(Intent.createChooser(intent, getString(R.string.player_external_player_title)))
                }
            } catch (e: Exception) {
                Log.e("ExternalPlayer", "Errore selettore app", e)
                startActivity(Intent.createChooser(intent, getString(R.string.player_external_player_title)))
            }
    }

    /** Saved watch position minus 10 s (cache first, then DB). */
    private fun savedResumePositionMs(): Long? {
        val videoType = args.videoType
        val provider = UserPreferences.currentProvider
        val watchItem: WatchItem? = when (videoType) {
            is Video.Type.Movie -> {
                val movie = if (provider != null) {
                    UserDataCache.read(requireContext(), provider)?.continueWatchingMovies
                        ?.find { it.id == videoType.id }?.toMovie()
                } else null
                movie ?: database.movieDao().getById(videoType.id)
            }
            is Video.Type.Episode -> {
                val episode = if (provider != null) {
                    UserDataCache.read(requireContext(), provider)?.continueWatchingEpisodes
                        ?.find { it.id == videoType.id }?.toEpisode()
                } else null
                episode ?: database.episodeDao().getById(videoType.id)
            }
        }
        return watchItem?.watchHistory
            ?.let { (it.lastPlaybackPositionMillis - 10.seconds.inWholeMilliseconds).coerceAtLeast(0) }
    }

    /** Tells the activity's IdleDimmer whether video is really playing (buffering that will resume counts). */
    private fun reportDimState() {
        val playing = ::player.isInitialized && player.playWhenReady &&
            (player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_BUFFERING)
        (activity as? IdleDimHost)?.idleDimmer?.setPlaying(playing)
    }

    /** One listener per player instance. */
    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            reportDimState()
            if (_binding == null) return
            binding.pvPlayer.keepScreenOn = isPlaying || UserPreferences.keepScreenOnWhenPaused

            if (isPlaying) {
                progressSaver.recordStart(currentVideoTypeForUi())
                progressSaver.start(player)
                startProgressHandler()
            } else {
                progressSaver.stop()
                stopProgressHandler()
            }

            val hasUri = player.currentMediaItem?.localConfiguration?.uri
                ?.toString()?.isNotEmpty()
                ?: false

            if (!isPlaying && hasUri) {
                val snapshot = ProgressSaver.Snapshot.of(player)
                progressSaver.saveNow(snapshot)
                if (snapshot.hasReallyFinished && UserPreferences.autoplay) {
                    upNext.launchNext()
                }
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            reportDimState()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            reportDimState()
            if (_binding == null) return
            if (playbackState == Player.STATE_READY) {
                val server = currentServer ?: return
                if (stickyReportedServerId != server.id) {
                    stickyReportedServerId = server.id
                    viewModel.onPlaybackStarted(server)
                }
            }
        }

        override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
            autoSubtitles?.onTracksChanged(tracks)
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e("PlayerMobileFragment", "onPlayerError: ", error)
            // The empty placeholder shown while a server is being resolved is not a server failure.
            if (player.currentMediaItem?.localConfiguration?.uri?.toString().isNullOrEmpty()) return
            val position = player.currentPosition
            if (position > 0) pendingResumeMs = position
            Log.i("PlayerMobileFragment", "Playback failed on ${currentServer?.name}, switching server at $position ms")
            viewModel.onPlaybackFailed(currentServer)
        }
    }

    private fun enterPIPMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            requireActivity().enterPictureInPictureMode(
                PictureInPictureParams.Builder()
                    .build()
            )
        }
    }


    private fun ExoPlayer.hasFinished(): Boolean {
        return (this.currentPosition > (this.duration * 0.90))
    }

    private fun ExoPlayer.hasReallyFinished(): Boolean {
        return this.duration > 0 &&
                this.currentPosition >= (this.duration - UserPreferences.autoplayBuffer * 1000)
    }

    private fun currentVideoTypeForUi(): Video.Type = when (val type = args.videoType) {
        is Video.Type.Episode -> EpisodeManager.getCurrentEpisode()
            ?.takeIf { currentEpisode -> currentEpisode.id == type.id }
            ?: type
        is Video.Type.Movie -> type
    }

    private fun resolvePlayerTitle(videoType: Video.Type = currentVideoTypeForUi()): String {
        return when (videoType) {
            is Video.Type.Movie -> videoType.title
            is Video.Type.Episode -> videoType.tvShow.title.ifBlank { args.title }
        }
    }

    private fun resolvePlayerSubtitle(videoType: Video.Type = currentVideoTypeForUi()): String {
        return when (videoType) {
            is Video.Type.Movie -> args.subtitle
            is Video.Type.Episode -> {
                val episodeTitle = videoType.title?.takeUnless { it.isBlank() } ?: args.subtitle
                "S${videoType.season.number} E${videoType.number}  •  $episodeTitle"
            }
        }
    }

    private fun updatePlayerHeader(videoType: Video.Type = currentVideoTypeForUi()) {
        nextUi.setHeader(resolvePlayerTitle(videoType), resolvePlayerSubtitle(videoType))
    }

    private fun startProgressHandler() {
        progressHandler = android.os.Handler(android.os.Looper.getMainLooper())
        progressRunnable = Runnable {
            if (player.isPlaying) {
                val position = player.currentPosition
                val duration = player.duration
                // The fixed +85 s button is only a fallback when there is no segment data.
                showSkipIntroButton(!skipSegments.hasData && position in 3000..120000)
                skipSegments.onTick(position, duration)
                updateNextEpisodeOverlay(position, duration)
            }
            progressHandler.postDelayed(progressRunnable, 1000)
        }
        progressHandler.post(progressRunnable)
    }

    private fun stopProgressHandler() {
        if (::progressHandler.isInitialized) {
            progressHandler.removeCallbacks(progressRunnable)
        }
    }

    private fun updateNextEpisodeOverlay(positionMs: Long, durationMs: Long) {
        if (currentVideoTypeForUi() !is Video.Type.Episode) {
            hideNextEpisodeOverlay()
            return
        }
        upNext.onTick(positionMs, durationMs, skipSegments.segments?.creditsStartMs(durationMs))
    }

    private fun prefetchNextEpisodeServers() {
        val next = EpisodeManager.peekNextEpisode() ?: return
        viewModel.prefetchNextEpisode(next, currentServer?.name)
    }

    private fun ensureNextEpisodePrepared(currentEpisode: Video.Type.Episode) {
        if (EpisodeManager.peekNextEpisode() != null) {
            prefetchNextEpisodeServers()
            return
        }
        if (nextEpisodePrefetchTargetId == currentEpisode.id && nextEpisodePrefetchJob?.isActive == true) {
            return
        }

        nextEpisodePrefetchTargetId = currentEpisode.id
        nextEpisodePrefetchJob?.cancel()
        nextEpisodePrefetchJob = lifecycleScope.launch(Dispatchers.IO) {
            val loaded = EpisodeManager.ensureNextEpisodeAvailable(currentEpisode, database)
            withContext(Dispatchers.Main) {
                if (!isAdded || _binding == null) return@withContext
                setupEpisodeNavigationButtons()
                if (loaded) prefetchNextEpisodeServers()
            }
        }
    }

    private fun hideNextEpisodeOverlay() {
        if (_binding == null) return
        nextUi.hideUpNext()
    }

    /** The fixed "+85 s" fallback, only offered while there is no skip-segment data for the title. */
    private fun showSkipIntroButton(show: Boolean) {
        if (show && !nextUi.skipIsFallback && !skipSegments.isShowing) {
            nextUi.skipIsFallback = true
            nextUi.showSkip(getString(R.string.player_skip_intro))
        } else if (!show && nextUi.skipIsFallback) {
            nextUi.skipIsFallback = false
            nextUi.showSkip(null)
        }
    }

    override fun onPause() {
        super.onPause()
        stopProgressHandler()
        hideNextEpisodeOverlay()
    }

    private var currentExtraBuffering = false
    private var currentSoftwareDecoder = false

    private fun initializePlayer(
        extraBuffering: Boolean,
        softwareDecoder: Boolean = currentSoftwareDecoder,
    ) {
        releasePlayer()
        currentExtraBuffering = extraBuffering
        currentSoftwareDecoder = softwareDecoder

        dataSources = StreamDataSourceFactory(requireContext(), { currentVideo }, "MOBILE")
        val built = PlayerFactory.build(
            context = requireContext(),
            dataSources = dataSources,
            softwareDecoder = softwareDecoder,
            extraBuffering = extraBuffering,
            seekIncrementMs = 10_000,
        )
        player = built.player
        analytics = built.analytics
        player.addListener(playerListener)
        autoSubtitles = TrackPreferences.AutoSubtitles(player)
        mediaSession = MediaSession.Builder(requireContext(), player).build()

        binding.pvPlayer.player = player
        binding.nextUi.player = player
        binding.settings.player = player
        binding.settings.subtitleView = binding.pvPlayer.subtitleView
        binding.settings.onSubtitlesClicked = {
            viewModel.getSubtitles(args.videoType)
        }
    }

    private fun releasePlayer() {
        stopProgressHandler()
        binding.pvPlayer.player = null
        binding.nextUi.player = null
        binding.settings.player = null
        binding.settings.subtitleView = null
        if (::player.isInitialized) {
            player.release()
        }
        if (::mediaSession.isInitialized) {
            mediaSession.release()
        }
    }
}
