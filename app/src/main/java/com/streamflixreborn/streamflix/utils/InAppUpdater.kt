package com.streamflixreborn.streamflix.utils

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.streamflixreborn.streamflix.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

/** Looks for newer versions in this app's own GitHub releases and installs them. */
object InAppUpdater {

    const val REPO_OWNER = "pwpp08"
    const val REPO_NAME = "Streamable"

    /** The 32-bit-only build for low-memory TV boxes; the other APK carries both ABIs. */
    private const val SLIM_ABI = "armeabi-v7a"

    data class AppAsset(val name: String, val url: String, val size: Long)

    data class AppRelease(val tagName: String, val body: String?, val assets: List<AppAsset>) {
        val versionName: String get() = tagName.removePrefix("v").removePrefix("V")
    }

    /** Thrown when the user still has to allow installs from this app in system settings. */
    class InstallPermissionRequired : Exception("Allow installs from this app, then tap Update again")

    /** Dotted numbers compared part by part, so 1.0.10 > 1.0.9. Anything after a digit run is ignored. */
    fun compareVersions(a: String, b: String): Int {
        fun parts(v: String) = v.removePrefix("v").removePrefix("V").split(".").map { part ->
            part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0
        }
        val left = parts(a)
        val right = parts(b)
        for (i in 0 until max(left.size, right.size)) {
            val l = left.getOrElse(i) { 0 }
            val r = right.getOrElse(i) { 0 }
            if (l != r) return l.compareTo(r)
        }
        return 0
    }

    fun isNewer(tag: String, current: String): Boolean = compareVersions(tag, current) > 0

    /**
     * The APK for this device: the slim one when the device is 32-bit only, the full one otherwise.
     * Falls back to whatever APK is there rather than offering nothing.
     */
    fun pickAsset(assets: List<AppAsset>, supportedAbis: List<String>, layout: String): AppAsset? {
        val apks = assets.filter { it.name.endsWith(".apk") }.filter {
            when (layout) {
                "mobile" -> it.name.endsWith("-mobile.apk")
                "tv" -> it.name.endsWith("-tv.apk")
                else -> !it.name.endsWith("-mobile.apk") && !it.name.endsWith("-tv.apk")
            }
        }.ifEmpty { assets.filter { it.name.endsWith(".apk") } }
        val is32bitOnly = supportedAbis.firstOrNull() == SLIM_ABI
        val slim = apks.firstOrNull { it.name.endsWith("-$SLIM_ABI.apk") }
        val full = apks.firstOrNull { !it.name.endsWith("-$SLIM_ABI.apk") }
        return if (is32bitOnly) slim ?: full else full ?: slim
    }

    /** Published releases newer than the installed version, newest first. */
    suspend fun getNewReleases(): List<AppRelease> {
        val current = BuildConfig.VERSION_NAME
        return GitHub.Releases.getReleases(REPO_OWNER, REPO_NAME)
            .filter { !it.draft && !it.prerelease && isNewer(it.tagName, current) }
            .map { release ->
                AppRelease(
                    tagName = release.tagName,
                    body = release.body,
                    assets = release.assets.map { AppAsset(it.name, it.browserDownloadUrl, it.size.toLong()) },
                )
            }
            .sortedWith { a, b -> compareVersions(b.tagName, a.tagName) }
    }

    suspend fun downloadApk(context: Context, asset: AppAsset): File = withContext(Dispatchers.IO) {
        val dir = context.externalCacheDir ?: context.cacheDir
        dir.listFiles()?.filter { it.extension == "apk" }?.forEach { it.delete() }
        val apk = File.createTempFile("${File(asset.name).nameWithoutExtension}-", ".apk", dir)

        val request = Request.Builder().url(asset.url).header("User-Agent", NetworkClient.USER_AGENT).build()
        try {
            NetworkClient.default.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw java.io.IOException("Download failed (HTTP ${response.code})")
                val body = response.body ?: throw java.io.IOException("Empty download")
                body.byteStream().use { input -> FileOutputStream(apk).use { input.copyTo(it) } }
            }
            if (asset.size > 0 && apk.length() != asset.size) {
                throw java.io.IOException("Download incomplete, try again")
            }
        } catch (e: Exception) {
            apk.delete()
            throw e
        }
        apk
    }

    fun installApk(context: Context, uri: Uri) {
        // Android 8+: installs from an app need the per-app "install unknown apps" switch.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            throw InstallPermissionRequired()
        }

        val apkFile = File(uri.path!!)
        apkFile.setReadable(true, false)

        val intent = Intent(Intent.ACTION_VIEW).also { intent ->
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            intent.putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)

            val apkUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                FileProvider.getUriForFile(context, BuildConfig.APPLICATION_ID + ".provider", apkFile)
            } else {
                Uri.fromFile(apkFile)
            }

            intent.setDataAndType(apkUri, "application/vnd.android.package-archive")

            try {
                context.startActivity(intent)
            } catch (e: ActivityNotFoundException) {
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
