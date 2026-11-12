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

    /** When each reaction was last set or cleared (kept for cleared ones), per key. */
    private const val REACTION_TIMES = "rt"

    /** When a title was last un-marked as seen, per key; lets the removal win over an older copy. */
    private const val SEEN_REMOVED = "sx"

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
        val releasedMillis: Long? = null,
        val rating: Double? = null,
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
        val times = data.optJSONObject(REACTION_TIMES) ?: JSONObject().also { data.put(REACTION_TIMES, it) }
        times.put(key, System.currentTimeMillis())
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
                releasedMillis = o.optLong("rd", -1L).takeIf { it >= 0 },
                rating = o.optDouble("rt", -1.0).takeIf { it > 0 },
            )
        }.toList()
    }

    fun isSeen(key: String): Boolean = providerData().optJSONObject(SEEN)?.has(key) == true

    fun setSeen(
        key: String,
        title: String,
        poster: String?,
        year: String?,
        seen: Boolean,
        releasedMillis: Long? = null,
        rating: Double? = null,
    ) = edit { data ->
        val json = data.optJSONObject(SEEN) ?: JSONObject().also { data.put(SEEN, it) }
        val removed = data.optJSONObject(SEEN_REMOVED) ?: JSONObject().also { data.put(SEEN_REMOVED, it) }
        if (seen) {
            removed.remove(key)
            json.put(
                key,
                JSONObject().put("t", title).put("p", poster.orEmpty()).put("y", year.orEmpty())
                    .put("at", System.currentTimeMillis())
                    .apply {
                        if (releasedMillis != null) put("rd", releasedMillis)
                        if (rating != null && rating > 0) put("rt", rating)
                    },
            )
        } else {
            json.remove(key)
            removed.put(key, System.currentTimeMillis())
        }
    }

    /** The whole profile document, for SettingsSync. */
    fun exportForSync(): String = canonical(document()).toString()

    /** Same data with every object's keys sorted, so equal documents compare (and upload) equal. */
    internal fun canonical(value: JSONObject): JSONObject = JSONObject().also { out ->
        value.keys().asSequence().toSortedSet().forEach { key ->
            val child = value.get(key)
            out.put(key, if (child is JSONObject) canonical(child) else child)
        }
    }

    /** Merges a document from another device into this one: nothing either side added is lost. */
    @Synchronized
    fun importFromSync(value: String) {
        val remote = runCatching { JSONObject(value) }.getOrNull() ?: return
        val merged = merge(document(), remote)
        if (canonical(merged).toString() == canonical(document()).toString()) return
        prefs().edit().putString(docKey(), canonical(merged).toString()).apply()
        _revision.value++
    }

    /**
     * Union of two documents (each maps provider name to its data). Per title the newest change
     * wins: a reaction by its time, a seen title against the time it was un-marked.
     */
    internal fun merge(local: JSONObject, remote: JSONObject): JSONObject {
        val result = JSONObject()
        val providers = (local.keys().asSequence() + remote.keys().asSequence()).toSortedSet()
        for (provider in providers) {
            val a = local.optJSONObject(provider) ?: JSONObject()
            val b = remote.optJSONObject(provider) ?: JSONObject()
            result.put(provider, mergeProvider(a, b))
        }
        return result
    }

    private fun mergeProvider(a: JSONObject, b: JSONObject): JSONObject {
        val out = JSONObject()

        // Reactions: the side that changed a title last decides, a cleared one counts as a change.
        val reactions = JSONObject()
        val times = JSONObject()
        val keys = sortedSetOf<String>()
        for (src in listOf(a, b)) {
            src.optJSONObject(REACTIONS)?.keys()?.forEach { keys += it }
            src.optJSONObject(REACTION_TIMES)?.keys()?.forEach { keys += it }
        }
        for (key in keys) {
            val ta = a.optJSONObject(REACTION_TIMES)?.optLong(key, 0L) ?: 0L
            val tb = b.optJSONObject(REACTION_TIMES)?.optLong(key, 0L) ?: 0L
            val ra = a.optJSONObject(REACTIONS)?.optString(key)?.takeIf { it.isNotEmpty() }
            val rb = b.optJSONObject(REACTIONS)?.optString(key)?.takeIf { it.isNotEmpty() }
            val (winner, time) = when {
                ta > tb -> ra to ta
                tb > ta -> rb to tb
                else -> (ra ?: rb) to ta
            }
            if (winner != null) reactions.put(key, winner)
            if (time > 0) times.put(key, time)
        }
        out.put(REACTIONS, reactions)
        out.put(REACTION_TIMES, times)

        // Seen: a title is seen when its newest mark is newer than its newest removal.
        val seen = JSONObject()
        val removed = JSONObject()
        val seenKeys = sortedSetOf<String>()
        for (src in listOf(a, b)) {
            src.optJSONObject(SEEN)?.keys()?.forEach { seenKeys += it }
            src.optJSONObject(SEEN_REMOVED)?.keys()?.forEach { seenKeys += it }
        }
        for (key in seenKeys) {
            val entries = listOfNotNull(a.optJSONObject(SEEN)?.optJSONObject(key), b.optJSONObject(SEEN)?.optJSONObject(key))
            val best = entries.maxByOrNull { it.optLong("at") }
            val removedAt = maxOf(
                a.optJSONObject(SEEN_REMOVED)?.optLong(key, 0L) ?: 0L,
                b.optJSONObject(SEEN_REMOVED)?.optLong(key, 0L) ?: 0L,
            )
            if (best != null && best.optLong("at") > removedAt) seen.put(key, best)
            else if (removedAt > 0) removed.put(key, removedAt)
        }
        out.put(SEEN, seen)
        out.put(SEEN_REMOVED, removed)
        return out
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
