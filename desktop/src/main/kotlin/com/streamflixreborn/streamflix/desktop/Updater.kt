package com.streamflixreborn.streamflix.desktop

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.streamflixreborn.streamflix.BuildConfig
import com.streamflixreborn.streamflix.utils.NetworkClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.system.exitProcess

/**
 * Looks for a newer desktop release on GitHub (tags `desktop-vX.Y.Z`) at launch and every few
 * hours. On Windows the update installs itself: the app closes, the MSI upgrades it in place
 * (per-user, no admin prompt) and the new version opens. On macOS the new .dmg is downloaded and
 * opened, and the app is dragged into Applications as usual.
 */
object Updater {
    private const val TAG = "Updater"
    private const val API = "https://api.github.com/repos/pwpp08/Streamable"
    private const val CHECK_EVERY_MS = 6L * 60 * 60 * 1000

    data class Release(val version: String, val assetName: String, val assetUrl: String, val pageUrl: String)

    sealed interface State {
        data object Idle : State
        data class Available(val release: Release) : State
        data class Downloading(val release: Release, val progress: Float) : State
        data class Failed(val release: Release, val message: String) : State
        data object UpToDate : State
    }

    var state by mutableStateOf<State>(State.Idle)
        private set

    /** Hidden after "Later" until the next launch. */
    var dismissed by mutableStateOf(false)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val os = System.getProperty("os.name").orEmpty().lowercase()
    private val isWindows = os.contains("win")
    private val isMac = os.contains("mac")

    /** The installed app's launcher; null when running from Gradle, where updating makes no sense. */
    private val launcher: String? = ProcessHandle.current().info().command().orElse(null)?.takeIf {
        val name = File(it).name.lowercase()
        name.startsWith("streamflix")
    }

    val supported get() = launcher != null && (isWindows || isMac)

    fun start() {
        if (!supported) return
        scope.launch {
            while (true) {
                check()
                delay(CHECK_EVERY_MS)
            }
        }
    }

    fun checkNow() = scope.launch { check(manual = true) }

    private fun check(manual: Boolean = false) {
        if (state is State.Downloading) return
        runCatching {
            // Desktop tags only, so phone and projector releases can never crowd them out.
            val tags = JSONArray(get("$API/git/matching-refs/tags/desktop-v"))
            val newer = (0 until tags.length())
                .map { tags.getJSONObject(it).getString("ref").substringAfterLast("/desktop-v") }
                .filter { compare(it, BuildConfig.VERSION_NAME) > 0 }
                .sortedWith { x, y -> compare(y, x) }
            // The newest tag whose release is published with an installer for this system
            // (a release still building has no files yet).
            val latest = newer.take(3).firstNotNullOfOrNull { v ->
                runCatching { parse(JSONObject(get("$API/releases/tags/desktop-v$v"))) }.getOrNull()
            }
            state = if (latest != null) {
                Log.i(TAG, "update available: ${latest.version}")
                if (manual) dismissed = false
                State.Available(latest)
            } else {
                State.UpToDate
            }
            // Test hook: -Dstreamflix.autoupdate=true installs without the click.
            if (state is State.Available && System.getProperty("streamflix.autoupdate") == "true") install()
        }.onFailure {
            Log.w(TAG, "update check failed: ${it.message}")
            if (manual) state = State.Idle
        }
    }

    private fun get(url: String): String {
        val req = Request.Builder().url(url).header("Accept", "application/vnd.github+json").build()
        return NetworkClient.default.newCall(req).execute().use { r ->
            if (!r.isSuccessful) error("GitHub answered ${r.code}")
            r.body!!.string()
        }
    }

    private fun parse(r: JSONObject): Release? {
        val tag = r.optString("tag_name")
        if (!tag.startsWith("desktop-v") || r.optBoolean("draft") || r.optBoolean("prerelease")) return null
        val assets = r.optJSONArray("assets") ?: return null
        val wanted = if (isWindows) ".msi" else ".dmg"
        val asset = (0 until assets.length()).map { assets.getJSONObject(it) }
            .firstOrNull { it.optString("name").endsWith(wanted, true) } ?: return null
        return Release(tag.removePrefix("desktop-v"), asset.getString("name"), asset.getString("browser_download_url"), r.optString("html_url"))
    }

    /** Compares dotted version numbers: 1.10.0 is newer than 1.9.3. */
    fun compare(a: String, b: String): Int {
        val x = a.split('.').map { it.toIntOrNull() ?: 0 }
        val y = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d
        }
        return 0
    }

    fun install() {
        val release = when (val s = state) {
            is State.Available -> s.release
            is State.Failed -> s.release
            else -> return
        }
        scope.launch {
            runCatching {
                val file = download(release)
                if (isWindows) installWindows(file) else openMac(file)
            }.onFailure {
                Log.e(TAG, "update failed", it)
                state = State.Failed(release, it.message ?: it.javaClass.simpleName)
            }
        }
    }

    private fun download(release: Release): File {
        val dir = if (isMac) File(System.getProperty("user.home"), "Downloads").takeIf { it.isDirectory } ?: tempDir() else tempDir()
        val target = File(dir, release.assetName)
        val part = File(dir, release.assetName + ".part")
        state = State.Downloading(release, 0f)
        NetworkClient.default.newCall(Request.Builder().url(release.assetUrl).build()).execute().use { r ->
            if (!r.isSuccessful) error("download failed (${r.code})")
            val total = r.body!!.contentLength().takeIf { it > 0 }
            r.body!!.byteStream().use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(256 * 1024)
                    var done = 0L
                    var shown = 0f
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val p = total?.let { done.toFloat() / it } ?: 0f
                        if (p - shown >= 0.01f) {
                            shown = p
                            state = State.Downloading(release, p)
                        }
                    }
                }
            }
            if (total != null && part.length() != total) error("download incomplete")
        }
        target.delete()
        if (!part.renameTo(target)) error("could not save the update")
        return target
    }

    private fun tempDir() = File(System.getProperty("java.io.tmpdir"), "StreamFlixUpdate").apply { mkdirs() }

    /**
     * A small script waits for this app to close, upgrades it with the MSI (progress bar only,
     * no questions) and opens the new version.
     */
    private fun installWindows(msi: File) {
        val exe = launcher ?: error("not an installed copy")
        val pid = ProcessHandle.current().pid()
        val script = File(msi.parentFile, "update.cmd")
        script.writeText(
            """
            |@echo off
            |chcp 65001 >NUL
            |:wait
            |tasklist /FI "PID eq $pid" 2>NUL | find "$pid" >NUL
            |if not errorlevel 1 (
            |  timeout /t 1 /nobreak >NUL
            |  goto wait
            |)
            |msiexec /i "${msi.absolutePath}" /passive /norestart
            |start "" "$exe"
            |del "${msi.absolutePath}"
            |(goto) 2>NUL & del "%~f0"
            |""".trimMargin().replace("\n", "\r\n"),
        )
        // The paths can hold non-ASCII letters (user names), hence UTF-8 with chcp 65001 above.
        ProcessBuilder("cmd", "/c", "start", "StreamFlix update", "/min", script.absolutePath).start()
        com.streamflixreborn.streamflix.desktop.data.Library.flush()
        exitProcess(0)
    }

    private fun openMac(dmg: File) {
        ProcessBuilder("open", dmg.absolutePath).start()
        state = State.Idle
        dismissed = true
    }
}
