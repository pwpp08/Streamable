package com.streamflixreborn.streamflix.sync

import android.content.Context
import com.streamflixreborn.streamflix.database.AppDatabase
import com.streamflixreborn.streamflix.providers.Provider
import com.streamflixreborn.streamflix.providers.TmdbProvider
import com.streamflixreborn.streamflix.utils.UserPreferences

/** Provider lookup and switching shared by sync features. */
object SyncProviders {
    fun all(): List<Provider> = (Provider.providers.keys +
        listOf("it", "en", "es", "de", "fr").map(::TmdbProvider)).distinctBy { it.name }

    fun byName(name: String?): Provider? {
        if (name.isNullOrBlank()) return null
        if (name.startsWith("TMDb (") && name.endsWith(")")) {
            return TmdbProvider(name.substringAfter("TMDb (").substringBefore(")"))
        }
        return all().firstOrNull { it.name == name }
    }

    /** Providers that have a database for [profileId]. */
    fun withDatabase(context: Context, profileId: String): List<Provider> = all().filter { provider ->
        context.getDatabasePath(AppDatabase.databaseNameFor(provider.name, profileId)).exists()
    }

    /**
     * Makes [providerName] the current provider (same mechanism as the
     * provider list: the setter resets the Room instance and notifies
     * ProviderChangeNotifier). Returns false when the provider is unknown.
     */
    fun switchTo(providerName: String?): Boolean {
        if (providerName.isNullOrBlank()) return true
        if (UserPreferences.currentProvider?.name == providerName) return true
        val provider = byName(providerName) ?: return false
        UserPreferences.currentProvider = provider
        return true
    }
}
