package com.streamflixreborn.streamflix.activities.main

import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import com.streamflixreborn.streamflix.sync.settings.SettingsSync
import androidx.activity.viewModels
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import com.streamflixreborn.streamflix.fragments.queue.ReactionPrompt
import com.streamflixreborn.streamflix.fragments.queue.WatchEvents
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.navOptions
import androidx.navigation.ui.setupWithNavController
import com.google.android.material.navigation.NavigationBarView
import com.streamflixreborn.streamflix.BuildConfig
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.databinding.ActivityMainMobileBinding
import com.streamflixreborn.streamflix.downloads.DownloadUtil
import com.streamflixreborn.streamflix.fragments.player.PlayerMobileFragment
import com.streamflixreborn.streamflix.providers.IptvProvider
import com.streamflixreborn.streamflix.providers.Provider
import com.streamflixreborn.streamflix.sync.CloudSyncManager
import com.streamflixreborn.streamflix.sync.HandoffResume
import com.streamflixreborn.streamflix.ui.UpdateAppMobileDialog
import com.streamflixreborn.streamflix.utils.AppLanguageManager
import com.streamflixreborn.streamflix.utils.IdleDimHost
import com.streamflixreborn.streamflix.utils.IdleDimmer
import com.streamflixreborn.streamflix.utils.ProfileManager
import com.streamflixreborn.streamflix.utils.ProviderChangeNotifier
import com.streamflixreborn.streamflix.utils.ThemeManager
import com.streamflixreborn.streamflix.utils.UserPreferences
import com.streamflixreborn.streamflix.utils.getCurrentFragment
import kotlinx.coroutines.launch

class MainMobileActivity : FragmentActivity(), IdleDimHost {

    override val idleDimmer by lazy { IdleDimmer(this) }

    private var _binding: ActivityMainMobileBinding? = null
    private val binding get() = _binding!!

    private val viewModel by viewModels<MainViewModel>()

    /** Bottom bar on phones, navigation rail on tablets (layout-sw600dp); same menu. */
    private val navigationBar: NavigationBarView
        get() = binding.bnvMain ?: binding.navRailMain!!
    private var updateAppDialog: UpdateAppMobileDialog? = null

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(AppLanguageManager.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyMobileTheme(this)

        super.onCreate(savedInstanceState)

        ThemeManager.applySystemBars(this)

        _binding = ActivityMainMobileBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyThemeNavigationChrome()

        ViewCompat.setOnApplyWindowInsetsListener(binding.mainContent) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            val navHostFragment = supportFragmentManager.findFragmentById(R.id.nav_main_fragment) as? NavHostFragment
            val currentFragment = navHostFragment?.childFragmentManager?.primaryNavigationFragment

            val isPlayer = currentFragment is PlayerMobileFragment
            // Only the phone bar covers the bottom edge; the tablet rail leaves it to the content.
            val isBottomNavVisible = binding.bnvMain?.visibility == View.VISIBLE

            val bottomPadding = if (isPlayer || isBottomNavVisible) 0 else insets.bottom
            val topPadding = if (isPlayer) 0 else insets.top

            view.setPadding(insets.left, topPadding, insets.right, bottomPadding)
            windowInsets
        }


        updateImmersiveMode()

        val navHost =
            supportFragmentManager.findFragmentById(R.id.nav_main_fragment) as NavHostFragment
        val navController = navHost.navController

        if (BuildConfig.APP_LAYOUT == "tv" ||
            (BuildConfig.APP_LAYOUT != "mobile" &&
                packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK))
        ) {
            finish()
            startActivity(Intent(this, MainTvActivity::class.java))
            return
        }

        if (savedInstanceState == null) {
            val activeProfile = ProfileManager.activeProfile
            if (activeProfile != null) {
                val hasProvider = UserPreferences.currentProvider != null
                val navToProviders = intent.getBooleanExtra("NAV_TO_PROVIDERS", false)
                val destination = when {
                    navToProviders -> R.id.providers
                    hasProvider -> R.id.home
                    else -> R.id.providers
                }
                navController.navigate(
                    destination,
                    null,
                    navOptions {
                        launchSingleTop = true
                        popUpTo(R.id.profiles) {
                            inclusive = true
                        }
                    }
                )
            }
        }

        if (savedInstanceState == null) {
            intent?.data?.takeIf { it.scheme == "streamflix" && it.host == "pair" }?.let { uri ->
                navController.navigate(R.id.pair_phone, Bundle().apply { putString("pairUri", uri.toString()) })
            }
        }
        HandoffResume.attach(this) { navController }
        // Debug builds only: open a screen directly for emulator checks (adb ... --es debug_nav remote).
        if (com.streamflixreborn.streamflix.BuildConfig.DEBUG && savedInstanceState == null) {
            when (intent?.getStringExtra("debug_nav")) {
                "remote" -> navController.navigate(R.id.remote)
                "search" -> navController.navigate(R.id.search)
                "home_rows" -> navController.navigate(R.id.home_rows)
            }
        }

        viewModel.checkUpdate()

        navigationBar.setupWithNavController(navController)
        binding.btnMainRemote.setOnClickListener {
            if (navController.currentDestination?.id != R.id.remote) {
                navController.navigate(R.id.remote)
            }
        }
        binding.btnMainDownloads.setOnClickListener {
            if (navController.currentDestination?.id != R.id.downloads) {
                navController.navigate(R.id.downloads)
            }
        }
        binding.btnMainSearch.setOnClickListener {
            if (navController.currentDestination?.id != R.id.search) {
                navController.navigate(R.id.search)
            }
        }
        updateNavigationVisibility()
        updateBottomNavigationVisibility(navController.currentDestination?.id)

        navController.addOnDestinationChangedListener { _, destination, _ ->
            updateNavigationVisibility(destination.id)
            updateBottomNavigationVisibility(destination.id)
            binding.mainContent.post { binding.mainContent.requestApplyInsets() }
        }

        SettingsSync.keepInSync(this)

        lifecycleScope.launch {
            WatchEvents.movieWatched
                .flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
                .collect { movie -> ReactionPrompt.show(this@MainMobileActivity, movie) }
        }

        lifecycleScope.launch {
            ProviderChangeNotifier.providerChangeFlow
                .flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
                .collect {
                    updateNavigationVisibility(navController.currentDestination?.id)
                }
        }

        lifecycleScope.launch {
            viewModel.state.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect { state ->
                when (state) {
                    is MainViewModel.State.SuccessCheckingUpdate -> {
                        if (viewModel.firstTime(state)) showUpdateDialog(state)
                    }
                    MainViewModel.State.UpToDate -> if (viewModel.firstTime(state)) {
                        Toast.makeText(this@MainMobileActivity, R.string.update_up_to_date, Toast.LENGTH_SHORT).show()
                    }

                    MainViewModel.State.DownloadingUpdate -> updateAppDialog?.isLoading = true
                    is MainViewModel.State.SuccessDownloadingUpdate -> {
                        viewModel.installUpdate(this@MainMobileActivity, state.apk)
                        dismissUpdateDialog()
                    }

                    MainViewModel.State.InstallingUpdate -> updateAppDialog?.isLoading = true
                    is MainViewModel.State.FailedUpdate -> if (viewModel.firstTime(state)) {
                        updateAppDialog?.isLoading = false
                        Toast.makeText(
                            this@MainMobileActivity,
                            state.error.message ?: "Update failed",
                            Toast.LENGTH_SHORT
                        ).show()
                    }

                    else -> {}
                }
            }
        }

        // Settings changed on another device (e.g. the theme) take effect immediately.
        lifecycleScope.launch {
            SettingsSync.applied.collect { if (it.themeChanged) recreate() }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val handled =
                    (getCurrentFragment() as? PlayerMobileFragment)?.onBackPressed() ?: false
                if (handled) return

                val currentDestinationId = navController.currentDestination?.id

                if (currentDestinationId == R.id.settings) {
                    navigateToProviderHome(navController)
                    return
                }

                if (UserPreferences.currentProvider != null && currentDestinationId == R.id.home) {
                    closeTask()
                    return
                }

                if (UserPreferences.currentProvider != null &&
                    isTopLevelProviderDestination(currentDestinationId)
                ) {
                    navigateToProviderHome(navController)
                    return
                }

                if (currentDestinationId == R.id.providers && UserPreferences.currentProvider != null) {
                    navigateToProviderHome(navController)
                    return
                }

                if (currentDestinationId == R.id.profiles && ProfileManager.activeProfile != null) {
                    if (UserPreferences.currentProvider != null) {
                        navigateToProviderHome(navController)
                    } else {
                        navController.navigate(
                            R.id.providers,
                            null,
                            navOptions {
                                launchSingleTop = true
                                popUpTo(R.id.profiles) { inclusive = false }
                            }
                        )
                    }
                    return
                }

                if (!navController.navigateUp()) finish()
            }
        })

    }

    override fun onDestroy() {
        idleDimmer.release()
        dismissUpdateDialog()
        _binding = null
        super.onDestroy()
    }

    private fun showUpdateDialog(state: MainViewModel.State.SuccessCheckingUpdate) {
        if (isFinishing || isDestroyed) return

        dismissUpdateDialog()
        updateAppDialog = UpdateAppMobileDialog(this, state.newReleases).also { dialog ->
            dialog.setOnUpdateClickListener {
                if (!dialog.isLoading) {
                    viewModel.downloadUpdate(this@MainMobileActivity, state.asset)
                }
            }
            dialog.show()
        }
    }

    private fun dismissUpdateDialog() {
        updateAppDialog?.takeIf { it.isShowing }?.dismiss()
        updateAppDialog = null
    }

    private fun updateBottomNavigationVisibility(destinationId: Int?) {
        val showBottomNav =
            UserPreferences.currentProvider != null && isTopLevelProviderDestination(destinationId)
        navigationBar.visibility = if (showBottomNav) View.VISIBLE else View.GONE
        // The Queue screen has its own toolbar; the global search / remote / downloads row stays off it.
        val showTopRow = destinationId != R.id.favorites
        binding.btnMainSearch.visibility = if (
            showTopRow &&
            UserPreferences.currentProvider != null &&
            isTopLevelProviderDestination(destinationId) &&
            destinationId != R.id.search
        ) View.VISIBLE else View.GONE
        binding.btnMainRemote.visibility = if (
            showTopRow && showBottomNav && CloudSyncManager.currentUserId() != null
        ) View.VISIBLE else View.GONE
        // Downloads (phone only): BottomNavigationView holds at most 5 items, so it sits in the top bar.
        binding.btnMainDownloads.visibility = if (
            showTopRow && showBottomNav && destinationId != R.id.downloads && DownloadUtil.isSupported(this)
        ) View.VISIBLE else View.GONE
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        idleDimmer.onUserInteraction()
    }

    override fun onPause() {
        idleDimmer.onPause()
        SettingsSync.push(this)
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        idleDimmer.onResume()
        SettingsSync.pull(this)
        val navHost =
            supportFragmentManager.findFragmentById(R.id.nav_main_fragment) as? NavHostFragment
        _binding?.let { updateBottomNavigationVisibility(navHost?.navController?.currentDestination?.id) }
    }

    private fun updateNavigationVisibility(currentDestinationId: Int? = null) {
        val provider = UserPreferences.currentProvider ?: return
        val supportsMovies = Provider.supportsMovies(provider)
        val supportsTvShows = Provider.supportsTvShows(provider)

        navigationBar.menu.findItem(R.id.movies)?.isVisible = supportsMovies
        navigationBar.menu.findItem(R.id.tv_shows)?.apply {
            isVisible = supportsTvShows
            title = if (provider is IptvProvider) {
                getString(R.string.main_menu_all_channels)
            } else {
                getString(R.string.main_menu_tv_shows)
            }
        }

        val navHost =
            supportFragmentManager.findFragmentById(R.id.nav_main_fragment) as? NavHostFragment
        val navController = navHost?.navController ?: return
        when {
            currentDestinationId == R.id.movies && !supportsMovies -> {
                navController.navigate(R.id.tv_shows)
            }

            currentDestinationId == R.id.tv_shows && !supportsTvShows -> {
                navController.navigate(R.id.home)
            }
        }
    }

    private fun isTopLevelProviderDestination(destinationId: Int?): Boolean {
        return destinationId in setOf(
            R.id.search,
            R.id.home,
            R.id.movies,
            R.id.tv_shows,
            R.id.favorites,
            R.id.settings,
            R.id.downloads,
        )
    }

    private fun navigateToProviderHome(navController: androidx.navigation.NavController) {
        if (!navController.popBackStack(R.id.home, false)) {
            navController.navigate(
                R.id.home,
                null,
                navOptions {
                    launchSingleTop = true
                    popUpTo(R.id.profiles) {
                        inclusive = true
                    }
                }
            )
        }
    }

    /**
     * Recreate Home after a profile switch. Profile switching replaces the
     * Room database, so an existing HomeViewModel may still be collecting
     * flows from the closed database instance.
     */
    fun recreateProviderHome() {
        val navHost =
            supportFragmentManager.findFragmentById(R.id.nav_main_fragment) as? NavHostFragment
        val navController = navHost?.navController ?: return

        navController.navigate(
            R.id.home,
            null,
            navOptions {
                launchSingleTop = true
                popUpTo(R.id.home) {
                    inclusive = true
                }
            },
        )
    }

    private fun closeTask() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            finishAndRemoveTask()
        } else {
            finishAffinity()
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        (getCurrentFragment() as? PlayerMobileFragment)?.onUserLeaveHint()
    }

    fun updateImmersiveMode() {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (UserPreferences.immersiveMode) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun applyThemeNavigationChrome() {
        // The bottom bar is styled by ?attr/bottomNavigationStyle (M3 Expressive); only the
        // system bars need refreshing here.
        ThemeManager.applySystemBars(this)
    }
}
