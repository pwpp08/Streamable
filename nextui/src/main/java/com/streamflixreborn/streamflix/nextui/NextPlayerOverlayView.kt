package com.streamflixreborn.streamflix.nextui

import android.content.Context
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.media3.common.Player

/**
 * Hosts NextPlayer's Compose controls on top of a video surface. The app talks to it through plain
 * Kotlin: [player], [model] (state in) and [callbacks] (events out), so the rest of the app needs no
 * Compose dependency.
 */
class NextPlayerOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    val model = NextUiModel()

    private var playerState by mutableStateOf<Player?>(null)
    private var callbacksState by mutableStateOf(NextUiCallbacks())

    var player: Player?
        get() = playerState
        set(value) {
            playerState = value
        }

    var callbacks: NextUiCallbacks
        get() = callbacksState
        set(value) {
            callbacksState = value
        }

    init {
        val composeView = ComposeView(context).apply {
            layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { NextUi(player = playerState, model = model, callbacks = callbacksState) }
        }
        addView(composeView)
    }

    val areControlsVisible: Boolean
        get() = model.controls?.controlsVisible == true

    fun showControls() {
        model.controls?.showControls()
    }

    fun hideControls() {
        model.controls?.hideControls()
    }

    /**
     * Consumes a Back press that belongs to the player UI: an open sheet, the Up-next card, the skip
     * pill, and (TV only) the visible controls. Returns false when the host should leave the player.
     */
    fun handleBack(): Boolean {
        if (model.picker != null) {
            model.picker = null
            return true
        }
        if (model.speedSheetVisible) {
            model.speedSheetVisible = false
            return true
        }
        if (model.upNext != null) {
            callbacksState.onUpNextCancel()
            return true
        }
        if (model.skipLabel != null) {
            callbacksState.onSkipDismiss()
            return true
        }
        val controls = model.controls
        if (model.isTv && controls != null && controls.controlsVisible && !controls.controlsLocked) {
            controls.hideControls()
            return true
        }
        return false
    }

    /** True while a sheet (audio, subtitles, speed, ...) is open. */
    val isSheetOpen: Boolean
        get() = model.picker != null || model.speedSheetVisible

    /** Gives the Compose tree D-pad focus (TV). */
    fun focusCompose(): Boolean = getChildAt(0).requestFocus()

}
