package com.streamflixreborn.streamflix.fragments.settings.controllers

import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.Preference
import androidx.preference.TwoStatePreference
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.providers.StreamingCommunityProvider
import com.streamflixreborn.streamflix.providers.TmdbProvider
import com.streamflixreborn.streamflix.utils.DnsResolver
import com.streamflixreborn.streamflix.utils.ProviderChangeNotifier
import com.streamflixreborn.streamflix.utils.UserPreferences
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Settings > Sources: provider list, source health, DNS over HTTPS, TMDb and the
 * StreamingCommunity domain (the only remaining provider with its own setting).
 */
class SourcesController(
    private val host: SettingsHost,
    private val parental: ParentalControlController,
) : SettingsController {

    override fun bind() {
        host.onClick(KEY_PROVIDERS) { host.navigate(R.id.providers) }
        host.onClick(KEY_HEALTH) { host.navigate(R.id.provider_health) }
        host.bindSwitch(KEY_TORRENT, { UserPreferences.torrentEnabled }) { UserPreferences.torrentEnabled = it }
        bindDoh()
        bindTmdb()
        bindStreamingCommunity()
        refresh()
    }

    override fun refresh() {
        host.find<Preference>(KEY_PROVIDERS)?.summary = UserPreferences.currentProvider?.let { provider ->
            val name = if (provider is TmdbProvider) {
                "TMDb (${Locale.forLanguageTag(provider.language).displayLanguage})"
            } else {
                provider.name
            }
            host.string(R.string.settings_sources_providers_summary, name)
        } ?: host.string(R.string.settings_sources_providers_none)

        host.find<ListPreference>(KEY_DOH)?.apply {
            value = UserPreferences.dohProviderUrl
            summary = entry
        }
        host.refreshSwitch(KEY_TORRENT) { UserPreferences.torrentEnabled }
        host.refreshSwitch(KEY_ENABLE_TMDB) { UserPreferences.enableTmdb }
        host.find<EditTextPreference>(KEY_TMDB_API)?.apply {
            text = UserPreferences.tmdbApiKey
            summary = UserPreferences.tmdbApiKey.ifEmpty { host.string(R.string.settings_tmdb_api_key_summary) }
        }
        refreshStreamingCommunity()
    }

    private fun bindDoh() {
        host.find<ListPreference>(KEY_DOH)?.setOnPreferenceChangeListener { pref, newValue ->
            val newUrl = newValue as String
            UserPreferences.dohProviderUrl = newUrl
            DnsResolver.setDnsUrl(newUrl)
            (pref as ListPreference).value = newUrl
            pref.summary = pref.entry
            if (UserPreferences.currentProvider is StreamingCommunityProvider) {
                rebuildStreamingCommunityAndRestart()
            } else {
                host.toast(R.string.doh_provider_updated, long = true)
            }
            false
        }
    }

    private fun bindTmdb() {
        host.find<TwoStatePreference>(KEY_ENABLE_TMDB)?.setOnPreferenceChangeListener { pref, newValue ->
            val enabled = newValue as Boolean
            val applyChange = {
                UserPreferences.enableTmdb = enabled
                (pref as TwoStatePreference).isChecked = enabled
                parental.refresh()
                ProviderChangeNotifier.notifyProviderChanged()
                host.toast(if (enabled) R.string.settings_enable_tmdb_enabled else R.string.settings_enable_tmdb_disabled)
            }
            // Turning TMDb off disables parental control, so it needs the PIN.
            if (!enabled && UserPreferences.parentalControlPin.isNotBlank()) {
                parental.changeParentalSettingWithPinCheck(applyChange)
            } else {
                applyChange()
            }
            false
        }

        host.find<MultiSelectListPreference>(KEY_TMDB_CATEGORIES)?.setOnPreferenceChangeListener { _, newValue ->
            @Suppress("UNCHECKED_CAST")
            UserPreferences.tmdbCategories = newValue as? Set<String> ?: emptySet()
            true
        }

        host.find<EditTextPreference>(KEY_TMDB_API)?.setOnPreferenceChangeListener { pref, newValue ->
            val newKey = (newValue as String).trim()
            UserPreferences.tmdbApiKey = newKey
            (pref as EditTextPreference).text = newKey
            pref.summary = newKey.ifEmpty { host.string(R.string.settings_tmdb_api_key_summary) }
            host.toast(if (newKey.isEmpty()) R.string.settings_tmdb_api_key_reset else R.string.settings_tmdb_api_key_success)
            false
        }
    }

    private fun bindStreamingCommunity() {
        host.find<EditTextPreference>(KEY_SC_DOMAIN)?.setOnPreferenceChangeListener { _, newValue ->
            val typed = (newValue as String).trim()
            val blocked = SC_BLOCKED_DOMAINS.any { typed.contains(it) }
            UserPreferences.streamingcommunityDomain = if (blocked || typed.isEmpty()) SC_DEFAULT_DOMAIN else typed
            refreshStreamingCommunity()
            if (blocked) host.toast(R.string.settings_streamingcommunity_domain_blocked, long = true)
            if (UserPreferences.currentProvider is StreamingCommunityProvider) rebuildStreamingCommunityAndRestart()
            false
        }
        host.onClick(KEY_SC_RESET) {
            UserPreferences.streamingcommunityDomain = SC_DEFAULT_DOMAIN
            refreshStreamingCommunity()
            host.toast(R.string.settings_streamingcommunity_domain_reset_done)
            if (UserPreferences.currentProvider is StreamingCommunityProvider) rebuildStreamingCommunityAndRestart()
        }
    }

    private fun refreshStreamingCommunity() {
        host.find<EditTextPreference>(KEY_SC_DOMAIN)?.apply {
            val current = UserPreferences.streamingcommunityDomain
            summary = current
            text = current.takeUnless { it == SC_DEFAULT_DOMAIN || it == PREFS_ERROR_VALUE }
        }
    }

    private fun rebuildStreamingCommunityAndRestart() {
        val provider = UserPreferences.currentProvider as? StreamingCommunityProvider ?: return
        host.scope.launch {
            provider.rebuildService()
            host.restartActivity()
        }
    }

    private companion object {
        const val KEY_PROVIDERS = "sources_providers"
        const val KEY_HEALTH = "sources_health"
        const val KEY_DOH = "p_doh_provider_url"
        const val KEY_TORRENT = "TORRENT_ENABLED"
        const val KEY_ENABLE_TMDB = "ENABLE_TMDB"
        const val KEY_TMDB_CATEGORIES = "TMDB_CATEGORIES"
        const val KEY_TMDB_API = "TMDB_API_KEY"
        const val KEY_SC_DOMAIN = "provider_streamingcommunity_domain"
        const val KEY_SC_RESET = "provider_streamingcommunity_domain_reset"

        const val SC_DEFAULT_DOMAIN = "streamingunity.fun"
        const val PREFS_ERROR_VALUE = "PREFS_NOT_INIT_ERROR"
        val SC_BLOCKED_DOMAINS = listOf(
            "streamingcommunityz.green", "streamingunity.club", "streamingunity.bike", "streamingcommunityz.buzz",
        )
    }
}
