package com.streamflixreborn.streamflix.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.streamflixreborn.streamflix.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import android.os.Build
import kotlin.math.max

object InAppUpdater {

    private const val REPO_OWNER = "streamflix-reborn2"
    private const val REPO_NAME = "streamflix"

    private data class Version(val name: String) : Comparable<Version> {
        override operator fun compareTo(other: Version): Int {
            val thisParts = this.name.split(".").toTypedArray()
            val thatParts = other.name.split(".").toTypedArray()
            for (i in 0 until max(thisParts.size, thatParts.size)) {
                val thisPart = thisParts.getOrNull(i)?.toIntOrNull() ?: 0
                val thatPart = thatParts.getOrNull(i)?.toIntOrNull() ?: 0
                if (thisPart < thatPart) return -1
                if (thisPart > thatPart) return 1
            }
            return 0
        }
    }

    suspend fun getReleaseUpdate(): GitGay.Release? {
        val latestRelease = GitGay.Releases.getLatestRelease(REPO_OWNER, REPO_NAME)
        val currentVersion = BuildConfig.VERSION_NAME

        if (Version(latestRelease.tagName.substringAfter("v")) > Version(currentVersion)) {
            return latestRelease
        }
        return null
    }

    suspend fun getNewReleases(): List<GitGay.Release> {
        val releases = GitGay.Releases.getReleases(REPO_OWNER, REPO_NAME)
        val currentVersion = BuildConfig.VERSION_NAME

        val newReleases = releases
            .filter { Version(it.tagName.substringAfter("v")) > Version(currentVersion) }

        return newReleases
    }

    suspend fun downloadApk(context: Context, asset: GitGay.Release.Asset): File {
        val cacheDir = context.externalCacheDir ?: context.cacheDir
        cacheDir.listFiles()
            ?.filter { it.extension == "apk" }
            ?.forEach { it.deleteOnExit() }

        val apk = withContext(Dispatchers.IO) {
            File.createTempFile(
                "${File(asset.name).nameWithoutExtension}-",
                ".${File(asset.name).extension}",
                cacheDir
            )
        }

        withContext(Dispatchers.IO) {
            val url = URL(asset.browserDownloadUrl)
            val inputStream = if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.LOLLIPOP_MR1) {
                url.openConnection().apply {
                    setRequestProperty("User-Agent", NetworkClient.USER_AGENT)
                }.getInputStream()
            } else {
                try {
                    url.openStream()
                } catch (e: Exception) {
                    url.openConnection().apply {
                        setRequestProperty("User-Agent", NetworkClient.USER_AGENT)
                    }.getInputStream()
                }
            }

            inputStream.use { input ->
                FileOutputStream(apk).use { output -> input.copyTo(output) }
            }
        }

        return apk
    }

    fun installApk(context: Context, uri: Uri) {
        val apkFile = File(uri.path!!)
        apkFile.setReadable(true, false)

        val intent = Intent(Intent.ACTION_VIEW).also { intent ->
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            intent.putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)

            val apkUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                FileProvider.getUriForFile(
                    context,
                    BuildConfig.APPLICATION_ID + ".provider",
                    apkFile
                )
            } else {
                Uri.fromFile(apkFile)
            }

            intent.setDataAndType(apkUri, "application/vnd.android.package-archive")

            try {
                context.startActivity(intent)
            } catch (e: android.content.ActivityNotFoundException) {
                val installIntent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                    setDataAndType(apkUri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
                }
                context.startActivity(installIntent)
            }
        }
    }
}