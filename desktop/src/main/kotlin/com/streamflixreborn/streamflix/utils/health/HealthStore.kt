package com.streamflixreborn.streamflix.utils.health

/** Desktop keeps no server health history yet: every server gets tried. */
object HealthStore {
    fun isDead(@Suppress("UNUSED_PARAMETER") serverName: String): Boolean = false
}
