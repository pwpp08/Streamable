package com.streamflixreborn.streamflix.sync

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * One-shot start position for the next player launch, set by "Play on TV"
 * and hand-off resume.
 *
 * Player hook: when PlayerTvFragment / PlayerMobileFragment decide the
 * initial seek position, call `PlayerStartPosition.consume(args.id)` first and
 * seek there when it returns non-null (no "-10 s" adjustment needed).
 */
object PlayerStartPosition {
    private const val MAX_AGE_MS = 2 * 60_000L

    private data class Pending(val videoId: String, val positionMs: Long, val setAtMillis: Long)

    @Volatile
    private var pending: Pending? = null

    fun set(videoId: String, positionMs: Long) {
        pending = if (positionMs > 0) Pending(videoId, positionMs, System.currentTimeMillis()) else null
    }

    /** Returns and clears the pending position when it targets [videoId]. */
    @Synchronized
    fun consume(videoId: String): Long? {
        val current = pending ?: return null
        if (current.videoId != videoId) return null
        pending = null
        if (System.currentTimeMillis() - current.setAtMillis > MAX_AGE_MS) return null
        return current.positionMs
    }
}

/**
 * Remote-control actions received from a phone (TV side).
 *
 * Player hook: PlayerTvFragment collects [actions] in its view lifecycle and
 * maps them onto the player: PLAY_PAUSE, SEEK_FORWARD/SEEK_BACK
 * (`amountMs`, default 10 s), NEXT_EPISODE, SUBTITLES_TOGGLE and STOP.
 */
object RemoteControl {
    const val DEFAULT_SEEK_MS = 10_000L

    private val _actions = MutableSharedFlow<DeviceCommand.Control>(extraBufferCapacity = 16)
    val actions: SharedFlow<DeviceCommand.Control> = _actions.asSharedFlow()

    /** True while at least one player collects [actions]. */
    val hasListener: Boolean get() = _actions.subscriptionCount.value > 0

    fun emit(control: DeviceCommand.Control): Boolean = _actions.tryEmit(control)
}

/** Text to prefill and submit on the TV search screen (consumed by SearchTvFragment). */
object RemoteSearch {
    private val _requests = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val requests: kotlinx.coroutines.flow.StateFlow<String?> = _requests

    fun request(text: String) {
        _requests.value = text
    }

    fun consume(): String? = _requests.value.also { _requests.value = null }
}
