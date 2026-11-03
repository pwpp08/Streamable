package com.streamflixreborn.streamflix.nextui.extensions

import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

/** Scrubbing mode makes seek-bar drags cheaper; only ExoPlayer supports it. */
fun Player.setIsScrubbingModeEnabled(enabled: Boolean) {
    (this as? ExoPlayer)?.isScrubbingModeEnabled = enabled
}
