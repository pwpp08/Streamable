package com.streamflixreborn.streamflix.fragments.queue

import android.content.Context
import androidx.annotation.StringRes
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.StreamFlixApp
import com.streamflixreborn.streamflix.utils.ProfileManager
import com.streamflixreborn.streamflix.utils.UserPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * Queue data that has no column in Room: the personal reaction to a title, and titles marked as
 * seen without being played here (from Swipe or "Mark as watched" on a show). Kept per profile and
 * provider as one JSON document, which also travels between devices through SettingsSync.
 */
object QueueStore {
    private const val PREFS = "queue_store"
    private const val REACTIONS = "r"
    private const val SEEN = "s"

    /** Best to worst; the order is the order of the rating buttons and filter chips. */
    enum class Reaction(val emoji: String, @StringRes val labelRes: Int) {
        LOVED("😍", R.string.queue_reaction_loved),
        LIKED("👍", R.string.queue_reaction_liked),
        OKAY("😐", R.string.queue_reaction_okay),
        DISLIKED("👎", R.string.queue_reaction_disliked),
        HATED("😡", R.string.queue_reaction_hated);

        companion object {
            fun of(name: String?): Reaction? = entries.firstOrNull { it.name == name }
        }
    }

    /** A title marked seen; enough to draw its card without the provider. */
    data class Seen(
        val key: String,
        val title: String,
        val poster: String?,
        val year: String?,
        val atMillis: Long,
    )

    private val _revision = MutableStateFlow(0)

    /** Bumps on every change, local or synced. */
    val revision: StateFlow<Int> = _revision.asStateFlow()

    fun key(isTv: Boolean, id: String) = "${if (isTv) "t" else "m"}:$id"

    fun reaction(key: String): Reaction? = Reaction.of(providerData().optJSONObject(REACTIONS)?.optString(key))

    fun reactions(): Map<String, Reaction> {
        val json = providerData().optJSONObject(REACTIONS) ?: return emptyMap()
        return json.keys().asSequence().mapNotNull { k -> Reaction.of(json.optString(k))?.let { k to it } }.toMap()
    }

    fun setReaction(key: String, reaction: Reaction?) = edit { data ->
        val json = data.optJSONObject(REACTIONS) ?: JSONObject().also { data.put(REACTIONS, it) }
        if (reaction == null) json.remove(key) else json.put(key, reaction.name)
    }

    fun seen(): List<Seen> {
        val json = providerData().optJSONObject(SEEN) ?: return emptyList()
        return json.keys().asSequence().mapNotNull { k ->
            val o = json.optJSONObject(k) ?: return@mapNotNull null
            Seen(
                key = k,
                title = o.optString("t"),
                poster = o.optString("p").ifBlank { null },
                year = o.optString("y").ifBlank { null },
                atMillis = o.optLong("at"),
            )
        }.toList()
    }

    fun isSeen(key: String): Boolean = providerData().optJSONObject(SEEN)?.has(key) == true

    fun setSeen(key: String, title: String, poster: String?, year: String?, seen: Boolean) = edit { data ->
        val json = data.optJSONObject(SEEN) ?: JSONObject().also { data.put(SEEN, it) }
        if (seen) {
            json.put(key, JSONObject().put("t", title).put("p", poster.orEmpty()).put("y", year.orEmpty()).put("at", System.currentTimeMillis()))
        } else {
            json.remove(key)
        }
    }

    /** The whole profile document, for SettingsSync. */
    fun exportForSync(): String = document().toString()

    fun importFromSync(value: String) {
        val parsed = runCatching { JSONObject(value) }.getOrNull() ?: return
        prefs().edit().putString(docKey(), parsed.toString()).apply()
        _revision.value++
    }

    private fun prefs() = StreamFlixApp.instance.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun docKey() = "doc_${ProfileManager.activeProfileId ?: "default"}"

    private fun providerName() = UserPreferences.currentProvider?.name.orEmpty()

    private fun document(): JSONObject =
        prefs().getString(docKey(), null)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()

    private fun providerData(): JSONObject = document().optJSONObject(providerName()) ?: JSONObject()

    @Synchronized
    private fun edit(block: (JSONObject) -> Unit) {
        val doc = document()
        val data = doc.optJSONObject(providerName()) ?: JSONObject().also { doc.put(providerName(), it) }
        block(data)
        prefs().edit().putString(docKey(), doc.toString()).apply()
        _revision.value++
    }
}
