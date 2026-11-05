package com.streamflixreborn.streamflix.utils

import com.streamflixreborn.streamflix.utils.InAppUpdater.AppAsset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InAppUpdaterTest {
    private fun asset(name: String) = AppAsset(name, "https://example.com/$name", 1L)
    private val full = asset("Streamable-1.0.11.apk")
    private val slim = asset("Streamable-1.0.11-armeabi-v7a.apk")

    @Test
    fun tenIsNewerThanNine() {
        assertTrue(InAppUpdater.isNewer("v1.0.10", "1.0.9"))
        assertFalse(InAppUpdater.isNewer("v1.0.9", "1.0.10"))
    }

    @Test
    fun sameVersionIsNotNewerAndTagPrefixIsIgnored() {
        assertFalse(InAppUpdater.isNewer("v1.0.10", "1.0.10"))
        assertFalse(InAppUpdater.isNewer("1.0.10", "v1.0.10"))
    }

    @Test
    fun missingPartsCountAsZeroAndSuffixesAreIgnored() {
        assertFalse(InAppUpdater.isNewer("1.1", "1.1.0"))
        assertTrue(InAppUpdater.isNewer("1.2", "1.1.9"))
        assertEquals(0, InAppUpdater.compareVersions("1.0.10-beta", "1.0.10"))
    }

    @Test
    fun sixtyFourBitDevicesGetTheFullApk() {
        assertEquals(full, InAppUpdater.pickAsset(listOf(slim, full), listOf("arm64-v8a", "armeabi-v7a", "armeabi"), ""))
        assertEquals(full, InAppUpdater.pickAsset(listOf(slim, full), listOf("x86_64", "x86"), ""))
    }

    @Test
    fun thirtyTwoBitOnlyDevicesGetTheSlimApk() {
        assertEquals(slim, InAppUpdater.pickAsset(listOf(full, slim), listOf("armeabi-v7a", "armeabi"), ""))
    }

    @Test
    fun fallsBackToTheOtherApkAndIgnoresNonApkFiles() {
        assertEquals(full, InAppUpdater.pickAsset(listOf(full), listOf("armeabi-v7a"), ""))
        assertEquals(slim, InAppUpdater.pickAsset(listOf(slim), listOf("arm64-v8a"), ""))
        assertNull(InAppUpdater.pickAsset(listOf(asset("notes.txt"), asset("app.apk.sha256")), listOf("arm64-v8a"), ""))
    }
}
