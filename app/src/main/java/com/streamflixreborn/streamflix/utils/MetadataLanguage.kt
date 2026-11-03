package com.streamflixreborn.streamflix.utils

import com.streamflixreborn.streamflix.providers.TmdbProvider

/**
 * Language of TMDb metadata (titles, overviews, row names). Picks between TmdbProvider("en")
 * and TmdbProvider("hu"). Stored per profile; when TMDb is the current provider its language wins.
 */
object MetadataLanguage {
    const val ENGLISH = "en"
    const val HUNGARIAN = "hu"
    val supported = listOf(ENGLISH, HUNGARIAN)

    private const val PREF_KEY = "METADATA_LANGUAGE"

    fun get(): String {
        (runCatching { UserPreferences.currentProvider }.getOrNull() as? TmdbProvider)
            ?.language?.takeIf { it in supported }?.let { return it }
        return runCatching { UserPreferences.getProfilePreferenceString(PREF_KEY) }.getOrNull()
            ?.takeIf { it in supported } ?: ENGLISH
    }

    /**
     * Stores [language] and, when TMDb is the current provider, switches it to the matching
     * TmdbProvider. Returns true when the current provider changed (the caller should restart).
     */
    fun set(language: String): Boolean {
        val lang = language.takeIf { it in supported } ?: ENGLISH
        UserPreferences.setProfilePreferenceString(PREF_KEY, lang)
        val current = UserPreferences.currentProvider as? TmdbProvider ?: return false
        if (current.language == lang) return false
        UserPreferences.currentProvider = TmdbProvider(lang)
        return true
    }

    fun tmdbProvider(): TmdbProvider = TmdbProvider(get())
}
