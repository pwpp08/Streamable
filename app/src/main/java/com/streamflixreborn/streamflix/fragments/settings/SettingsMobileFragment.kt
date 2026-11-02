package com.streamflixreborn.streamflix.fragments.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.constraintlayout.widget.Group
import androidx.lifecycle.lifecycleScope
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreference
import androidx.preference.SwitchPreferenceCompat
import com.streamflixreborn.streamflix.BuildConfig
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.activities.main.MainMobileActivity
import com.streamflixreborn.streamflix.backup.BackupRestoreManager
import com.streamflixreborn.streamflix.backup.ProviderBackupContext
import com.streamflixreborn.streamflix.database.AppDatabase
import com.streamflixreborn.streamflix.providers.Provider
import com.streamflixreborn.streamflix.providers.ProviderConfigUrl
import com.streamflixreborn.streamflix.providers.ProviderPortalUrl
import com.streamflixreborn.streamflix.providers.StreamingCommunityProvider
import com.streamflixreborn.streamflix.providers.TmdbProvider
import com.streamflixreborn.streamflix.utils.AppLanguageManager
import com.streamflixreborn.streamflix.utils.DnsResolver
import kotlin.coroutines.suspendCoroutine
import com.streamflixreborn.streamflix.utils.ProfileColorPicker
import com.streamflixreborn.streamflix.utils.ProfileManager
import com.streamflixreborn.streamflix.utils.ProfileSwitchPinGuard
import com.streamflixreborn.streamflix.utils.ProviderChangeNotifier
import com.streamflixreborn.streamflix.utils.ThemeManager
import com.streamflixreborn.streamflix.utils.UserPreferences
import com.streamflixreborn.streamflix.utils.DomainRedirectChecker
import com.streamflixreborn.streamflix.utils.VavooDomainSelector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume

class SettingsMobileFragment : PreferenceFragmentCompat() {
    private data class SettingsScreenState(
        val rootKey: String?,
        val title: String?,
    )

    private val DEFAULT_DOMAIN_VALUE = "streamingunity.fun"
    private val DEFAULT_SERIENSTREAM_DOMAIN_VALUE = "serienstream.to"
    private val DEFAULT_MOFLIX_DOMAIN_VALUE = "moflix-stream.xyz"
    private val DEFAULT_CUEVANA_DOMAIN_VALUE = "cuevana3.la"
    private val DEFAULT_POSEIDON_DOMAIN_VALUE = "www.poseidonhd2.co"
    private val PREFS_ERROR_VALUE = "PREFS_NOT_INIT_ERROR"
    private var currentScreenState = SettingsScreenState(rootKey = null, title = null)
    private val screenBackStack = ArrayDeque<SettingsScreenState>()
    private lateinit var settingsBackCallback: OnBackPressedCallback

    private lateinit var backupRestoreManager: BackupRestoreManager
    private var backupLoadingDialog: AlertDialog? = null

    private val exportBackupLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        uri?.let {
            viewLifecycleOwner.lifecycleScope.launch {
                performBackupExport(it)
            }
        }
    }

    private val exportDbBackupLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri: Uri? ->
        uri?.let {
            viewLifecycleOwner.lifecycleScope.launch {
                performDatabaseBackupExport(it)
            }
        }
    }

    private val importDbBackupLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            viewLifecycleOwner.lifecycleScope.launch {
                performDatabaseBackupImport(it)
            }
        }
    }

    private val importBackupLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            viewLifecycleOwner.lifecycleScope.launch {
                performBackupImport(it)
            }
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        currentScreenState = SettingsScreenState(rootKey = rootKey, title = null)
        renderCurrentScreen()

        val allProvidersToBackup = Provider.providers.keys.toMutableList().apply {
            listOf("it", "en", "es", "de", "fr").forEach { lang ->
                add(TmdbProvider(lang))
            }
        }

        backupRestoreManager = BackupRestoreManager(
            requireContext(),
            allProvidersToBackup.mapNotNull { provider ->
                try {
                    val db = AppDatabase.getInstanceForProvider(provider.name, requireContext())
                    ProviderBackupContext(
                        name = provider.name,
                        movieDao = db.movieDao(),
                        tvShowDao = db.tvShowDao(),
                        episodeDao = db.episodeDao(),
                        seasonDao = db.seasonDao(),
                        provider = provider
                    )
                } catch (e: Exception) {
                    Log.w("BackupRestore", "Skipping ${provider.name}: ${e.message}")
                    null
                }
            }
        )

        displaySettings()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsBackCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                if (screenBackStack.isEmpty()) return
                currentScreenState = screenBackStack.removeLast()
                settingsBackCallback.isEnabled = screenBackStack.isNotEmpty()
                renderCurrentScreen()
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(this, settingsBackCallback)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        SettingsListStyler.attach(view, isTv = false)
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        if (preference is PreferenceScreen && !preference.key.isNullOrBlank()) {
            screenBackStack.addLast(currentScreenState)
            currentScreenState = SettingsScreenState(
                rootKey = preference.key,
                title = preference.title?.toString(),
            )
            settingsBackCallback.isEnabled = screenBackStack.isNotEmpty()
            renderCurrentScreen()
            return true
        }
        return super.onPreferenceTreeClick(preference)
    }

    override fun onDisplayPreferenceDialog(preference: Preference) {
        if (preference.key == "PARENTAL_CONTROL_PIN" || preference.key == "PARENTAL_CONTROL_ADMIN_PIN") {
            return
        }
        super.onDisplayPreferenceDialog(preference)
    }

    private fun applyScreenTitle() {
        activity?.title = currentScreenState.title ?: getString(R.string.player_settings_title)
    }

    private fun renderCurrentScreen() {
        getPreferenceManager().setSharedPreferencesName(
            ProfileManager.profilePreferencesName(ProfileManager.activeProfileId ?: "default")
        )
        setPreferencesFromResource(R.xml.settings_mobile, currentScreenState.rootKey)
        if (::backupRestoreManager.isInitialized) {
            displaySettings()
        }
        applyScreenTitle()
    }

    private fun displaySettings() {
        updateOverviewLabels()
        updateProviderVisibilityState()
        bindGenericProviderDomain()
        SupabaseSettingsController.bind(this, lifecycleScope) { key ->
            findPreference(key)
        }

        findPreference<EditTextPreference>("provider_streamingcommunity_domain")?.apply {
            val currentValue = UserPreferences.streamingcommunityDomain
            summary = currentValue
            if (currentValue == DEFAULT_DOMAIN_VALUE || currentValue == PREFS_ERROR_VALUE) {
                text = null
            } else {
                text = currentValue
            }
            setOnPreferenceChangeListener { preference, newValue ->
                val typed = (newValue as String).trim()
                val BLOCKED = listOf("streamingcommunityz.green", "streamingunity.club", "streamingunity.bike", "streamingcommunityz.buzz")
                val effectiveDomain = if (BLOCKED.any { typed.contains(it) }) DEFAULT_DOMAIN_VALUE else typed
                UserPreferences.streamingcommunityDomain = effectiveDomain
                preference.summary = effectiveDomain
                if (effectiveDomain != typed) {
                    findPreference<EditTextPreference>("provider_streamingcommunity_domain")?.text = null
                    Toast.makeText(requireContext(), getString(R.string.settings_streamingcommunity_domain_blocked), Toast.LENGTH_LONG).show()
                }
                if (UserPreferences.currentProvider is StreamingCommunityProvider) {
                    viewLifecycleOwner.lifecycleScope.launch {
                        (UserPreferences.currentProvider as StreamingCommunityProvider).rebuildService()
                        requireActivity().apply {
                            finish()
                            startActivity(Intent(this, this::class.java))
                        }
                    }
                }
                true
            }
        }

        findPreference<Preference>("provider_streamingcommunity_domain_reset")?.setOnPreferenceClickListener {
            UserPreferences.streamingcommunityDomain = DEFAULT_DOMAIN_VALUE
            findPreference<EditTextPreference>("provider_streamingcommunity_domain")?.apply {
                summary = DEFAULT_DOMAIN_VALUE
                text = null
            }
            Toast.makeText(requireContext(), getString(R.string.settings_streamingcommunity_domain_reset_done), Toast.LENGTH_SHORT).show()
            if (UserPreferences.currentProvider is StreamingCommunityProvider) {
                viewLifecycleOwner.lifecycleScope.launch {
                    (UserPreferences.currentProvider as StreamingCommunityProvider).rebuildService()
                    requireActivity().apply {
                        finish()
                        startActivity(Intent(this, this::class.java))
                    }
                }
            }
            true
        }

        findPreference<EditTextPreference>("provider_serienstream_domain")?.apply {
            val currentValue = UserPreferences.serienstreamDomain
            summary = currentValue
            if (currentValue == DEFAULT_SERIENSTREAM_DOMAIN_VALUE || currentValue == PREFS_ERROR_VALUE) {
                text = null
            } else {
                text = currentValue
            }
            setOnPreferenceChangeListener { preference, newValue ->
                val typed = (newValue as String).trim()
                val effectiveDomain = typed.ifBlank { DEFAULT_SERIENSTREAM_DOMAIN_VALUE }
                UserPreferences.serienstreamDomain = effectiveDomain
                preference.summary = effectiveDomain
                if (false) {
                    viewLifecycleOwner.lifecycleScope.launch {
                        requireActivity().apply {
                            finish()
                            startActivity(Intent(this, this::class.java))
                        }
                    }
                }
                true
            }
        }

        findPreference<Preference>("provider_serienstream_domain_reset")?.setOnPreferenceClickListener {
            UserPreferences.serienstreamDomain = DEFAULT_SERIENSTREAM_DOMAIN_VALUE
            findPreference<EditTextPreference>("provider_serienstream_domain")?.apply {
                summary = DEFAULT_SERIENSTREAM_DOMAIN_VALUE
                text = null
            }
            Toast.makeText(requireContext(), getString(R.string.settings_serienstream_domain_reset_done), Toast.LENGTH_SHORT).show()
            if (false) {
                viewLifecycleOwner.lifecycleScope.launch {
                    requireActivity().apply {
                        finish()
                        startActivity(Intent(this, this::class.java))
                    }
                }
            }
            true
        }

        findPreference<EditTextPreference>("provider_moflix_domain")?.apply {
            val currentValue = UserPreferences.moflixDomain
            summary = currentValue
            if (currentValue == DEFAULT_MOFLIX_DOMAIN_VALUE || currentValue == PREFS_ERROR_VALUE) {
                text = null
            } else {
                text = currentValue
            }
            setOnPreferenceChangeListener { preference, newValue ->
                val typed = (newValue as String).trim()
                val effectiveDomain = typed.ifBlank { DEFAULT_MOFLIX_DOMAIN_VALUE }
                UserPreferences.moflixDomain = effectiveDomain
                preference.summary = effectiveDomain
                true
            }
        }

        findPreference<Preference>("provider_moflix_domain_reset")?.setOnPreferenceClickListener {
            UserPreferences.moflixDomain = DEFAULT_MOFLIX_DOMAIN_VALUE
            findPreference<EditTextPreference>("provider_moflix_domain")?.apply {
                summary = DEFAULT_MOFLIX_DOMAIN_VALUE
                text = null
            }
            Toast.makeText(requireContext(), getString(R.string.settings_moflix_domain_reset_done), Toast.LENGTH_SHORT).show()
            true
        }

        findPreference<EditTextPreference>("provider_cuevana_domain")?.apply {
            val currentValue = UserPreferences.cuevanaDomain
            summary = currentValue
            if (currentValue == DEFAULT_CUEVANA_DOMAIN_VALUE) {
                text = null
            } else {
                text = currentValue
            }
            setOnPreferenceChangeListener { preference, newValue ->
                val newDomainFromDialog = newValue as String
                UserPreferences.cuevanaDomain = newDomainFromDialog
                preference.summary = UserPreferences.cuevanaDomain
                if (UserPreferences.currentProvider?.name == "Cuevana 3") {
                    requireActivity().apply {
                        finish()
                        startActivity(Intent(this, this::class.java))
                    }
                }
                true
            }
        }

        findPreference<EditTextPreference>("provider_poseidon_domain")?.apply {
            val currentValue = UserPreferences.poseidonDomain
            summary = currentValue
            if (currentValue == DEFAULT_POSEIDON_DOMAIN_VALUE) {
                text = null
            } else {
                text = currentValue
            }
            setOnPreferenceChangeListener { preference, newValue ->
                val newDomainFromDialog = newValue as String
                UserPreferences.poseidonDomain = newDomainFromDialog
                preference.summary = UserPreferences.poseidonDomain
                if (UserPreferences.currentProvider?.name == "Poseidonhd2") {
                    requireActivity().apply {
                        finish()
                        startActivity(Intent(this, this::class.java))
                    }
                }
                true
            }
        }

        bindProviderDomainCheckButtons()
        bindAnimeOnlineNinjaPreferredServer()

        findPreference<Preference>("CLEAR_RECENTLY_WATCHED")?.setOnPreferenceClickListener {
            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.settings_clear_recently_watched_title))
                .setMessage(getString(R.string.settings_clear_recently_watched_dialog_message))
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                        AppDatabase.getInstance(requireContext()).movieDao().clearAllRecentlyWatched()
                        AppDatabase.getInstance(requireContext()).tvShowDao().clearAllRecentlyWatched()
                        withContext(Dispatchers.Main) {
                            Toast.makeText(requireContext(), getString(R.string.settings_clear_recently_watched_done), Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            true
        }

        findPreference<SwitchPreferenceCompat>("ENABLE_RECENTLY_WATCHED")?.setOnPreferenceChangeListener { _, newValue ->
            UserPreferences.setRecentlyWatchedEnabled(newValue as Boolean)
            true
        }

        findPreference<androidx.preference.MultiSelectListPreference>("TMDB_CATEGORIES")?.setOnPreferenceChangeListener { _, newValue ->
            @Suppress("UNCHECKED_CAST")
            val selected = newValue as? Set<String> ?: emptySet()
            UserPreferences.tmdbCategories = selected
            true
        }

        findPreference<EditTextPreference>("TMDB_API_KEY")?.apply {
            summary = if (UserPreferences.tmdbApiKey.isEmpty()) getString(R.string.settings_tmdb_api_key_summary) else UserPreferences.tmdbApiKey
            text = UserPreferences.tmdbApiKey
            setOnPreferenceChangeListener { _, newValue ->
                val newKey = (newValue as String).trim()
                UserPreferences.tmdbApiKey = newKey
                summary = if (newKey.isEmpty()) getString(R.string.settings_tmdb_api_key_summary) else newKey
                val message = if (newKey.isEmpty()) {
                    getString(R.string.settings_tmdb_api_key_reset)
                } else {
                    getString(R.string.settings_tmdb_api_key_success)
                }
                Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
                true
            }
        }

        findPreference<EditTextPreference>("SUBDL_API_KEY")?.apply {
            summary = if (UserPreferences.subdlApiKey.isEmpty()) getString(R.string.settings_subdl_api_key_summary) else UserPreferences.subdlApiKey
            text = UserPreferences.subdlApiKey
            setOnPreferenceChangeListener { _, newValue ->
                val newKey = (newValue as String).trim()
                UserPreferences.subdlApiKey = newKey
                summary = if (newKey.isEmpty()) getString(R.string.settings_subdl_api_key_summary) else newKey
                val message = if (newKey.isEmpty()) {
                    getString(R.string.settings_subdl_api_key_reset)
                } else {
                    getString(R.string.settings_subdl_api_key_success)
                }
                Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
                true
            }
        }

        findPreference<Preference>("p_settings_about")?.apply {
            val palette = ThemeManager.palette(UserPreferences.selectedTheme)
            val titleStr = getString(R.string.settings_version_mobile)
            val spannableTitle = SpannableString(titleStr)
            spannableTitle.setSpan(ForegroundColorSpan(palette.tvHeaderPrimary), 0, titleStr.length, 0)
            title = spannableTitle
            
            val summaryStr = BuildConfig.VERSION_NAME
            val spannableSummary = SpannableString(summaryStr)
            spannableSummary.setSpan(ForegroundColorSpan(palette.tvHeaderSecondary), 0, summaryStr.length, 0)
            summary = spannableSummary
            
            isSelectable = false
            setOnPreferenceClickListener(null)
        }

        findPreference<Preference>("p_settings_help")?.setOnPreferenceClickListener {
            startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://git.gay/streamflix-reborn2/streamflix")
                )
            )
            true
        }

        findPreference<Preference>("p_settings_telegram")?.setOnPreferenceClickListener {
            try {
                val tgIntent = Intent(Intent.ACTION_VIEW, Uri.parse("tg://resolve?domain=streamflixreborn"))
                startActivity(tgIntent)
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Telegram not found.", Toast.LENGTH_SHORT).show()
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/streamflixreborn"))
                startActivity(intent)
            }
            true
        }

        findPreference<SwitchPreference>("AUTOPLAY")?.isChecked = UserPreferences.autoplay
        findPreference<SwitchPreference>("AUTOPLAY")?.setOnPreferenceChangeListener { _, newValue ->
            UserPreferences.autoplay = newValue as Boolean
            true
        }

        findPreference<SwitchPreference>("FORCE_EXTRA_BUFFERING")?.apply {
            isChecked = UserPreferences.forceExtraBuffering
            setOnPreferenceChangeListener { _, newValue ->
                UserPreferences.forceExtraBuffering = newValue as Boolean
                true
            }
        }

        findPreference<EditTextPreference>("p_settings_autoplay_buffer")?.apply {
            summaryProvider = Preference.SummaryProvider<EditTextPreference> { pref ->
                val value = pref.text?.toLongOrNull() ?: 3L
                "$value s"
            }
            setOnPreferenceChangeListener { _, newValue ->
                UserPreferences.autoplayBuffer = (newValue as String).toLongOrNull() ?: 3L
                true
            }
        }

        findPreference<SwitchPreference>("PLAYER_GESTURES")?.isChecked = UserPreferences.playerGestures
        findPreference<SwitchPreference>("PLAYER_GESTURES")?.setOnPreferenceChangeListener { _, newValue ->
            UserPreferences.playerGestures = newValue as Boolean
            true
        }

        findPreference<SwitchPreference>("KEEP_SCREEN_ON_WHEN_PAUSED")?.isChecked = UserPreferences.keepScreenOnWhenPaused
        findPreference<SwitchPreference>("KEEP_SCREEN_ON_WHEN_PAUSED")?.setOnPreferenceChangeListener { _, newValue ->
            UserPreferences.keepScreenOnWhenPaused = newValue as Boolean
            true
        }

        findPreference<SwitchPreference>("UPDATE_CHECK_ENABLED")?.isChecked = UserPreferences.updateCheckEnabled
        findPreference<SwitchPreference>("UPDATE_CHECK_ENABLED")?.setOnPreferenceChangeListener { preference, newValue ->
            UserPreferences.updateCheckEnabled = newValue as Boolean
            (preference as SwitchPreference).isChecked = newValue
            false
        }

        findPreference<SwitchPreference>("SERVER_AUTO_SUBTITLES_DISABLED")?.apply {
            isChecked = UserPreferences.serverAutoSubtitlesDisabled
            setOnPreferenceChangeListener { _, newValue ->
                UserPreferences.serverAutoSubtitlesDisabled = newValue as Boolean
                true
            }
        }

        val HasConfigProvider = UserPreferences.currentProvider is ProviderConfigUrl
        findPreference<PreferenceCategory>("pc_provider_settings")?.apply {
            isVisible = HasConfigProvider
        }

        if (HasConfigProvider) {
            val provider = UserPreferences.currentProvider
            val configProvider = provider as? ProviderConfigUrl
            val portalProvider = provider as? ProviderPortalUrl
            var autoUpdateVal = false

            findPreference<SwitchPreference>("provider_autoupdate")?.apply {
                isVisible = portalProvider != null
                if (isVisible) {
                    autoUpdateVal = UserPreferences
                        .getProviderCache(
                            provider!!, UserPreferences
                                .PROVIDER_AUTOUPDATE
                        ) != "false"
                    isChecked = autoUpdateVal
                    setOnPreferenceChangeListener { _, newValue ->
                        val newState = newValue as Boolean
                        UserPreferences.setProviderCache(
                            null,
                            UserPreferences.PROVIDER_AUTOUPDATE,
                            newState.toString()
                        )
                        findPreference<EditTextPreference>("provider_url")?.isEnabled = newState == false
                        true
                    }
                }
            }

            findPreference<EditTextPreference>("provider_url")?.apply {
                isVisible = configProvider != null
                isEnabled = autoUpdateVal == false
                if (isVisible && provider != null && configProvider != null) {
                    summary = UserPreferences
                        .getProviderCache(
                            provider, UserPreferences
                                .PROVIDER_URL
                        )
                        .ifBlank { provider.defaultBaseUrl }
                    setOnBindEditTextListener { editText ->
                        editText.inputType = InputType.TYPE_CLASS_TEXT
                        editText.imeOptions = EditorInfo.IME_ACTION_DONE
                        editText.hint = configProvider.defaultBaseUrl

                        editText.setText(summary)
                    }
                    setOnPreferenceChangeListener { _, newValue ->
                        val toSave = (newValue as String)
                            .ifBlank { configProvider.defaultBaseUrl }
                            .trim()
                            .removeSuffix("/") + "/"
                        UserPreferences.setProviderCache(
                            null,
                            UserPreferences.PROVIDER_URL,
                            toSave
                        )
                        summary = toSave
                        viewLifecycleOwner.lifecycleScope.launch {
                            configProvider.onChangeUrl()
                            ProviderChangeNotifier.notifyProviderChanged()
                        }
                        true
                    }
                }
            }

            findPreference<EditTextPreference>("provider_portal_url")?.apply {
                isVisible = portalProvider != null
                if (isVisible && provider != null && portalProvider != null) {
                    summary = UserPreferences
                        .getProviderCache(
                            provider, UserPreferences
                                .PROVIDER_PORTAL_URL
                        )
                        .ifBlank { portalProvider.defaultPortalUrl }
                    setOnBindEditTextListener { editText ->
                        editText.inputType = InputType.TYPE_CLASS_TEXT
                        editText.imeOptions = EditorInfo.IME_ACTION_DONE
                        editText.hint = portalProvider.defaultPortalUrl
                        editText.setText(summary)
                    }
                    setOnPreferenceChangeListener { _, newValue ->
                        val toSave = (newValue as String)
                            .ifBlank { portalProvider.defaultPortalUrl }
                            .trim()
                            .removeSuffix("/") + "/"
                        summary = toSave
                        UserPreferences.setProviderCache(
                            null,
                            UserPreferences.PROVIDER_PORTAL_URL,
                            toSave
                        )
                        true
                    }
                }
            }

            findPreference<Preference>("provider_autoupdate_now")?.apply {
                isVisible = portalProvider != null
                setOnPreferenceClickListener {
                    viewLifecycleOwner.lifecycleScope.launch {
                        findPreference<EditTextPreference>("provider_url")?.summary =
                            configProvider!!.onChangeUrl(true)
                    }
                    true
                }
            }
        }

        findPreference<ListPreference>("p_doh_provider_url")?.apply {
            value = UserPreferences.dohProviderUrl
            summary = entry
            setOnPreferenceChangeListener { preference, newValue ->
                val newUrl = newValue as String
                UserPreferences.dohProviderUrl = newUrl
                DnsResolver.setDnsUrl(newUrl)
                if (preference is ListPreference) {
                    val index = preference.findIndexOfValue(newUrl)
                    if (index >= 0 && preference.entries != null && index < preference.entries.size) {
                        preference.summary = preference.entries[index]
                    } else {
                        preference.summary = null
                    }
                }
                if (UserPreferences.currentProvider is StreamingCommunityProvider) {
                    viewLifecycleOwner.lifecycleScope.launch {
                        (UserPreferences.currentProvider as StreamingCommunityProvider).rebuildService()
                        requireActivity().apply {
                            finish()
                            startActivity(Intent(this, this::class.java))
                        }
                    }
                } else {
                    Toast.makeText(requireContext(), getString(R.string.doh_provider_updated), Toast.LENGTH_LONG).show()
                }
                true
            }
        }

        findPreference<SwitchPreference>("pc_frenchstream_new_interface")?.apply {
            isVisible = false
            if (isVisible) {
                val useNewInterface = UserPreferences
                    .getProviderCache(
                        UserPreferences.currentProvider!!, UserPreferences
                            .PROVIDER_NEW_INTERFACE
                    ) != "false"
                isChecked = useNewInterface
                setOnPreferenceChangeListener { _, newValue ->
                    val newState = newValue as Boolean
                    UserPreferences.setProviderCache(
                        null,
                        UserPreferences.PROVIDER_NEW_INTERFACE,
                        newState.toString()
                    )
                    true
                }
            }
        }

        findPreference<ListPreference>("SELECTED_THEME")?.apply {
            summaryProvider = Preference.SummaryProvider<ListPreference> { pref ->
                getString(ThemeManager.titleRes(pref.value ?: ThemeManager.DEFAULT))
            }
            setOnPreferenceChangeListener { preference, newValue ->
                val newTheme = newValue as String
                UserPreferences.selectedTheme = newTheme
                if (preference is ListPreference) {
                    preference.value = newTheme
                }
                requireActivity().apply {
                    finish()
                    startActivity(Intent(this, MainMobileActivity::class.java))
                }
                true
            }
        }

        findPreference<ListPreference>("APP_LANGUAGE")?.apply {
            entries = AppLanguageManager.buildLanguageEntries(requireContext())
            entryValues = AppLanguageManager.buildLanguageValues(requireContext())
            value = AppLanguageManager.getSelectedLanguage(requireContext())
            summaryProvider = Preference.SummaryProvider<ListPreference> { pref ->
                pref.entries.getOrNull(pref.findIndexOfValue(pref.value))
                    ?: getString(R.string.settings_app_language_system)
            }
            setOnPreferenceChangeListener { preference, newValue ->
                val newLanguage = newValue as String
                AppLanguageManager.setSelectedLanguage(newLanguage)
                if (preference is ListPreference) {
                    preference.value = newLanguage
                }
                requireActivity().apply {
                    finish()
                    startActivity(
                        Intent(this, MainMobileActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    )
                }
                true
            }
        }

        findPreference<SwitchPreference>("IMMERSIVE_MODE")?.apply {
            isChecked = UserPreferences.immersiveMode
            setOnPreferenceChangeListener { _, newValue ->
                UserPreferences.immersiveMode = newValue as Boolean
                (activity as? MainMobileActivity)?.updateImmersiveMode()
                true
            }
        }

        findPreference<SwitchPreferenceCompat>("ENABLE_TMDB")?.apply {
            isChecked = UserPreferences.enableTmdb
            setOnPreferenceChangeListener { _, newValue ->
                val enabled = newValue as Boolean
                val applyChange = {
                    UserPreferences.enableTmdb = enabled
                    updateParentalControlPreferenceState()
                    ProviderChangeNotifier.notifyProviderChanged()
                    val message = if (enabled) {
                        getString(R.string.settings_enable_tmdb_enabled)
                    } else {
                        getString(R.string.settings_enable_tmdb_disabled)
                    }
                    Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
                }

                if (!enabled && UserPreferences.parentalControlPin.isNotBlank()) {
                    changeParentalSettingWithPinCheck(onVerified = applyChange)
                    false
                } else {
                    applyChange()
                    true
                }
            }
        }

        setupParentalControlPreferences()

        findPreference<Preference>("key_backup_export_mobile")?.setOnPreferenceClickListener {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "streamflix_mobile_backup_$timestamp.json"
            exportBackupLauncher.launch(fileName)
            true
        }

        findPreference<Preference>("key_backup_import_mobile")?.setOnPreferenceClickListener {
            importBackupLauncher.launch(arrayOf("application/json"))
            true
        }

        findPreference<Preference>("preferred_player_reset")?.setOnPreferenceClickListener {
            UserPreferences.removeProfilePreference("preferred_smarttube_package")
            Toast.makeText(requireContext(), R.string.settings_trailer_player_reset, Toast.LENGTH_SHORT).show()
            true
        }

        findPreference<Preference>("key_backup_refresh_cache_mobile")?.setOnPreferenceClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.settings_refresh_cache_confirm)
                .setMessage(R.string.settings_refresh_cache_message)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    viewLifecycleOwner.lifecycleScope.launch {
                        val refreshed = backupRestoreManager.refreshCachesFromDatabase()
                        Toast.makeText(
                            requireContext(),
                            if (refreshed) {
                                R.string.settings_refresh_cache_success
                            } else {
                                R.string.settings_refresh_cache_success
                            },
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            true
        }

        findPreference<Preference>("key_backup_export_db_mobile")?.setOnPreferenceClickListener {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "streamflix_mobile_db_backup_$timestamp.zip"
            exportDbBackupLauncher.launch(fileName)
            true
        }

        findPreference<Preference>("key_backup_import_db_mobile")?.setOnPreferenceClickListener {
            importDbBackupLauncher.launch(arrayOf("application/zip"))
            true
        }

        findPreference<Preference>("key_profile_edit")?.setOnPreferenceClickListener {
            val active = ProfileManager.activeProfile
            if (active != null) showRenameProfileDialog(active)
            true
        }

        findPreference<Preference>("key_profile_manage")?.setOnPreferenceClickListener {
            showProfileManagementDialog()
            true
        }

        findPreference<Preference>("key_profile_add")?.setOnPreferenceClickListener {
            showCreateProfileDialog()
            true
        }
    }

    private fun updateOverviewLabels() {
        val providerName = UserPreferences.currentProvider?.name

        findPreference<PreferenceScreen>("screen_provider")?.apply {
            title = getString(R.string.settings_provider_connection_title)
            summary = providerName?.let {
                getString(R.string.settings_screen_provider_summary_with_name, it)
            } ?: getString(R.string.settings_screen_provider_summary)
        }

        findPreference<PreferenceCategory>("pc_provider_settings")?.title = providerName?.let {
            getString(R.string.settings_provider_connection_category_title, it)
        } ?: getString(R.string.settings_category_provider_title)

        findPreference<PreferenceCategory>("pc_provider_empty_state")?.title = providerName?.let {
            getString(R.string.settings_provider_connection_category_title, it)
        } ?: getString(R.string.settings_provider_connection_title)

        findPreference<Preference>("key_profile_edit")?.summary =
            ProfileManager.activeProfile?.name ?: getString(R.string.profile_edit_btn)
    }


    private fun isVavooProviderName(
        providerName: String?,
    ): Boolean =
        providerName
            ?.startsWith(
                "Vavoo",
                ignoreCase = true,
            ) == true

    private fun configurableDomainPreferenceKey(
        providerName: String,
    ): String =
        if (isVavooProviderName(providerName)) {
            "Vavoo"
        } else {
            providerName
        }

    private fun configurableDomainDefault(
        providerName: String?,
    ): String? {
        // FILMO_REDIRECT_SETTINGS_V1
        if (providerName == "Filmo") return "https://filmo.to/"

        if (isVavooProviderName(providerName)) {
            return ""
        }

        return when (providerName) {
            "AniWorld" -> "https://aniworld.to/"
            "Filmpalast" -> "https://filmpalast.to/"
            "HDFilme" -> "https://hdfilme.cafe/"
            "MEGAKino" -> "https://megakino12.com"
            "KinoGer" -> "https://kinoger.beer/"
            "Einschalten" -> "https://einschalten.in"
            "Kellerkino" -> "https://www.kellerkino.com/"
            "AnimeUnity" -> "https://www.animeunity.to"
            else -> null
        }
    }

    private fun bindGenericProviderDomain() {
        val provider =
            UserPreferences.currentProvider
                ?: return

        val defaultBaseUrl =
            configurableDomainDefault(
                provider.name
            ) ?: return

        val providerKey =
            configurableDomainPreferenceKey(
                provider.name
            )

        findPreference<EditTextPreference>(
            "provider_domain_generic"
        )?.apply {

            val displayDomain =
                UserPreferences.providerDomainForDisplay(
                    providerKey,
                    defaultBaseUrl,
                )

            summary = displayDomain

            val customDomain =
                UserPreferences.getProviderCustomDomain(
                    providerKey
                )

            text =
                customDomain.ifBlank {
                    null
                }

            setOnBindEditTextListener { editText ->
                editText.inputType =
                    InputType.TYPE_CLASS_TEXT or
                        InputType.TYPE_TEXT_VARIATION_URI

                editText.imeOptions =
                    EditorInfo.IME_ACTION_DONE

                editText.hint =
                    UserPreferences.providerDomainForDisplay(
                        providerKey,
                        defaultBaseUrl,
                    )
            }

            setOnPreferenceChangeListener {
                    preference,
                    newValue,
                ->

                val typed =
                    (newValue as String)
                        .trim()

                if (typed.isBlank()) {
                    UserPreferences
                        .resetProviderCustomDomain(
                            providerKey
                        )
                } else {
                    UserPreferences
                        .setProviderCustomDomain(
                            providerKey,
                            typed,
                        )
                }

                preference.summary =
                    UserPreferences
                        .providerDomainForDisplay(
                            providerKey,
                            defaultBaseUrl,
                        )

                ProviderChangeNotifier
                    .notifyProviderChanged()

                true
            }
        }

        findPreference<Preference>(
            "provider_domain_generic_reset"
        )?.setOnPreferenceClickListener {

            UserPreferences
                .resetProviderCustomDomain(
                    providerKey
                )

            findPreference<EditTextPreference>(
                "provider_domain_generic"
            )?.apply {
                text = null

                summary =
                    UserPreferences
                        .providerDomainForDisplay(
                            providerKey,
                            defaultBaseUrl,
                        )
            }

            ProviderChangeNotifier
                .notifyProviderChanged()

            Toast.makeText(
                requireContext(),
                getString(
                    R.string
                        .settings_provider_domain_reset_done
                ),
                Toast.LENGTH_SHORT,
            ).show()

            true
        }
    }

    private fun updateProviderVisibilityState() {
        val isStreamingCommunity = UserPreferences.currentProvider is StreamingCommunityProvider
        val isSerienStream = false
        val isMoflix = false
        val isCuevana = UserPreferences.currentProvider?.name == "Cuevana 3"
        val isPoseidon = UserPreferences.currentProvider?.name == "Poseidonhd2"
        val isAnimeOnlineNinja = false
        val hasGenericDomain =
            configurableDomainDefault(UserPreferences.currentProvider?.name) != null
        val hasConfigProvider = UserPreferences.currentProvider is ProviderConfigUrl
        val hasSpecificOptions =
            isStreamingCommunity ||
                isSerienStream ||
                isMoflix ||
                isCuevana ||
                isPoseidon ||
                isAnimeOnlineNinja ||
                hasGenericDomain

        findPreference<PreferenceCategory>("pc_generic_provider_domain_settings")?.isVisible =
            hasGenericDomain

        findPreference<Preference>(
            "provider_domain_generic_next"
        )?.isVisible =
            isVavooProviderName(
                UserPreferences.currentProvider?.name
            )
        findPreference<PreferenceCategory>("pc_streamingcommunity_settings")?.isVisible = isStreamingCommunity
        findPreference<PreferenceCategory>("pc_serienstream_settings")?.isVisible = isSerienStream
        findPreference<PreferenceCategory>("pc_moflix_settings")?.isVisible = isMoflix
        findPreference<PreferenceCategory>("pc_cuevana_settings")?.isVisible = isCuevana
        findPreference<PreferenceCategory>("pc_poseidon_settings")?.isVisible = isPoseidon
        findPreference<PreferenceCategory>("pc_animeonlineninja_settings")?.isVisible = isAnimeOnlineNinja
        findPreference<PreferenceCategory>("pc_provider_empty_state")?.isVisible = !hasConfigProvider && !hasSpecificOptions
    }

    private fun bindProviderDomainCheckButtons() {

        fun showDomainCheckResult(
            result: Result<String>
        ) {
            result.onSuccess { message ->
                Toast.makeText(
                    requireContext(),
                    message,
                    Toast.LENGTH_LONG
                ).show()
            }.onFailure { error ->
                Toast.makeText(
                    requireContext(),
                    getString(
                        R.string.settings_provider_domain_check_failed,
                        error.message ?: error.javaClass.simpleName
                    ),
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        fun checkAndUpdate(
            currentValue: () -> String,
            saveValue: (String) -> Unit,
            saveHostOnly: Boolean = false,
            afterSave: (() -> Unit)? = null,
        ) {
            viewLifecycleOwner.lifecycleScope.launch {

                val result = runCatching {
                    val checked =
                        DomainRedirectChecker.check(currentValue())

                    if (!checked.changed) {
                        return@runCatching getString(
                            R.string.settings_provider_domain_check_current
                        )
                    }

                    val valueToSave =
                        if (saveHostOnly) {
                            DomainRedirectChecker.hostOnly(
                                checked.finalUrl
                            )
                        } else {
                            checked.finalUrl
                        }

                    saveValue(valueToSave)
                    afterSave?.invoke()

                    getString(
                        R.string.settings_provider_domain_check_updated,
                        valueToSave
                    )
                }

                showDomainCheckResult(result)
            }
        }

        // Generic provider domain check.
        // Vavoo is special: it always starts from vavoo.to and
        // walks the ordered mirror list until one responds.
        findPreference<Preference>(
            "provider_domain_generic_check"
        )?.setOnPreferenceClickListener {

            val provider =
                UserPreferences.currentProvider

            val providerName =
                provider?.name

            val defaultBase =
                configurableDomainDefault(
                    providerName
                )

            if (
                providerName.isNullOrBlank() ||
                defaultBase.isNullOrBlank()
            ) {
                Toast.makeText(
                    requireContext(),
                    getString(
                        R.string
                            .settings_provider_domain_check_failed,
                        "Provider not supported",
                    ),
                    Toast.LENGTH_LONG,
                ).show()

                return@setOnPreferenceClickListener true
            }

            val providerKey =
                configurableDomainPreferenceKey(
                    providerName
                )

            if (
                isVavooProviderName(
                    providerName
                )
            ) {
                viewLifecycleOwner
                    .lifecycleScope
                    .launch {

                        val result =
                            runCatching {
                                val selected =
                                    VavooDomainSelector
                                        .firstAvailable()

                                val newDomain =
                                    selected.finalUrl

                                UserPreferences
                                    .setProviderCustomDomain(
                                        providerKey,
                                        newDomain,
                                    )

                                findPreference<EditTextPreference>(
                                    "provider_domain_generic"
                                )?.apply {
                                    text = newDomain
                                    summary = newDomain
                                }

                                ProviderChangeNotifier
                                    .notifyProviderChanged()

                                getString(
                                    R.string
                                        .settings_provider_domain_check_updated,
                                    newDomain,
                                )
                            }

                        showDomainCheckResult(
                            result
                        )
                    }

                return@setOnPreferenceClickListener true
            }

            val current =
                UserPreferences
                    .resolveProviderBaseUrl(
                        providerKey,
                        defaultBase,
                    )

            checkAndUpdate(
                currentValue = {
                    current
                },
                saveValue = { newDomain ->
                    UserPreferences
                        .setProviderCustomDomain(
                            providerKey,
                            newDomain,
                        )
                },
                afterSave = {
                    findPreference<EditTextPreference>(
                        "provider_domain_generic"
                    )?.apply {
                        text =
                            UserPreferences
                                .providerDomainForDisplay(
                                    providerKey,
                                    defaultBase,
                                )

                        summary = text
                    }
                },
            )

            true
        }

        // VAVOO_DOMAIN_NEXT_BUTTON_V1
        findPreference<Preference>(
            "provider_domain_generic_next"
        )?.setOnPreferenceClickListener {

            val providerName =
                UserPreferences
                    .currentProvider
                    ?.name

            if (
                !isVavooProviderName(
                    providerName
                )
            ) {
                return@setOnPreferenceClickListener true
            }

            val current =
                ""

            viewLifecycleOwner
                .lifecycleScope
                .launch {

                    val result =
                        runCatching {
                            val selected =
                                VavooDomainSelector
                                    .nextAvailable(
                                        current
                                    )

                            val newDomain =
                                selected.finalUrl

                            UserPreferences
                                .setProviderCustomDomain(
                                    "Vavoo",
                                    newDomain,
                                )

                            findPreference<EditTextPreference>(
                                "provider_domain_generic"
                            )?.apply {
                                text = newDomain
                                summary = newDomain
                            }

                            ProviderChangeNotifier
                                .notifyProviderChanged()

                            getString(
                                R.string
                                    .settings_vavoo_domain_next_updated,
                                newDomain,
                            )
                        }

                    showDomainCheckResult(
                        result
                    )
                }

            true
        }

        // SerienStream stores only the hostname
        findPreference<Preference>(
            "provider_serienstream_domain_check"
        )?.setOnPreferenceClickListener {

            checkAndUpdate(
                currentValue = {
                    UserPreferences.serienstreamDomain
                },
                saveValue = { newDomain ->
                    UserPreferences.serienstreamDomain = newDomain
                },
                saveHostOnly = true,
                afterSave = {
                    findPreference<EditTextPreference>(
                        "provider_serienstream_domain"
                    )?.apply {
                        text =
                            UserPreferences.serienstreamDomain

                        summary =
                            UserPreferences.serienstreamDomain
                    }
                }
            )

            true
        }

        // Moflix stores only the hostname
        findPreference<Preference>(
            "provider_moflix_domain_check"
        )?.setOnPreferenceClickListener {

            checkAndUpdate(
                currentValue = {
                    UserPreferences.moflixDomain
                },
                saveValue = { newDomain ->
                    UserPreferences.moflixDomain = newDomain
                },
                saveHostOnly = true,
                afterSave = {
                    findPreference<EditTextPreference>(
                        "provider_moflix_domain"
                    )?.apply {
                        text =
                            UserPreferences.moflixDomain

                        summary =
                            UserPreferences.moflixDomain
                    }
                }
            )

            true
        }
    }

    private fun bindAnimeOnlineNinjaPreferredServer() {
        val preference = findPreference<ListPreference>("provider_animeonlineninja_preferred_server") ?: return
        val currentValue = UserPreferences.getProviderCache(
            UserPreferences.currentProvider!!,
            UserPreferences.PROVIDER_PREFERRED_SERVER
        )
        preference.value = currentValue
        preference.summary = preference.entries
            ?.getOrNull(preference.findIndexOfValue(currentValue))
            ?: getString(R.string.settings_provider_animeonlineninja_preferred_server_summary)
        preference.setOnPreferenceChangeListener { pref, newValue ->
            val value = (newValue as String).trim()
            UserPreferences.setProviderCache(
                UserPreferences.currentProvider!!,
                UserPreferences.PROVIDER_PREFERRED_SERVER,
                value
            )
            if (pref is ListPreference) {
                pref.summary = pref.entries?.getOrNull(pref.findIndexOfValue(value))
                    ?: getString(R.string.settings_provider_animeonlineninja_preferred_server_summary)
            }
            true
        }
    }

    private fun setupParentalControlPreferences() {
        val pinPreference = findPreference<EditTextPreference>("PARENTAL_CONTROL_PIN")
        val adminPinPreference = findPreference<EditTextPreference>("PARENTAL_CONTROL_ADMIN_PIN")
        val removePinPreference = findPreference<Preference>("PARENTAL_CONTROL_REMOVE_PIN")
        val removeAdminPinPreference = findPreference<Preference>("PARENTAL_CONTROL_REMOVE_ADMIN_PIN")
        val maxAgePreference = findPreference<ListPreference>("PARENTAL_CONTROL_MAX_AGE")
        val unlockPreference = findPreference<Preference>("PARENTAL_CONTROL_UNLOCK")

        fun bindPinEditText(editText: android.widget.EditText) {
            editText.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            editText.imeOptions = EditorInfo.IME_ACTION_DONE
            editText.hint = getString(R.string.settings_parental_pin_hint)
            editText.setText("")
        }

        pinPreference?.setOnBindEditTextListener(::bindPinEditText)
        adminPinPreference?.setOnBindEditTextListener(::bindPinEditText)

        pinPreference?.setOnPreferenceClickListener {
            showParentalPinEditor(maxAgePreference)
            true
        }

        adminPinPreference?.setOnPreferenceClickListener {
            showAdminPinEditor()
            true
        }

        removePinPreference?.setOnPreferenceClickListener {
            val removePin = {
                if (UserPreferences.saveParentalControlPin("")) {
                    UserPreferences.parentalControlMaxAge = null
                    maxAgePreference?.value = ""
                    UserPreferences.unlockParentalControls()
                    Toast.makeText(requireContext(), getString(R.string.settings_parental_pin_removed), Toast.LENGTH_SHORT).show()
                    ProviderChangeNotifier.notifyProviderChanged()
                    updateParentalControlPreferenceState()
                }
            }
            if (UserPreferences.parentalControlAdminPin.isNotBlank()) {
                changeAdminSettingWithPinCheck(removePin)
            } else {
                changeParentalSettingWithPinCheck(removePin)
            }
            true
        }

        removeAdminPinPreference?.setOnPreferenceClickListener {
            changeAdminSettingWithPinCheck {
                UserPreferences.parentalControlAdminPin = ""
                Toast.makeText(requireContext(), getString(R.string.settings_parental_admin_pin_removed), Toast.LENGTH_SHORT).show()
                updateParentalControlPreferenceState()
            }
            true
        }

        maxAgePreference?.setOnPreferenceChangeListener { _, newValue ->
            if (!UserPreferences.enableTmdb) return@setOnPreferenceChangeListener false
            if (UserPreferences.parentalControlPin.isBlank()) {
                Toast.makeText(requireContext(), getString(R.string.settings_parental_set_pin_first), Toast.LENGTH_SHORT).show()
                return@setOnPreferenceChangeListener false
            }

            val newMaxAgeValue = newValue as String
            val newMaxAge = newMaxAgeValue.toIntOrNull()

            changeParentalSettingWithPinCheck {
                UserPreferences.parentalControlMaxAge = newMaxAge
                maxAgePreference.value = newMaxAgeValue
                Toast.makeText(requireContext(), getString(R.string.settings_parental_max_age_saved), Toast.LENGTH_SHORT).show()
                ProviderChangeNotifier.notifyProviderChanged()
                updateParentalControlPreferenceState()
            }

            false
        }

        unlockPreference?.setOnPreferenceClickListener {
            if (UserPreferences.parentalControlAdminPin.isBlank()) {
                Toast.makeText(requireContext(), getString(R.string.settings_parental_set_admin_pin_first), Toast.LENGTH_SHORT).show()
            } else {
                promptForAdminPin {
                    UserPreferences.unlockParentalControls()
                    Toast.makeText(requireContext(), getString(R.string.settings_parental_unlocked), Toast.LENGTH_SHORT).show()
                    updateParentalControlPreferenceState()
                }
            }
            true
        }

        updateParentalControlPreferenceState()
    }

    private fun updateParentalControlPreferenceState() {
        val tmdbEnabled = UserPreferences.enableTmdb
        val pinPreference = findPreference<EditTextPreference>("PARENTAL_CONTROL_PIN")
        val adminPinPreference = findPreference<EditTextPreference>("PARENTAL_CONTROL_ADMIN_PIN")
        val removePinPreference = findPreference<Preference>("PARENTAL_CONTROL_REMOVE_PIN")
        val removeAdminPinPreference = findPreference<Preference>("PARENTAL_CONTROL_REMOVE_ADMIN_PIN")
        val maxAgePreference = findPreference<ListPreference>("PARENTAL_CONTROL_MAX_AGE")
        val unlockPreference = findPreference<Preference>("PARENTAL_CONTROL_UNLOCK")
        val isLocked = UserPreferences.isParentalControlTemporarilyLocked || UserPreferences.parentalControlHardLocked

        pinPreference?.apply {
            isEnabled = tmdbEnabled && !isLocked
            summary = when {
                !tmdbEnabled -> getString(R.string.settings_parental_requires_tmdb)
                UserPreferences.parentalControlHardLocked -> getString(R.string.settings_parental_locked_hard)
                UserPreferences.isParentalControlTemporarilyLocked -> getString(
                    R.string.settings_parental_locked_temporary,
                    lockRemainingMinutes()
                )
                UserPreferences.parentalControlPin.isBlank() -> getString(R.string.settings_parental_pin_not_set)
                else -> getString(R.string.settings_parental_pin_set)
            }
        }

        adminPinPreference?.apply {
            isEnabled = tmdbEnabled
            summary = when {
                !tmdbEnabled -> getString(R.string.settings_parental_requires_tmdb)
                UserPreferences.parentalControlAdminPin.isBlank() -> getString(R.string.settings_parental_admin_pin_not_set)
                else -> getString(R.string.settings_parental_admin_pin_set)
            }
        }

        removePinPreference?.apply {
            isVisible = tmdbEnabled && UserPreferences.parentalControlPin.isNotBlank()
            isEnabled = !isLocked
        }

        removeAdminPinPreference?.apply {
            isVisible = tmdbEnabled && UserPreferences.parentalControlAdminPin.isNotBlank()
            isEnabled = true
        }

        maxAgePreference?.apply {
            isEnabled = tmdbEnabled && !isLocked
            value = UserPreferences.parentalControlMaxAge?.toString().orEmpty()
            summary = when {
                !tmdbEnabled -> getString(R.string.settings_parental_requires_tmdb)
                UserPreferences.parentalControlHardLocked -> getString(R.string.settings_parental_locked_hard)
                UserPreferences.isParentalControlTemporarilyLocked -> getString(
                    R.string.settings_parental_locked_temporary,
                    lockRemainingMinutes()
                )
                UserPreferences.parentalControlPin.isBlank() -> getString(R.string.settings_parental_set_pin_first)
                UserPreferences.parentalControlMaxAge == null -> getString(R.string.settings_parental_max_age_disabled)
                else -> "${UserPreferences.parentalControlMaxAge}+"
            }
        }

        unlockPreference?.apply {
            isVisible = isLocked
            isEnabled = tmdbEnabled && UserPreferences.parentalControlAdminPin.isNotBlank()
            summary = when {
                UserPreferences.parentalControlAdminPin.isBlank() -> getString(R.string.settings_parental_set_admin_pin_first)
                UserPreferences.parentalControlHardLocked -> getString(R.string.settings_parental_locked_hard)
                UserPreferences.isParentalControlTemporarilyLocked -> getString(
                    R.string.settings_parental_locked_temporary,
                    lockRemainingMinutes()
                )
                else -> getString(R.string.settings_parental_unlock_summary)
            }
        }
    }

    private fun changeParentalSettingWithPinCheck(onVerified: () -> Unit) {
        when {
            UserPreferences.parentalControlHardLocked -> {
                Toast.makeText(requireContext(), getString(R.string.settings_parental_locked_hard), Toast.LENGTH_SHORT).show()
                updateParentalControlPreferenceState()
                return
            }
            UserPreferences.isParentalControlTemporarilyLocked -> {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.settings_parental_locked_temporary, lockRemainingMinutes()),
                    Toast.LENGTH_SHORT
                ).show()
                updateParentalControlPreferenceState()
                return
            }
        }

        val currentPin = UserPreferences.parentalControlPin
        if (currentPin.isBlank()) {
            onVerified()
            return
        }

        promptForPin(
            titleRes = R.string.settings_parental_enter_current_pin_title,
            messageRes = R.string.settings_parental_enter_current_pin_message,
            onSubmit = { enteredPin ->
                if (enteredPin == currentPin) {
                    UserPreferences.registerParentalPinSuccess()
                    onVerified()
                    null
                } else {
                    UserPreferences.registerParentalPinFailure()
                    updateParentalControlPreferenceState()
                    when {
                        UserPreferences.parentalControlHardLocked -> R.string.settings_parental_locked_hard
                        UserPreferences.isParentalControlTemporarilyLocked -> R.string.settings_parental_locked_temporary
                        else -> R.string.settings_parental_invalid_pin
                    }.let { failureMessageRes ->
                        if (failureMessageRes == R.string.settings_parental_locked_temporary) {
                            getString(failureMessageRes, lockRemainingMinutes())
                        } else {
                            getString(failureMessageRes)
                        }
                    }
                }
            }
        )
    }

    private fun changeAdminSettingWithPinCheck(onVerified: () -> Unit) {
        val currentAdminPin = UserPreferences.parentalControlAdminPin
        if (currentAdminPin.isBlank()) {
            onVerified()
            return
        }

        promptForAdminPin(onVerified)
    }

    private fun promptForAdminPin(onVerified: () -> Unit) {
        val currentAdminPin = UserPreferences.parentalControlAdminPin
        if (currentAdminPin.isBlank()) {
            Toast.makeText(requireContext(), getString(R.string.settings_parental_set_admin_pin_first), Toast.LENGTH_SHORT).show()
            return
        }

        promptForPin(
            titleRes = R.string.settings_parental_enter_admin_pin_title,
            messageRes = R.string.settings_parental_enter_admin_pin_message,
            onSubmit = { enteredPin ->
                if (enteredPin == currentAdminPin) {
                    UserPreferences.unlockParentalControls()
                    onVerified()
                    null
                } else {
                    getString(R.string.settings_parental_invalid_admin_pin)
                }
            }
        )
    }

    private fun showParentalPinEditor(maxAgePreference: ListPreference?) {
        if (!UserPreferences.enableTmdb) {
            Toast.makeText(requireContext(), getString(R.string.settings_parental_requires_tmdb), Toast.LENGTH_SHORT).show()
            return
        }

        val showEditor = {
            promptForPinValue(
                titleRes = R.string.settings_parental_pin_title,
                messageRes = if (UserPreferences.parentalControlPin.isBlank()) {
                    R.string.settings_parental_set_new_pin_message
                } else {
                    R.string.settings_parental_change_pin_message
                },
                allowBlank = UserPreferences.parentalControlPin.isNotBlank(),
                onSubmit = { newPin ->
                    when {
                        newPin.isBlank() -> {
                            if (UserPreferences.saveParentalControlPin("")) {
                                UserPreferences.parentalControlMaxAge = null
                                maxAgePreference?.value = ""
                                UserPreferences.unlockParentalControls()
                                Toast.makeText(requireContext(), getString(R.string.settings_parental_pin_removed), Toast.LENGTH_SHORT).show()
                                ProviderChangeNotifier.notifyProviderChanged()
                                updateParentalControlPreferenceState()
                            }
                            null
                        }
                        newPin.length < 4 -> getString(R.string.settings_parental_pin_too_short)
                        else -> {
                            if (UserPreferences.saveParentalControlPin(newPin)) {
                                Toast.makeText(requireContext(), getString(R.string.settings_parental_pin_saved), Toast.LENGTH_SHORT).show()
                                ProviderChangeNotifier.notifyProviderChanged()
                                updateParentalControlPreferenceState()
                            }
                            null
                        }
                    }
                }
            )
        }
        if (UserPreferences.parentalControlAdminPin.isNotBlank()) {
            changeAdminSettingWithPinCheck(showEditor)
        } else {
            changeParentalSettingWithPinCheck(showEditor)
        }
    }

    private fun showAdminPinEditor() {
        changeAdminSettingWithPinCheck {
            promptForPinValue(
                titleRes = R.string.settings_parental_admin_pin_title,
                messageRes = if (UserPreferences.parentalControlAdminPin.isBlank()) {
                    R.string.settings_parental_set_new_admin_pin_message
                } else {
                    R.string.settings_parental_change_admin_pin_message
                },
                allowBlank = UserPreferences.parentalControlAdminPin.isNotBlank(),
                onSubmit = { newPin ->
                    when {
                        newPin.isBlank() -> {
                            UserPreferences.parentalControlAdminPin = ""
                            Toast.makeText(requireContext(), getString(R.string.settings_parental_admin_pin_removed), Toast.LENGTH_SHORT).show()
                            updateParentalControlPreferenceState()
                            null
                        }
                        newPin.length < 4 -> getString(R.string.settings_parental_pin_too_short)
                        else -> {
                            UserPreferences.parentalControlAdminPin = newPin
                            Toast.makeText(requireContext(), getString(R.string.settings_parental_admin_pin_saved), Toast.LENGTH_SHORT).show()
                            updateParentalControlPreferenceState()
                            null
                        }
                    }
                }
            )
        }
    }

    private fun promptForPin(
        titleRes: Int,
        messageRes: Int,
        onSubmit: (String) -> String?,
    ) {
        val input = android.widget.EditText(requireContext()).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            imeOptions = EditorInfo.IME_ACTION_DONE
            hint = getString(R.string.settings_parental_pin_hint)
        }

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(titleRes)
            .setMessage(messageRes)
            .setView(input)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                input.error = null
                val errorMessage = onSubmit(input.text?.toString()?.trim().orEmpty())
                if (errorMessage == null) {
                    dialog.dismiss()
                } else {
                    input.setText("")
                    input.error = errorMessage
                    input.requestFocus()
                }
            }
        }

        dialog.show()
    }

    private fun promptForPinValue(
        titleRes: Int,
        messageRes: Int,
        allowBlank: Boolean,
        onSubmit: (String) -> String?,
    ) {
        val input = android.widget.EditText(requireContext()).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            imeOptions = EditorInfo.IME_ACTION_DONE
            hint = getString(R.string.settings_parental_pin_hint)
        }

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(titleRes)
            .setMessage(messageRes)
            .setView(input)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                input.error = null
                val newValue = input.text?.toString()?.trim().orEmpty()
                if (newValue.isBlank() && !allowBlank) {
                    input.setText("")
                    input.error = getString(R.string.settings_parental_pin_too_short)
                    input.requestFocus()
                    return@setOnClickListener
                }

                val errorMessage = onSubmit(newValue)
                if (errorMessage == null) {
                    dialog.dismiss()
                } else {
                    input.setText("")
                    input.error = errorMessage
                    input.requestFocus()
                }
            }
        }

        dialog.show()
    }

    private fun showProfileManagementDialog() {
        lifecycleScope.launch {
            val profiles = ProfileManager.getAllProfiles()
            val profileNames = profiles.map { it.name }.toTypedArray()

            AlertDialog.Builder(requireContext())
                .setTitle(R.string.profile_manage_title)
                .setItems(profileNames) { _, which ->
                    if (which < profiles.size) {
                        showProfileActionsDialog(profiles[which], profiles.size)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun showProfileActionsDialog(profile: com.streamflixreborn.streamflix.models.Profile, profileCount: Int = 1) {
        val items = mutableListOf<String>().apply {
            add(getString(R.string.profile_action_switch))
            add(getString(R.string.profile_action_rename))
            add(getString(R.string.profile_action_color))
            if (profileCount > 1) {
                add(getString(R.string.profile_action_delete))
            }
        }

        AlertDialog.Builder(requireContext())
            .setTitle(profile.name)
            .setItems(items.toTypedArray()) { _, which ->
                when (items[which]) {
                    getString(R.string.profile_action_switch) -> {
                        if (profile.id == ProfileManager.activeProfileId) {
                            switchProfileFromSettings(profile)
                        } else {
                            ProfileSwitchPinGuard.verifyCurrentProfile(requireContext()) {
                                switchProfileFromSettings(profile)
                            }
                        }
                    }
                    getString(R.string.profile_action_rename) -> showRenameProfileDialog(profile)
                    getString(R.string.profile_action_color) -> showProfileColorDialog(profile)
                    getString(R.string.profile_action_delete) -> showDeleteProfileDialog(profile)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun switchProfileFromSettings(profile: com.streamflixreborn.streamflix.models.Profile) {
        if (!isAdded) return
        val oldProfileId = ProfileManager.activeProfileId
        val oldLang = oldProfileId?.let { AppLanguageManager.getProfileLanguage(requireContext(), it) }
        val oldTheme = UserPreferences.selectedTheme
        lifecycleScope.launch {
            ProfileManager.switchToProfile(profile.id)
            val newLang = AppLanguageManager.getProfileLanguage(requireContext(), profile.id)
            val newTheme = UserPreferences.selectedTheme
            if (newLang != (oldLang ?: AppLanguageManager.SYSTEM_LANGUAGE) || newTheme != oldTheme) {
                requireActivity().apply {
                    finish()
                    startActivity(Intent(this, this::class.java))
                }
            } else {
                (requireActivity() as? MainMobileActivity)?.recreateProviderHome()
            }
        }
    }

    private fun showCreateProfileDialog() {
        val input = android.widget.EditText(requireContext()).apply {
            hint = getString(R.string.profile_name_hint)
        }

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.profile_create_title)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotBlank()) {
                    lifecycleScope.launch {
                        ProfileManager.createProfile(name)
                        Toast.makeText(requireContext(), R.string.profile_created, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showRenameProfileDialog(profile: com.streamflixreborn.streamflix.models.Profile) {
        val input = android.widget.EditText(requireContext()).apply {
            setText(profile.name)
            hint = getString(R.string.profile_name_hint)
        }

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.profile_rename_title)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotBlank()) {
                    lifecycleScope.launch {
                        ProfileManager.renameProfile(profile.id, newName)
                        Toast.makeText(requireContext(), R.string.profile_renamed, Toast.LENGTH_SHORT).show()
                        updateOverviewLabels()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showProfileColorDialog(profile: com.streamflixreborn.streamflix.models.Profile) {
        ProfileColorPicker.show(requireContext(), profile.avatarColor) { color ->
            lifecycleScope.launch {
                if (ProfileManager.setProfileColor(profile.id, color)) {
                    Toast.makeText(requireContext(), R.string.profile_color_changed, Toast.LENGTH_SHORT).show()
                    if (profile.id == ProfileManager.activeProfileId) requireActivity().recreate()
                }
            }
        }
    }

    private fun showDeleteProfileDialog(profile: com.streamflixreborn.streamflix.models.Profile) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.profile_delete_title)
            .setMessage(getString(R.string.profile_delete_message, profile.name))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                lifecycleScope.launch {
                    val success = ProfileManager.deleteProfile(profile.id)
                    if (success) {
                        Toast.makeText(requireContext(), R.string.profile_deleted, Toast.LENGTH_SHORT).show()
                        if (ProfileManager.activeProfile?.id == profile.id) {
                            requireActivity().apply {
                                finish()
                                startActivity(Intent(this, this::class.java))
                            }
                        }
                    } else {
                        Toast.makeText(requireContext(), R.string.profile_delete_error, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun lockRemainingMinutes(): Int {
        val millis = UserPreferences.parentalControlLockRemainingMillis
        return ((millis + 60_000L - 1L) / 60_000L).toInt().coerceAtLeast(1)
    }

    private suspend fun performBackupExport(uri: Uri) {
        withBackupLoading(R.string.backup_export_title) {
            val jsonData = withContext(Dispatchers.IO) {
                backupRestoreManager.exportUserData()
            }
            if (jsonData != null) {
                try {
                    requireContext().contentResolver.openOutputStream(uri)?.use { outputStream ->
                        outputStream.writer().use { it.write(jsonData) }
                        Toast.makeText(requireContext(), getString(R.string.backup_export_success), Toast.LENGTH_LONG).show()
                    }
                } catch (e: IOException) {
                    Toast.makeText(requireContext(), getString(R.string.backup_export_error_write), Toast.LENGTH_LONG).show()
                    Log.e("BackupExportMobile", "Error writing backup file", e)
                }
            } else {
                Toast.makeText(requireContext(), getString(R.string.backup_data_not_generated), Toast.LENGTH_LONG).show()
            }
        }
    }

    private suspend fun performBackupImport(uri: Uri) {
        withBackupLoading(R.string.backup_import_title) {
            try {
                val jsonData = withContext(Dispatchers.IO) {
                    val stringBuilder = StringBuilder()
                    requireContext().contentResolver.openInputStream(uri)?.use { inputStream ->
                        inputStream.bufferedReader().useLines { lines ->
                            lines.forEach { stringBuilder.append(it) }
                        }
                    }
                    stringBuilder.toString()
                }
                if (jsonData.isNotBlank()) {
                    val metadata = withContext(Dispatchers.IO) {
                        backupRestoreManager.parseExportMetadata(jsonData)
                    }

                    val activeProfile = ProfileManager.activeProfile
                    if (metadata != null && metadata.profileId != null && activeProfile != null
                        && metadata.profileId != activeProfile.id
                    ) {
                        val shouldContinue = withContext(Dispatchers.Main) {
                            suspendCoroutine { cont ->
                                AlertDialog.Builder(requireContext())
                                    .setTitle(R.string.profile_import_warning_title)
                                    .setMessage(getString(R.string.profile_import_warning_message, metadata.profileName ?: metadata.profileId, activeProfile.name))
                                    .setPositiveButton(android.R.string.ok) { _, _ -> cont.resume(true) }
                                    .setNegativeButton(android.R.string.cancel) { _, _ -> cont.resume(false) }
                                    .show()
                            }
                        }
                        if (!shouldContinue) return@withBackupLoading
                    }

                    val success = withContext(Dispatchers.IO) {
                        backupRestoreManager.importUserData(jsonData)
                    }
                    if (success) {
                        Toast.makeText(requireContext(), getString(R.string.backup_import_success), Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(requireContext(), getString(R.string.backup_import_error), Toast.LENGTH_LONG).show()
                    }
                } else {
                    Toast.makeText(requireContext(), getString(R.string.backup_import_empty_file), Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Toast.makeText(requireContext(), getString(R.string.backup_import_read_error), Toast.LENGTH_LONG).show()
                Log.e("BackupImportMobile", "Error reading/processing backup file", e)
            }
        }
    }

    private suspend fun performDatabaseBackupExport(uri: Uri) {
        withBackupLoading(R.string.backup_db_export_title) {
            val zipData = withContext(Dispatchers.IO) {
                backupRestoreManager.exportDatabaseZip()
            }
            if (zipData != null) {
                try {
                    requireContext().contentResolver.openOutputStream(uri)?.use { outputStream ->
                        outputStream.write(zipData)
                        Toast.makeText(requireContext(), getString(R.string.backup_db_export_success), Toast.LENGTH_LONG).show()
                    }
                } catch (e: IOException) {
                    Toast.makeText(requireContext(), getString(R.string.backup_export_error_write), Toast.LENGTH_LONG).show()
                    Log.e("BackupExportMobile", "Error writing database backup file", e)
                }
            } else {
                Toast.makeText(requireContext(), getString(R.string.backup_data_not_generated), Toast.LENGTH_LONG).show()
            }
        }
    }

    private suspend fun performDatabaseBackupImport(uri: Uri) {
        withBackupLoading(R.string.backup_db_import_title) {
            try {
                val zipBytes = withContext(Dispatchers.IO) {
                    requireContext().contentResolver.openInputStream(uri)?.use { it.readBytes() }
                }
                if (zipBytes == null || zipBytes.isEmpty()) {
                    Toast.makeText(requireContext(), getString(R.string.backup_import_empty_file), Toast.LENGTH_LONG).show()
                    return@withBackupLoading
                }
                val success = withContext(Dispatchers.IO) {
                    backupRestoreManager.importDatabaseZip(zipBytes)
                }
                Toast.makeText(
                    requireContext(),
                    if (success) getString(R.string.backup_db_import_success) else getString(R.string.backup_import_error),
                    Toast.LENGTH_LONG
                ).show()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), getString(R.string.backup_import_read_error), Toast.LENGTH_LONG).show()
                Log.e("BackupImportMobile", "Error reading/processing database backup file", e)
            }
        }
    }

    private suspend fun <T> withBackupLoading(titleRes: Int, block: suspend () -> T): T {
        showBackupLoadingDialog(titleRes)
        return try {
            block()
        } finally {
            hideBackupLoadingDialog()
        }
    }

    private fun showBackupLoadingDialog(titleRes: Int) {
        if (!isAdded) return
        if (backupLoadingDialog?.isShowing == true) {
            backupLoadingDialog?.setTitle(titleRes)
            return
        }

        val contentView = LayoutInflater.from(requireContext()).inflate(
            R.layout.layout_is_loading_mobile,
            null
        )
        contentView.findViewById<android.widget.TextView>(R.id.tv_is_loading_error)?.visibility = View.GONE
        contentView.findViewById<Group>(R.id.g_is_loading_retry)?.visibility = View.GONE

        backupLoadingDialog = AlertDialog.Builder(requireContext())
            .setTitle(titleRes)
            .setView(contentView)
            .setCancelable(false)
            .create()
            .apply {
                setCanceledOnTouchOutside(false)
                show()
            }
    }

    private fun hideBackupLoadingDialog() {
        backupLoadingDialog?.dismiss()
        backupLoadingDialog = null
    }

    override fun onResume() {
        super.onResume()
        applyScreenTitle()
        updateOverviewLabels()
        updateProviderVisibilityState()

        findPreference<EditTextPreference>("provider_streamingcommunity_domain")?.apply {
            val currentValue = UserPreferences.streamingcommunityDomain
            summary = currentValue
            if (currentValue == DEFAULT_DOMAIN_VALUE || currentValue == PREFS_ERROR_VALUE) {
                text = null
            } else {
                text = currentValue
            }
        }

        findPreference<EditTextPreference>("TMDB_API_KEY")?.apply {
            summary = if (UserPreferences.tmdbApiKey.isEmpty()) getString(R.string.settings_tmdb_api_key_summary) else UserPreferences.tmdbApiKey
            text = UserPreferences.tmdbApiKey
        }

        findPreference<EditTextPreference>("SUBDL_API_KEY")?.apply {
            summary = if (UserPreferences.subdlApiKey.isEmpty()) getString(R.string.settings_subdl_api_key_summary) else UserPreferences.subdlApiKey
            text = UserPreferences.subdlApiKey
        }

        findPreference<ListPreference>("p_doh_provider_url")?.apply {
            summary = entry
        }

        findPreference<ListPreference>("APP_LANGUAGE")?.value =
            AppLanguageManager.getSelectedLanguage(requireContext())

        findPreference<SwitchPreference>("AUTOPLAY")?.isChecked = UserPreferences.autoplay
        findPreference<SwitchPreference>("FORCE_EXTRA_BUFFERING")?.isChecked = UserPreferences.forceExtraBuffering
        findPreference<SwitchPreference>("PLAYER_GESTURES")?.isChecked = UserPreferences.playerGestures
        findPreference<SwitchPreference>("KEEP_SCREEN_ON_WHEN_PAUSED")?.isChecked = UserPreferences.keepScreenOnWhenPaused
        findPreference<SwitchPreferenceCompat>("ENABLE_TMDB")?.isChecked = UserPreferences.enableTmdb
        updateParentalControlPreferenceState()
    }
}
