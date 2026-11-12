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

    @Test
    fun projectorReleasesPickTheTvApkForTheDevicesAbi() {
        val tv64 = asset("Streamable-1.0.14-tv.apk")
        val tv32 = asset("Streamable-1.0.14-tv-armeabi-v7a.apk")
        val all = listOf(tv64, tv32)
        assertEquals(tv32, InAppUpdater.pickAsset(all, listOf("armeabi-v7a", "armeabi"), "tv"))
        assertEquals(tv64, InAppUpdater.pickAsset(all, listOf("arm64-v8a", "armeabi-v7a"), "tv"))
        // The universal build never picks a TV-only file.
        assertEquals(full, InAppUpdater.pickAsset(listOf(full, slim, tv64, tv32), listOf("arm64-v8a"), ""))
        assertEquals(slim, InAppUpdater.pickAsset(listOf(full, slim, tv64, tv32), listOf("armeabi-v7a"), ""))
    }

    @Test
    fun projectorTagsComparePlainlyAndAreOnlyForTvBuilds() {
        assertTrue(InAppUpdater.isNewer("projector-v1.0.15", "1.0.14"))
        assertFalse(InAppUpdater.isNewer("projector-v1.0.14", "1.0.14"))
        assertEquals("1.0.15", InAppUpdater.AppRelease("projector-v1.0.15", null, emptyList()).versionName)

        val projector = listOf("Streamable-1.0.15-tv.apk", "Streamable-1.0.15-tv-armeabi-v7a.apk")
        val normal = listOf("Streamable-1.0.15.apk", "Streamable-1.0.15-armeabi-v7a.apk")
        assertTrue(InAppUpdater.isForLayout("projector-v1.0.15", projector, "tv"))
        assertFalse(InAppUpdater.isForLayout("v1.0.15", normal, "tv"))
        assertFalse(InAppUpdater.isForLayout("projector-v1.0.15", projector, "null"))
        assertTrue(InAppUpdater.isForLayout("v1.0.15", normal, "null"))
    }
}
