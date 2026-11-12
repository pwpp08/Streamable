package com.streamflixreborn.streamflix.fragments.player.engine

import androidx.media3.common.C
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressSnapshotTest {

    @Test
    fun aStreamStillLoadingIsNeitherStartedNorFinished() {
        val switching = ProgressSaver.Snapshot(positionMs = 1_800_000, durationMs = C.TIME_UNSET)
        assertFalse(switching.hasFinished)
        assertFalse(switching.hasStarted)
    }

    @Test
    fun theStartOfANewStreamIsNotFinished() {
        assertFalse(ProgressSaver.Snapshot(positionMs = 0, durationMs = C.TIME_UNSET).hasFinished)
    }

    @Test
    fun ninetyPercentStillCountsAsFinished() {
        assertTrue(ProgressSaver.Snapshot(positionMs = 91_000, durationMs = 100_000).hasFinished)
        assertFalse(ProgressSaver.Snapshot(positionMs = 89_000, durationMs = 100_000).hasFinished)
    }
}
