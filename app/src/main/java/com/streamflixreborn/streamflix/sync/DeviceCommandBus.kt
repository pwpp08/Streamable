package com.streamflixreborn.streamflix.sync

import android.content.Context
import android.util.Log
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.decodeRecordOrNull
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import org.json.JSONObject

/**
 * Device registry and command channel between devices of the same account.
 *
 * Every signed-in device upserts itself into `user_devices` (heartbeat).
 * TVs additionally listen for `device_commands` inserts addressed to them and
 * re-emit them on [commands]. The Wanbo firmware kills background apps, so a
 * TV only receives commands while StreamFlix is open; a catch-up query on
 * start delivers commands sent up to [CATCH_UP_WINDOW_MS] earlier.
 */
object DeviceCommandBus {
    private const val TAG = "DeviceCommandBus"
    private const val DEVICES = "user_devices"
    private const val COMMANDS = "device_commands"
    private const val NAMES_PREFS = "device_names"

    const val CATCH_UP_WINDOW_MS = 2 * 60_000L
    const val LIST_WINDOW_MS = 30L * 24 * 60 * 60_000L
    private const val HEARTBEAT_INTERVAL_MS = 15 * 60_000L
    private const val CLEANUP_AGE_MS = 24 * 60 * 60_000L

    data class SyncDevice(
        val id: String,
        val name: String,
        val kind: DeviceIdentity.Kind,
        val lastSeenAtMillis: Long,
        val isThisDevice: Boolean,
    )

    data class ReceivedCommand(
        val id: String,
        val command: DeviceCommand,
        val sourceDeviceId: String?,
        val sourceName: String?,
        val receivedAtMillis: Long,
    )

    @Serializable
    private data class DeviceRow(
        val id: String,
        @SerialName("user_id") val userId: String? = null,
        val name: String = "",
        val kind: String = "phone",
        @SerialName("last_seen_at") val lastSeenAt: String? = null,
    )

    @Serializable
    private data class NewCommandRow(
        @SerialName("target_device_id") val targetDeviceId: String,
        @SerialName("source_device_id") val sourceDeviceId: String,
        val kind: String,
        val payload: JsonObject,
    )

    @Serializable
    private data class CommandRow(
        val id: String,
        @SerialName("target_device_id") val targetDeviceId: String,
        @SerialName("source_device_id") val sourceDeviceId: String? = null,
        val kind: String,
        val payload: JsonObject = JsonObject(emptyMap()),
        @SerialName("created_at") val createdAt: String? = null,
        @SerialName("consumed_at") val consumedAt: String? = null,
    )

    @Serializable
    private data class ConsumedPatch(@SerialName("consumed_at") val consumedAt: String)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycleMutex = Mutex()

    private var appContext: Context? = null
    private var activeProfileId: String? = null
    private var activeUserId: String? = null
    private var activeClient: SupabaseClient? = null
    private var channel: RealtimeChannel? = null
    private var collectorJob: Job? = null
    private var heartbeatJob: Job? = null
    private val handledIds = LinkedHashSet<String>()

    private val incoming = Channel<ReceivedCommand>(
        capacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * Commands addressed to this device (TV only). Commands that arrive while
     * nobody collects stay buffered until the first collector subscribes.
     */
    val commands: SharedFlow<ReceivedCommand> = incoming
        .receiveAsFlow()
        .shareIn(scope, SharingStarted.WhileSubscribed(), replay = 0)

    fun isOwnedBy(profileId: String): Boolean = activeProfileId == profileId

    /** Registers this device and, on TVs, starts listening for commands. */
    suspend fun start(context: Context, profileId: String, userId: String) {
        if (!SupabaseProvider.isConfigured) return
        val appContext = context.applicationContext
        lifecycleMutex.withLock {
            val listening = channel?.status?.value == RealtimeChannel.Status.SUBSCRIBED
            if (activeProfileId == profileId && activeUserId == userId &&
                (listening || !DeviceIdentity.isTv(appContext))
            ) {
                return@withLock
            }
            stopLocked()

            val client = SupabaseProvider.clientFor(appContext, profileId)
            this.appContext = appContext
            activeProfileId = profileId
            activeUserId = userId
            activeClient = client

            runCatching { heartbeat(appContext, client, userId) }
                .onFailure { Log.w(TAG, "Device heartbeat failed (run the devices migration?)", it) }
            heartbeatJob = scope.launch {
                while (isActive) {
                    delay(HEARTBEAT_INTERVAL_MS)
                    runCatching { heartbeat(appContext, client, userId) }
                }
            }

            if (DeviceIdentity.isTv(appContext)) {
                subscribeLocked(appContext, client, profileId)
            }
        }
    }

    /**
     * Reads commands addressed to this TV that realtime did not deliver (a dropped socket, an
     * undecodable record, a table missing from the publication). Cheap enough to run every few
     * seconds while the TV app is on screen.
     */
    suspend fun poll() {
        val client = activeClient ?: return
        val context = appContext ?: return
        val myId = DeviceIdentity.id(context)
        runCatching {
            val since = SyncTime.iso(System.currentTimeMillis() - CATCH_UP_WINDOW_MS)
            client.from(COMMANDS).select {
                filter {
                    eq("target_device_id", myId)
                    exact("consumed_at", null)
                    gt("created_at", since)
                }
                order("created_at", Order.ASCENDING)
            }.decodeList<CommandRow>().forEach { deliver(client, it) }
        }.onFailure { Log.w(TAG, "Command poll failed", it) }
    }

    /** Re-subscribes when the realtime channel has dropped (the socket can die while the TV dims). */
    suspend fun ensureListening() {
        val context = appContext ?: return
        val profileId = activeProfileId ?: return
        val userId = activeUserId ?: return
        if (channel?.status?.value == RealtimeChannel.Status.SUBSCRIBED) return
        Log.i(TAG, "Realtime channel is not subscribed; re-subscribing")
        start(context, profileId, userId)
    }

    suspend fun stop() {
        lifecycleMutex.withLock { stopLocked() }
    }

    /**
     * Devices of the signed-in account seen in the last 30 days: TVs first,
     * then most recently seen first.
     */
    suspend fun listDevices(context: Context): List<SyncDevice> {
        val appContext = context.applicationContext
        val profileId = activeProfileIdOrDefault()
        CloudSyncManager.currentUserId(profileId) ?: return emptyList()
        val client = SupabaseProvider.clientFor(appContext, profileId)
        val since = SyncTime.iso(System.currentTimeMillis() - LIST_WINDOW_MS)
        val myId = DeviceIdentity.id(appContext)
        val rows = client.from(DEVICES).select {
            filter { gt("last_seen_at", since) }
            order("last_seen_at", Order.DESCENDING)
        }.decodeList<DeviceRow>()
        val devices = rows.map { row ->
            SyncDevice(
                id = row.id,
                name = row.name.ifBlank { row.id.take(6) },
                kind = DeviceIdentity.Kind.fromWire(row.kind),
                lastSeenAtMillis = SyncTime.parse(row.lastSeenAt) ?: 0L,
                isThisDevice = row.id == myId,
            )
        }
        rememberNames(appContext, devices)
        return sortDevices(devices)
    }

    internal fun sortDevices(devices: List<SyncDevice>): List<SyncDevice> =
        devices.sortedWith(
            compareByDescending<SyncDevice> { it.kind == DeviceIdentity.Kind.TV }
                .thenByDescending { it.lastSeenAtMillis },
        )

    /** Sends [command] to [targetDeviceId]. Throws when signed out or offline. */
    suspend fun send(context: Context, targetDeviceId: String, command: DeviceCommand) {
        val appContext = context.applicationContext
        val profileId = activeProfileIdOrDefault()
        CloudSyncManager.currentUserId(profileId) ?: error("Sign in to sync before sending to a TV")
        val client = SupabaseProvider.clientFor(appContext, profileId)
        client.from(COMMANDS).insert(
            NewCommandRow(
                targetDeviceId = targetDeviceId,
                sourceDeviceId = DeviceIdentity.id(appContext),
                kind = command.kind,
                payload = DeviceCommand.toPayload(command, DeviceIdentity.name(appContext)),
            ),
        )
    }

    /** Last known display name of a device of this account, if any. */
    fun knownDeviceName(context: Context, deviceId: String?): String? {
        if (deviceId.isNullOrBlank()) return null
        val raw = context.applicationContext.getSharedPreferences(NAMES_PREFS, Context.MODE_PRIVATE)
            .getString("names", null) ?: return null
        return runCatching { JSONObject(raw).optString(deviceId).takeIf { it.isNotBlank() } }.getOrNull()
    }

    private fun rememberNames(context: Context, devices: List<SyncDevice>) {
        val prefs = context.getSharedPreferences(NAMES_PREFS, Context.MODE_PRIVATE)
        val json = runCatching { JSONObject(prefs.getString("names", null) ?: "{}") }.getOrElse { JSONObject() }
        devices.forEach { json.put(it.id, it.name) }
        prefs.edit().putString("names", json.toString()).apply()
    }

    private suspend fun heartbeat(context: Context, client: SupabaseClient, userId: String) {
        client.from(DEVICES).upsert(
            DeviceRow(
                id = DeviceIdentity.id(context),
                userId = userId,
                name = DeviceIdentity.name(context),
                kind = DeviceIdentity.kind(context).wireName,
                lastSeenAt = SyncTime.iso(System.currentTimeMillis()),
            ),
        ) {
            onConflict = "user_id,id"
        }
    }

    private suspend fun subscribeLocked(context: Context, client: SupabaseClient, profileId: String) {
        val myId = DeviceIdentity.id(context)
        val newChannel = client.realtime.channel("device-commands-$profileId-$myId")
        val inserts = newChannel.postgresChangeFlow<PostgresAction.Insert>(schema = "public") {
            table = COMMANDS
            filter("target_device_id", FilterOperator.EQ, myId)
        }
        val collector = inserts
            .onEach { action ->
                val row = action.decodeRecordOrNull<CommandRow>()
                if (row != null) deliver(client, row) else {
                    // The change arrived but could not be decoded: read the row the normal way.
                    Log.w(TAG, "Could not decode a realtime command; polling instead")
                    poll()
                }
            }
            .catch { error -> Log.w(TAG, "Device command listener stopped", error) }
            .launchIn(scope)

        try {
            newChannel.subscribe(blockUntilSubscribed = true)
            channel = newChannel
            collectorJob = collector
            Log.i(TAG, "Listening for device commands")
        } catch (error: Throwable) {
            collector.cancel()
            runCatching { client.realtime.removeChannel(newChannel) }
            Log.w(TAG, "Could not subscribe to device commands", error)
        }

        // Commands sent while the app was closed (the TV firmware kills it on Home).
        runCatching {
            val since = SyncTime.iso(System.currentTimeMillis() - CATCH_UP_WINDOW_MS)
            client.from(COMMANDS).select {
                filter {
                    eq("target_device_id", myId)
                    exact("consumed_at", null)
                    gt("created_at", since)
                }
                order("created_at", Order.ASCENDING)
            }.decodeList<CommandRow>().forEach { deliver(client, it) }
        }.onFailure { Log.w(TAG, "Device command catch-up failed", it) }

        scope.launch {
            runCatching {
                val before = SyncTime.iso(System.currentTimeMillis() - CLEANUP_AGE_MS)
                client.from(COMMANDS).delete {
                    filter {
                        eq("target_device_id", myId)
                        lt("created_at", before)
                    }
                }
            }
        }
    }

    private suspend fun deliver(client: SupabaseClient, row: CommandRow) {
        if (row.consumedAt != null) return
        synchronized(handledIds) {
            if (!handledIds.add(row.id)) return
            if (handledIds.size > 200) handledIds.remove(handledIds.first())
        }
        val createdAt = SyncTime.parse(row.createdAt)
        if (createdAt != null && System.currentTimeMillis() - createdAt > CATCH_UP_WINDOW_MS * 2) return

        runCatching {
            client.from(COMMANDS).update(ConsumedPatch(SyncTime.iso(System.currentTimeMillis()))) {
                filter { eq("id", row.id) }
            }
        }.onFailure { Log.w(TAG, "Could not mark command consumed", it) }

        Log.i(TAG, "Command ${row.kind} received")
        val (command, sourceName) = DeviceCommand.fromPayload(row.kind, row.payload) ?: run {
            Log.w(TAG, "Ignoring unknown device command kind ${row.kind}")
            return
        }
        incoming.trySend(
            ReceivedCommand(
                id = row.id,
                command = command,
                sourceDeviceId = row.sourceDeviceId,
                sourceName = sourceName,
                receivedAtMillis = System.currentTimeMillis(),
            ),
        )
    }

    private suspend fun stopLocked() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        collectorJob?.cancelAndJoin()
        collectorJob = null
        val client = activeClient
        channel?.let { existing ->
            runCatching { client?.realtime?.removeChannel(existing) }
                .onFailure { Log.w(TAG, "Could not stop device command listener", it) }
        }
        channel = null
        activeClient = null
        activeProfileId = null
        activeUserId = null
    }

    private fun activeProfileIdOrDefault(): String =
        com.streamflixreborn.streamflix.utils.ProfileManager.activeProfileId ?: "default"
}
