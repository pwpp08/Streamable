package com.streamflixreborn.streamflix.nextui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import android.graphics.Bitmap
import com.streamflixreborn.streamflix.nextui.model.VideoContentScale
import com.streamflixreborn.streamflix.nextui.state.ControlsVisibilityState

/** One selectable row of a [Picker]. */
class PickerItem(
    val label: String,
    val selected: Boolean,
    val detail: String? = null,
    val onSelect: () -> Unit,
)

/** A button under the list of a [Picker]. [dismiss] closes the picker before [onClick] runs. */
class PickerAction(
    val label: String,
    val dismiss: Boolean = true,
    val onClick: () -> Unit,
)

/** Side sheet with a radio list (audio, subtitles, servers, quality). The host builds the content. */
class Picker(
    val title: String,
    val items: List<PickerItem>,
    val actions: List<PickerAction> = emptyList(),
    val emptyText: String? = null,
)

/** The "Up next" card shown near the end of an episode. */
class UpNext(
    val label: String,
    val meta: String,
    val title: String,
    val countdownText: String,
    val secondsLeft: Int?,
    val totalSeconds: Int,
    val playLabel: String,
    val cancelLabel: String,
)

/** What the host (a Fragment) has to react to; everything else the UI does on the Player itself. */
open class NextUiCallbacks {
    open fun onBack() {}
    open fun onServers() {}
    open fun onQuality() {}
    open fun onAudio() {}
    open fun onSubtitles() {}
    open fun onMore() {}
    open fun onExternalPlayer() {}
    open fun onPreviousEpisode() {}
    open fun onNextEpisode() {}
    open fun onSkipSegment() {}
    open fun onSkipDismiss() {}
    open fun onUpNextPlay() {}
    open fun onUpNextCancel() {}
    open fun onPictureInPicture() {}
    open fun onVideoContentScaleChanged(scale: VideoContentScale) {}

    /** Called whenever the pinch zoom / pan changes so the host can transform its video surface. */
    open fun onVideoTransform(scale: VideoContentScale, zoom: Float, offsetX: Float, offsetY: Float) {}
}

/** Compose-observable state the host pushes into the UI. */
@Stable
class NextUiModel {
    var title by mutableStateOf("")
    var subtitle by mutableStateOf("")
    var hasPrevious by mutableStateOf(false)
    var hasNext by mutableStateOf(false)
    var serverName by mutableStateOf<String?>(null)
    var showServerButton by mutableStateOf(true)
    var showQualityButton by mutableStateOf(true)
    var showExternalPlayerButton by mutableStateOf(false)
    var skipLabel by mutableStateOf<String?>(null)
    var upNext by mutableStateOf<UpNext?>(null)
    var upNextPoster by mutableStateOf<Bitmap?>(null)
    var picker by mutableStateOf<Picker?>(null)
    var isPictureInPictureSupported by mutableStateOf(false)
    var isInPictureInPictureMode by mutableStateOf(false)
    var initialContentScale by mutableStateOf(VideoContentScale.AUTO)
    var gesturesEnabled by mutableStateOf(true)
    var seekIncrementMs by mutableStateOf(10_000L)
    var autoHideSeconds by mutableIntStateOf(4)
    var isTv by mutableStateOf(false)

    /** Host-driven: looking up servers or resolving a stream. Buffering is picked up on its own. */
    var isLoading by mutableStateOf(true)
    var message by mutableStateOf<Message?>(null)
        private set

    internal var speedSheetVisible by mutableStateOf(false)

    /** Set by the UI while it is composed; lets the host read or drive the controls. */
    var controls by mutableStateOf<ControlsVisibilityState?>(null)
        internal set

    private var messageCounter = 0

    fun showMessage(text: String) {
        message = Message(++messageCounter, text)
    }

    fun clearMessage() {
        message = null
    }

    class Message(val id: Int, val text: String)
}
