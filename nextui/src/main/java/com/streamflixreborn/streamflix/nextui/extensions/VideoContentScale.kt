package com.streamflixreborn.streamflix.nextui.extensions

import androidx.compose.ui.layout.ContentScale
import com.streamflixreborn.streamflix.nextui.model.VideoContentScale
import com.streamflixreborn.streamflix.nextui.R

fun VideoContentScale.nameRes(): Int = when (this) {
    VideoContentScale.BEST_FIT -> R.string.best_fit
    VideoContentScale.STRETCH -> R.string.stretch
    VideoContentScale.CROP -> R.string.crop
}

fun VideoContentScale.drawableRes(): Int = when (this) {
    VideoContentScale.BEST_FIT -> R.drawable.ic_fit_screen
    VideoContentScale.STRETCH -> R.drawable.ic_aspect_ratio
    VideoContentScale.CROP -> R.drawable.ic_crop_landscape
}

fun VideoContentScale.toContentScale(): ContentScale = when (this) {
    VideoContentScale.BEST_FIT -> ContentScale.Fit
    VideoContentScale.STRETCH -> ContentScale.FillBounds
    VideoContentScale.CROP -> ContentScale.Crop
}
