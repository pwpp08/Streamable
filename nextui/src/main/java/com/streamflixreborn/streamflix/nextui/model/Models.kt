package com.streamflixreborn.streamflix.nextui.model

enum class DoubleTapGesture {
    PLAY_PAUSE,
    FAST_FORWARD_AND_REWIND,
    BOTH,
    NONE,
}

enum class VideoContentScale {
    /** Removes black bars baked into the video and fills the screen with the real picture. */
    AUTO,
    BEST_FIT,
    STRETCH,
    CROP,
}
