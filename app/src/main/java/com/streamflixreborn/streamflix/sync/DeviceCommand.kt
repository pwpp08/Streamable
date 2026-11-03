package com.streamflixreborn.streamflix.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * A command sent from one signed-in device to another through
 * `public.device_commands`. The row's `kind` column holds the serial name
 * ("play", "search", "control") and `payload` holds the JSON encoding of the
 * command plus an optional `source_name`.
 */
@Serializable
sealed class DeviceCommand {

    @Serializable
    @SerialName("play")
    data class Play(
        val provider: String,
        /** "movie" or "episode". */
        val mediaType: String,
        val mediaId: String,
        val showId: String? = null,
        val seasonNumber: Int? = null,
        val episodeNumber: Int? = null,
        /** Movie title, or the show title for episodes. */
        val title: String,
        val positionMs: Long = 0L,
        val serverName: String? = null,
        val durationMs: Long? = null,
        // Optional extras so the receiver can open the player even when the
        // item is not in its local database yet.
        val episodeTitle: String? = null,
        val seasonTitle: String? = null,
        val poster: String? = null,
        val banner: String? = null,
        val releaseDate: String? = null,
        val imdbId: String? = null,
    ) : DeviceCommand() {
        val isEpisode: Boolean get() = mediaType == MEDIA_EPISODE

        companion object {
            const val MEDIA_MOVIE = "movie"
            const val MEDIA_EPISODE = "episode"
        }
    }

    @Serializable
    @SerialName("search")
    data class Search(val text: String) : DeviceCommand()

    @Serializable
    @SerialName("control")
    data class Control(
        val action: Action,
        val amountMs: Long? = null,
    ) : DeviceCommand() {
        @Serializable
        enum class Action {
            PLAY_PAUSE,
            SEEK_FORWARD,
            SEEK_BACK,
            NEXT_EPISODE,
            SUBTITLES_TOGGLE,
            STOP,
        }
    }

    val kind: String
        get() = when (this) {
            is Play -> "play"
            is Search -> "search"
            is Control -> "control"
        }

    companion object {
        private const val SOURCE_NAME = "source_name"

        internal val json = Json {
            ignoreUnknownKeys = true
            classDiscriminator = "type"
            encodeDefaults = false
        }

        fun toPayload(command: DeviceCommand, sourceName: String?): JsonObject {
            val encoded = json.encodeToJsonElement(serializer(), command).jsonObject
            return if (sourceName.isNullOrBlank()) encoded else {
                JsonObject(encoded + (SOURCE_NAME to JsonPrimitive(sourceName)))
            }
        }

        /** Returns the command and the sender's display name, or null for unknown kinds. */
        fun fromPayload(kind: String, payload: JsonObject): Pair<DeviceCommand, String?>? {
            val sourceName = (payload[SOURCE_NAME] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val withType = JsonObject(payload - SOURCE_NAME + ("type" to JsonPrimitive(kind)))
            val command = runCatching { json.decodeFromJsonElement(serializer(), withType) }.getOrNull()
                ?: return null
            return command to sourceName
        }
    }
}
