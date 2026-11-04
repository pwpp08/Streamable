package com.streamflixreborn.streamflix.activities.main

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import com.streamflixreborn.streamflix.sync.pairing.PairingApplier
import com.streamflixreborn.streamflix.sync.settings.SettingsSync
import androidx.activity.viewModels
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.navOptions
import com.bumptech.glide.Glide
import com.tanasi.navigation.widget.setupWithNavController
import com.streamflixreborn.streamflix.BuildConfig
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.database.AppDatabase
import com.streamflixreborn.streamflix.databinding.ActivityMainTvBinding
import com.streamflixreborn.streamflix.databinding.ContentHeaderMenuMainTvBinding
import com.streamflixreborn.streamflix.fragments.player.PlayerTvFragment
import com.streamflixreborn.streamflix.ui.UpdateAppTvDialog
import com.streamflixreborn.streamflix.providers.IptvProvider
import com.streamflixreborn.streamflix.providers.Provider
import com.streamflixreborn.streamflix.sync.HandoffResume
import com.streamflixreborn.streamflix.sync.TvCommandHandler
import com.streamflixreborn.streamflix.utils.AppLanguageManager
import com.streamflixreborn.streamflix.utils.IdleDimHost
import com.streamflixreborn.streamflix.utils.IdleDimmer
import com.streamflixreborn.streamflix.utils.ProfileManager
import com.streamflixreborn.streamflix.utils.ThemeManager
import com.streamflixreborn.streamflix.utils.UserPreferences
import com.streamflixreborn.streamflix.utils.getCurrentFragment
import com.streamflixreborn.streamflix.ui.motion.Springs
import android.graphics.drawable.GradientDrawable
import kotlinx.coroutines.launch

class MainTvActivity : FragmentActivity(), IdleDimHost {

    override val idleDimmer by lazy { IdleDimmer(this) }

    private var _binding: ActivityMainTvBinding? = null
    private val binding get() = _binding!!

    private val viewModel by viewModels<MainViewModel>()

    private lateinit var updateAppDialog: UpdateAppTvDialog
    private lateinit var navController: androidx.navigation.NavController

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(AppLanguageManager.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Il setup delle preferenze è già avvenuto in StreamFlixApp
        ThemeManager.applyTvTheme(this)
        
        super.onCreate(savedInstanceState)
        
        // Inizializza il provider con il context dell'attività per gestire eventuali bypass visibili

        _binding = ActivityMainTvBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyThemeNavigationChrome()

        binding.ivSplashOverlay.animate()
            .alpha(0f)
            .setDuration(800)
            .setStartDelay(400)
            .withEndAction {
                binding.ivSplashOverlay.visibility = View.GONE
            }

        val navHostFragment = this.supportFragmentManager
            .findFragmentById(binding.navMainFragment.id) as NavHostFragment
        navController = navHostFragment.navController

        adjustLayoutDelta(null, null)

        if (BuildConfig.APP_LAYOUT == "mobile" || (BuildConfig.APP_LAYOUT != "tv" && !packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK))) {
            finish()
            startActivity(Intent(this, MainMobileActivity::class.java))
            return
        }

        if (savedInstanceState == null) {
            val activeProfile = ProfileManager.activeProfile
            if (activeProfile != null) {
                val navToProviders = intent.getBooleanExtra("NAV_TO_PROVIDERS", false)
                when {
                    navToProviders -> navController.navigate(R.id.providers)
                    UserPreferences.currentProvider != null -> navController.navigate(R.id.home)
                    else -> navController.navigate(R.id.providers)
                }
            }
        }

        binding.navMain.setupWithNavController(navController)
        updateNavigationVisibility()
        configureNavigationRail()

        // Phone -> TV commands (Play on TV, remote search/controls) and hand-off resume.
        TvCommandHandler(this) { navController }.attach()
        HandoffResume.attach(this) { navController }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            binding.navMainFragment.isFocusedByDefault = true
        }

        navController.addOnDestinationChangedListener { _, destination, _ ->
            updateNavigationHeader()

            when (destination.id) {
                R.id.search, R.id.home, R.id.movies, R.id.tv_shows, R.id.favorites, R.id.settings -> {
                    binding.navMain.visibility = View.VISIBLE
                    binding.vNavMainSpacer.visibility = View.VISIBLE
                    updateNavigationVisibility()
                }
                else -> {
                    binding.navMain.visibility = View.GONE
                    binding.vNavMainSpacer.visibility = View.GONE
                }
            }
        }

        lifecycleScope.launch {
            viewModel.state.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect { state ->
                when (state) {
                    is MainViewModel.State.SuccessCheckingUpdate -> {
                        updateAppDialog = UpdateAppTvDialog(this@MainTvActivity, state.newReleases).also {
                            it.setOnUpdateClickListener { _ ->
                                if (!it.isLoading) viewModel.downloadUpdate(this@MainTvActivity, state.asset)
                            }
                            it.show()
                        }
                    }
                    MainViewModel.State.DownloadingUpdate -> if (::updateAppDialog.isInitialized) updateAppDialog.isLoading = true
                    is MainViewModel.State.SuccessDownloadingUpdate -> {
                        viewModel.installUpdate(this@MainTvActivity, state.apk)
                        if (::updateAppDialog.isInitialized) updateAppDialog.hide()
                    }
                    MainViewModel.State.InstallingUpdate -> if (::updateAppDialog.isInitialized) updateAppDialog.isLoading = true
                    is MainViewModel.State.FailedUpdate -> {
                        Toast.makeText(this@MainTvActivity, state.error.message ?: "Update failed", Toast.LENGTH_SHORT).show()
                    }
                    else -> {}
                }
            }
        }

        // Settings changed on another device (e.g. the theme) take effect immediately; a freshly
        // paired TV adopts the phone's settings as soon as the pairing finishes.
        lifecycleScope.launch {
            SettingsSync.applied.collect { if (it.themeChanged) recreate() }
        }
        lifecycleScope.launch {
            PairingApplier.status.collect { if (it is PairingApplier.Status.Done) SettingsSync.pull(this@MainTvActivity, force = true) }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when (navController.currentDestination?.id) {
                    R.id.home -> if (binding.navMain.hasFocus()) finish() else binding.navMain.requestFocus()
                    R.id.profiles -> {
                        if (UserPreferences.currentProvider != null) {
                            navigateToProviderHome(navController)
                        } else if (ProfileManager.activeProfile != null) {
                            navController.navigate(R.id.providers)
                        } else {
                            finish()
                        }
                    }
                    R.id.settings, R.id.search, R.id.movies, R.id.tv_shows -> {
                        navigateToProviderHome(navController)
                        binding.navMain.requestFocus()
                    }
                    else -> {
                        val handled = (getCurrentFragment() as? PlayerTvFragment)?.onBackPressed() ?: false
                        if (!handled && !navController.navigateUp()) finish()
                    }
                }
            }
        })
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        idleDimmer.onUserInteraction()
    }

    override fun onDestroy() {
        idleDimmer.release()
        super.onDestroy()
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
        updateNavigationHeader()
        viewModel.checkUpdate()
    }

    private fun updateNavigationHeader() {
        binding.navMain.headerView?.apply {
            val header = ContentHeaderMenuMainTvBinding.bind(this)

            Glide.with(context)
                .load(UserPreferences.currentProvider?.logo?.takeIf { it.isNotEmpty() } ?: R.drawable.ic_provider_default_logo)
                .error(R.drawable.ic_provider_default_logo)
                .into(header.ivNavigationHeaderIcon)
            header.tvNavigationHeaderTitle.text = UserPreferences.currentProvider?.name
            header.tvNavigationHeaderSubtitle.text = getString(R.string.main_menu_change_provider)

            val profile = ProfileManager.activeProfile
            if (profile != null) {
                val drawable = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = 8f * header.ivHeaderProfileAvatar.resources.displayMetrics.density
                    setColor(profile.avatarColor)
                }
                header.ivHeaderProfileAvatar.setImageDrawable(drawable)
                header.tvHeaderProfileInitial.text = profile.name.firstOrNull()?.uppercase() ?: "?"
                header.tvHeaderProfileName.text = profile.name
            }

            header.flHeaderProfileAvatar.setOnClickListener {
                navController.navigate(R.id.profiles)
            }
            header.flHeaderProfileAvatar.onFocusChangeListener = android.view.View.OnFocusChangeListener { _, hasFocus ->
                if (hasFocus) binding.navMain.open()
            }

            header.ivNavigationHeaderIcon.setOnClickListener {
                navController.navigate(R.id.providers)
            }

            binding.navMain.menuView.getChildAt(0)?.nextFocusUpId = R.id.fl_header_profile_avatar

            setOnOpenListener {
                header.tvNavigationHeaderTitle.visibility = View.VISIBLE
                header.tvNavigationHeaderSubtitle.visibility = View.VISIBLE
                header.tvHeaderProfileName.visibility = View.VISIBLE
                header.tvHeaderProfileSubtitle.visibility = View.VISIBLE
            }
            setOnCloseListener {
                header.tvNavigationHeaderTitle.visibility = View.GONE
                header.tvNavigationHeaderSubtitle.visibility = View.GONE
                header.tvHeaderProfileName.visibility = View.GONE
                header.tvHeaderProfileSubtitle.visibility = View.GONE
            }
        }
    }

    private fun applyThemeNavigationChrome() {
        val palette = ThemeManager.palette(this)
        window.statusBarColor = palette.systemBar
        window.navigationBarColor = palette.systemBar
        // Tonal rail with rounded end corners; it reads as a panel when it expands over content.
        val radius = 24f * resources.displayMetrics.density
        binding.navMain.background = GradientDrawable().apply {
            setColor(palette.tvNavBackground)
            cornerRadii = if (binding.navMain.layoutDirection == View.LAYOUT_DIRECTION_RTL) {
                floatArrayOf(radius, radius, 0f, 0f, 0f, 0f, radius, radius)
            } else {
                floatArrayOf(0f, 0f, radius, radius, radius, radius, 0f, 0f)
            }
        }
        binding.navMain.headerView?.background = null
    }

    /** Rail motion: the SpatialDefault spring for the width, snapping under reduced motion. */
    private fun configureNavigationRail() {
        val spring = Springs.spatialDefault(this)
        binding.navMain.setWidthSpring(spring.stiffness, spring.dampingRatio)
        binding.navMain.animationsEnabled = !Springs.isReducedMotion(this)
        // Start collapsed unless the rail already holds focus, so it never covers the content.
        binding.navMain.post {
            val rail = _binding?.navMain ?: return@post
            if (!rail.hasFocus() && rail.isOpen) rail.close()
        }
    }
    
    private fun updateNavigationVisibility() {
        UserPreferences.currentProvider?.let { provider ->
            binding.navMain.menu.findItem(R.id.movies)?.isVisible = Provider.supportsMovies(provider)
            val tvShowsItem = binding.navMain.menu.findItem(R.id.tv_shows)
            tvShowsItem?.isVisible = Provider.supportsTvShows(provider)
            tvShowsItem?.title = if (provider is IptvProvider)
                getString(R.string.main_menu_all_channels) else getString(R.string.main_menu_tv_shows)
        }
    }

    fun adjustLayoutDelta(deltaX: Int?, deltaY: Int?) {
        val uDeltaX = deltaX ?: UserPreferences.paddingX
        val uDeltaY = deltaY ?: UserPreferences.paddingY
        binding.root.setPadding(uDeltaX, uDeltaY, uDeltaX, uDeltaY)
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
}
