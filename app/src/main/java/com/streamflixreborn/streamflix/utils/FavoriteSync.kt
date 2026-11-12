package com.streamflixreborn.streamflix.utils

import android.content.Context
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.TvShow

/**
 * Tells the cloud sync that a title was added to / removed from the watchlist ("favorite").
 *
 * The DAOs' `upsertFavorite` only writes the local database; without this call the change would
 * wait for the next full sync instead of reaching the account's other devices right away.
 * Call it after `upsertFavorite`, off the main thread.
 */
object FavoriteSync {

    fun movie(context: Context, movie: Movie, favorite: Boolean) {
        val provider = UserPreferences.currentProvider ?: return
        if (favorite) {
            UserDataCache.addMovieToFavorites(context, provider, movie)
        } else {
            UserDataCache.removeMovieFromFavorites(context, provider, movie.id)
        }
    }

    fun tvShow(context: Context, tvShow: TvShow, favorite: Boolean) {
        val provider = UserPreferences.currentProvider ?: return
        if (favorite) {
            UserDataCache.addTvShowToFavorites(context, provider, tvShow)
        } else {
            UserDataCache.removeTvShowFromFavorites(context, provider, tvShow.id)
        }
    }
}
