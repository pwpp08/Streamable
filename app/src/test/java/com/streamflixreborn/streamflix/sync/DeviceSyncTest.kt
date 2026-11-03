package com.streamflixreborn.streamflix.sync

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceSyncTest {

    @Test
    fun commandPayloadRoundTrip() {
        val commands = listOf(
            DeviceCommand.Play(
                provider = "TMDb (en)",
                mediaType = DeviceCommand.Play.MEDIA_EPISODE,
                mediaId = "ep-1",
                showId = "show-1",
                seasonNumber = 2,
                episodeNumber = 5,
                title = "Show",
                positionMs = 83_000,
                durationMs = 2_400_000,
            ),
            DeviceCommand.Search("breaking bad"),
            DeviceCommand.Control(DeviceCommand.Control.Action.SEEK_BACK, 10_000),
        )
        for (command in commands) {
            val payload = DeviceCommand.toPayload(command, "Pixel 8")
            assertEquals(JsonPrimitive("Pixel 8"), payload["source_name"])
            val (decoded, source) = DeviceCommand.fromPayload(command.kind, payload)!!
            assertEquals(command, decoded)
            assertEquals("Pixel 8", source)
        }
    }

    @Test
    fun unknownCommandKindIsIgnored() {
        val payload = DeviceCommand.toPayload(DeviceCommand.Search("x"), null)
        assertNull(DeviceCommand.fromPayload("teleport", payload))
    }

    @Test
    fun parsesPostgresTimestamps() {
        assertEquals(0L, SyncTime.parse("1970-01-01T00:00:00+00:00"))
        assertEquals(1_500L, SyncTime.parse("1970-01-01T00:00:01.5Z"))
        assertEquals(1_123L, SyncTime.parse("1970-01-01T00:00:01.123456+00:00"))
        assertEquals(-3_600_000L, SyncTime.parse("1970-01-01T00:00:00+01:00"))
        assertEquals(1_234L, SyncTime.parse("1970-01-01 00:00:01.234"))
        assertNull(SyncTime.parse("yesterday"))
        val now = 1_793_620_800_123L
        assertEquals(now, SyncTime.parse(SyncTime.iso(now)))
    }

    @Test
    fun devicesSortTvFirstThenRecent() {
        fun device(id: String, kind: DeviceIdentity.Kind, seen: Long) =
            DeviceCommandBus.SyncDevice(id, id, kind, seen, false)
        val sorted = DeviceCommandBus.sortDevices(
            listOf(
                device("phone-new", DeviceIdentity.Kind.PHONE, 300),
                device("tv-old", DeviceIdentity.Kind.TV, 100),
                device("tv-new", DeviceIdentity.Kind.TV, 200),
            ),
        )
        assertEquals(listOf("tv-new", "tv-old", "phone-new"), sorted.map { it.id })
    }

    @Test
    fun handoffCandidateRules() {
        val now = 10L * 24 * 3_600_000
        fun candidate(
            engaged: Long = now - 60_000,
            position: Long = 600_000,
            duration: Long = 2_400_000,
            device: String? = "phone",
        ) = HandoffResume.isCandidate(engaged, position, duration, device, "tv", now)

        assertTrue(candidate())
        assertFalse(candidate(engaged = now - 25L * 3_600_000))
        assertFalse(candidate(position = 59_000))
        assertFalse(candidate(position = 2_300_000))
        assertFalse(candidate(duration = 0))
        assertFalse(candidate(device = "tv"))
        assertFalse(candidate(device = ""))
        assertFalse(candidate(device = null))
    }

    @Test
    fun formatsResumePosition() {
        assertEquals("23:10", HandoffResume.formatPosition(23 * 60_000L + 10_000))
        assertEquals("1:02:03", HandoffResume.formatPosition(3_723_000))
    }

    @Test
    fun startPositionIsOneShotAndScoped() {
        PlayerStartPosition.set("movie-1", 90_000)
        assertNull(PlayerStartPosition.consume("movie-2"))
        assertEquals(90_000L, PlayerStartPosition.consume("movie-1"))
        assertNull(PlayerStartPosition.consume("movie-1"))
    }

    @Test
    fun remoteMediaStateOmitsUnknownDevice() {
        val json = kotlinx.serialization.json.Json
        val state = RemoteMediaState(
            userId = "u", provider = "p", mediaType = "movie", mediaId = "m", clientUpdatedAtMillis = 1,
        )
        assertFalse(json.encodeToString(RemoteMediaState.serializer(), state).contains("last_device_id"))
        assertTrue(
            json.encodeToString(RemoteMediaState.serializer(), state.copy(lastDeviceId = ""))
                .contains("\"last_device_id\":\"\""),
        )
        val decoded = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .decodeFromString(RemoteMediaState.serializer(), """{"user_id":"u","provider":"p","media_type":"movie","media_id":"m","client_updated_at_millis":1}""")
        assertNull(decoded.lastDeviceId)
    }
}
