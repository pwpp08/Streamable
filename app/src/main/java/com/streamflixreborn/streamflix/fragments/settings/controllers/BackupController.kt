package com.streamflixreborn.streamflix.fragments.settings.controllers

import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.constraintlayout.widget.Group
import androidx.lifecycle.lifecycleScope
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.backup.BackupRestoreManager
import com.streamflixreborn.streamflix.backup.ProviderBackupContext
import com.streamflixreborn.streamflix.database.AppDatabase
import com.streamflixreborn.streamflix.providers.Provider
import com.streamflixreborn.streamflix.providers.TmdbProvider
import com.streamflixreborn.streamflix.utils.MetadataLanguage
import com.streamflixreborn.streamflix.utils.ProfileManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Settings > Backup: JSON export/import of user data and ZIP export/import of the databases.
 *
 * Uses the system document picker when one exists. Devices without one (e.g. Android TV
 * projectors without DocumentsUI) get app-folder and Downloads alternatives instead.
 *
 * Must be constructed while the fragment is being created (as a property initializer),
 * because it registers activity-result launchers.
 */
class BackupController(
    private val fragment: androidx.preference.PreferenceFragmentCompat,
    private val isTv: Boolean,
) {
    private lateinit var host: SettingsHost
    private var loadingDialog: AlertDialog? = null
    private val tag = if (isTv) "BackupTv" else "BackupMobile"

    private val manager: BackupRestoreManager by lazy { createManager() }

    private val exportJsonLauncher = fragment.registerForActivityResult(
        ActivityResultContracts.CreateDocument(MIME_JSON),
    ) { uri -> uri?.let { launch { exportJson(it) } } }

    private val importJsonLauncher = fragment.registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { launch { importJson(readText(it)) } } }

    private val exportZipLauncher = fragment.registerForActivityResult(
        ActivityResultContracts.CreateDocument(MIME_ZIP),
    ) { uri -> uri?.let { launch { exportZip(it) } } }

    private val importZipLauncher = fragment.registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { launch { importZip(readBytes(it)) } } }

    val controller: SettingsController = object : SettingsController {
        override fun bind() {
            host.onClick("key_backup_export") { exportJsonOptions() }
            host.onClick("key_backup_import") { importJsonOptions() }
            host.onClick("key_backup_export_db") { exportZipOptions() }
            host.onClick("key_backup_import_db") { importZipOptions() }
            host.onClick("key_backup_refresh_cache") { confirmRefreshCache() }
        }
    }

    fun attach(host: SettingsHost): SettingsController {
        this.host = host
        return controller
    }

    private fun launch(block: suspend () -> Unit) {
        fragment.lifecycleScope.launch { block() }
    }

    private fun createManager(): BackupRestoreManager {
        val context = fragment.requireContext()
        val providers = Provider.providers.keys.toMutableList<Provider>().apply {
            MetadataLanguage.supported.forEach { add(TmdbProvider(it)) }
        }
        return BackupRestoreManager(
            context,
            providers.mapNotNull { provider ->
                runCatching {
                    val db = AppDatabase.getInstanceForProvider(provider.name, context)
                    ProviderBackupContext(
                        name = provider.name,
                        movieDao = db.movieDao(),
                        tvShowDao = db.tvShowDao(),
                        episodeDao = db.episodeDao(),
                        seasonDao = db.seasonDao(),
                        provider = provider,
                    )
                }.onFailure { Log.w(tag, "Skipping ${provider.name}: ${it.message}") }.getOrNull()
            },
        )
    }

    private fun timestamp() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
    private val device get() = if (isTv) "tv" else "mobile"

    // --- Option menus ---

    private fun exportJsonOptions() {
        val fileName = "streamflix_${device}_backup_${timestamp()}.json"
        chooseTarget(
            R.string.backup_export_title, MIME_JSON, picker = { exportJsonLauncher.launch(fileName) },
            local = { launch { saveLocal(fileName, R.string.backup_export_title) { manager.exportUserData()?.toByteArray() } } },
            downloads = { launch { saveDownloads(fileName, MIME_JSON, R.string.backup_export_title) { manager.exportUserData()?.toByteArray() } } },
        )
    }

    private fun exportZipOptions() {
        val fileName = "streamflix_${device}_db_backup_${timestamp()}.zip"
        chooseTarget(
            R.string.backup_db_export_option, MIME_ZIP, picker = { exportZipLauncher.launch(fileName) },
            local = { launch { saveLocal(fileName, R.string.backup_db_export_title) { manager.exportDatabaseZip() } } },
            downloads = { launch { saveDownloads(fileName, MIME_ZIP, R.string.backup_db_export_title) { manager.exportDatabaseZip() } } },
        )
    }

    private fun importJsonOptions() = chooseSource(
        R.string.backup_import_title, MIME_JSON,
        picker = { importJsonLauncher.launch(arrayOf(MIME_JSON)) },
        local = { pickLocalFile("json", R.string.backup_import_local_option) { file -> launch { importJson(file.readTextOrNull()) } } },
    )

    private fun importZipOptions() = chooseSource(
        R.string.backup_db_import_option, MIME_ZIP,
        picker = { importZipLauncher.launch(arrayOf(MIME_ZIP)) },
        local = { pickLocalFile("zip", R.string.backup_db_import_option) { file -> launch { importZip(file.readBytesOrNull()) } } },
    )

    /** Phones go straight to the picker; TVs (or phones without one) choose where to save. */
    private fun chooseTarget(titleRes: Int, mime: String, picker: () -> Unit, local: () -> Unit, downloads: () -> Unit) {
        val hasPicker = hasHandler(Intent.ACTION_CREATE_DOCUMENT, mime)
        if (hasPicker && !isTv) {
            launchPicker(picker, local)
            return
        }
        val options = buildList<Pair<Int, () -> Unit>> {
            if (hasPicker) add(R.string.backup_export_picker_option to { launchPicker(picker, local) })
            add(R.string.backup_export_local_option to local)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) add(R.string.backup_export_downloads_option to downloads)
        }
        showOptions(titleRes, options)
    }

    private fun chooseSource(titleRes: Int, mime: String, picker: () -> Unit, local: () -> Unit) {
        val hasPicker = hasHandler(Intent.ACTION_OPEN_DOCUMENT, mime)
        if (hasPicker && !isTv) {
            launchPicker(picker, local)
            return
        }
        val options = buildList<Pair<Int, () -> Unit>> {
            if (hasPicker) add(R.string.backup_import_picker_option to { launchPicker(picker, local) })
            add(R.string.backup_import_local_option to local)
        }
        showOptions(titleRes, options)
    }

    private fun launchPicker(picker: () -> Unit, fallback: () -> Unit) {
        try {
            picker()
        } catch (error: ActivityNotFoundException) {
            Log.w(tag, "No document picker available, using the app folder", error)
            host.toast(R.string.backup_picker_unavailable, long = true)
            fallback()
        }
    }

    private fun showOptions(titleRes: Int, options: List<Pair<Int, () -> Unit>>) {
        host.dialog()
            .setTitle(titleRes)
            .setItems(options.map { host.string(it.first) }.toTypedArray()) { _, which -> options[which].second() }
            .show()
    }

    private fun hasHandler(action: String, mime: String): Boolean =
        Intent(action).addCategory(Intent.CATEGORY_OPENABLE).setType(mime)
            .resolveActivity(fragment.requireContext().packageManager) != null

    // --- Export ---

    private suspend fun exportJson(uri: Uri) = withLoading(R.string.backup_export_title) {
        val data = withContext(Dispatchers.IO) { manager.exportUserData() }
        if (data == null) {
            host.toast(R.string.backup_data_not_generated, long = true)
            return@withLoading
        }
        writeToUri(uri, data.toByteArray(), R.string.backup_export_success)
    }

    private suspend fun exportZip(uri: Uri) = withLoading(R.string.backup_db_export_title) {
        val data = withContext(Dispatchers.IO) { manager.exportDatabaseZip() }
        if (data == null) {
            host.toast(R.string.backup_data_not_generated, long = true)
            return@withLoading
        }
        writeToUri(uri, data, R.string.backup_db_export_success)
    }

    private suspend fun writeToUri(uri: Uri, data: ByteArray, successRes: Int) {
        val written = withContext(Dispatchers.IO) {
            runCatching {
                fragment.requireContext().contentResolver.openOutputStream(uri)?.use { it.write(data) } != null
            }.onFailure { Log.e(tag, "Error writing backup", it) }.getOrDefault(false)
        }
        host.toast(if (written) successRes else R.string.backup_export_error_write, long = true)
    }

    private suspend fun saveLocal(fileName: String, titleRes: Int, produce: suspend () -> ByteArray?) = withLoading(titleRes) {
        val data = withContext(Dispatchers.IO) { produce() }
        if (data == null || data.isEmpty()) {
            host.toast(R.string.backup_data_not_generated, long = true)
            return@withLoading
        }
        runCatching {
            val file = withContext(Dispatchers.IO) {
                File(backupDirectory(), fileName).apply {
                    parentFile?.mkdirs()
                    writeBytes(data)
                }
            }
            host.toast(host.string(R.string.backup_export_saved_to, file.absolutePath), long = true)
        }.onFailure {
            Log.e(tag, "Error writing local backup", it)
            host.toast(R.string.backup_export_error_write, long = true)
        }
    }

    private suspend fun saveDownloads(fileName: String, mime: String, titleRes: Int, produce: suspend () -> ByteArray?) =
        withLoading(titleRes) {
            val data = withContext(Dispatchers.IO) { produce() }
            if (data == null || data.isEmpty()) {
                host.toast(R.string.backup_data_not_generated, long = true)
                return@withLoading
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@withLoading
            runCatching {
                withContext(Dispatchers.IO) {
                    val resolver = fragment.requireContext().contentResolver
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                        put(MediaStore.Downloads.MIME_TYPE, mime)
                        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/StreamFlix")
                    }
                    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        ?: error("Unable to create download entry")
                    resolver.openOutputStream(uri)?.use { it.write(data) } ?: error("Unable to open output stream")
                }
                host.toast(host.string(R.string.backup_export_saved_to, "Downloads/StreamFlix/$fileName"), long = true)
            }.onFailure {
                Log.e(tag, "Error writing backup to Downloads", it)
                host.toast(R.string.backup_export_error_write, long = true)
            }
        }

    // --- Import ---

    private suspend fun readText(uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            fragment.requireContext().contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
        }.onFailure { Log.e(tag, "Error reading backup", it) }.getOrNull()
    }

    private suspend fun readBytes(uri: Uri): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            fragment.requireContext().contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.onFailure { Log.e(tag, "Error reading backup", it) }.getOrNull()
    }

    private fun File.readTextOrNull(): String? = runCatching { readText() }.getOrNull()
    private fun File.readBytesOrNull(): ByteArray? = runCatching { readBytes() }.getOrNull()

    private suspend fun importJson(json: String?) = withLoading(R.string.backup_import_title) {
        if (json == null) {
            host.toast(R.string.backup_import_read_error, long = true)
            return@withLoading
        }
        if (json.isBlank()) {
            host.toast(R.string.backup_import_empty_file, long = true)
            return@withLoading
        }
        val metadata = withContext(Dispatchers.IO) { runCatching { manager.parseExportMetadata(json) }.getOrNull() }
        val active = ProfileManager.activeProfile
        if (metadata?.profileId != null && active != null && metadata.profileId != active.id) {
            if (!confirmForeignProfile(metadata.profileName ?: metadata.profileId, active.name)) return@withLoading
        }
        val success = withContext(Dispatchers.IO) {
            runCatching { manager.importUserData(json) }.onFailure { Log.e(tag, "Import failed", it) }.getOrDefault(false)
        }
        host.toast(if (success) R.string.backup_import_success else R.string.backup_import_error, long = true)
    }

    private suspend fun confirmForeignProfile(backupProfile: String, activeProfile: String): Boolean =
        suspendCancellableCoroutine { cont ->
            host.dialog()
                .setTitle(R.string.profile_import_warning_title)
                .setMessage(host.string(R.string.profile_import_warning_message, backupProfile, activeProfile))
                .setPositiveButton(android.R.string.ok) { _, _ -> if (cont.isActive) cont.resume(true) }
                .setNegativeButton(android.R.string.cancel) { _, _ -> if (cont.isActive) cont.resume(false) }
                .setOnCancelListener { if (cont.isActive) cont.resume(false) }
                .show()
        }

    private suspend fun importZip(bytes: ByteArray?) = withLoading(R.string.backup_db_import_title) {
        if (bytes == null) {
            host.toast(R.string.backup_import_read_error, long = true)
            return@withLoading
        }
        if (bytes.isEmpty()) {
            host.toast(R.string.backup_import_empty_file, long = true)
            return@withLoading
        }
        val success = withContext(Dispatchers.IO) {
            runCatching { manager.importDatabaseZip(bytes) }.onFailure { Log.e(tag, "DB import failed", it) }.getOrDefault(false)
        }
        host.toast(if (success) R.string.backup_db_import_success else R.string.backup_import_error, long = true)
    }

    // --- Local backup folder ---

    private fun backupDirectory(): File {
        val context = fragment.requireContext()
        return File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir, "backups")
    }

    private fun pickLocalFile(extension: String, titleRes: Int, onImport: (File) -> Unit) {
        val files = backupDirectory()
            .listFiles { file -> file.isFile && file.extension.equals(extension, ignoreCase = true) }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
        if (files.isEmpty()) {
            host.toast(R.string.backup_import_no_local_files, long = true)
            return
        }
        val format = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        host.dialog()
            .setTitle(titleRes)
            .setItems(files.map { "${format.format(Date(it.lastModified()))}  ${it.name}" }.toTypedArray()) { _, which ->
                val file = files[which]
                host.dialog()
                    .setTitle(file.name)
                    .setItems(arrayOf(host.string(R.string.backup_import_action), host.string(R.string.backup_delete_action))) { _, action ->
                        if (action == 0) onImport(file) else confirmDelete(file) { pickLocalFile(extension, titleRes, onImport) }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            .show()
    }

    private fun confirmDelete(file: File, onDeleted: () -> Unit) {
        host.dialog()
            .setTitle(R.string.backup_delete_confirm_title)
            .setMessage(host.string(R.string.backup_delete_confirm_message, file.name))
            .setPositiveButton(R.string.backup_delete_action) { _, _ ->
                val deleted = runCatching { file.delete() }.getOrDefault(false)
                host.toast(if (deleted) R.string.backup_delete_success else R.string.backup_delete_error, long = true)
                if (deleted) onDeleted()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // --- Cache refresh ---

    private fun confirmRefreshCache() {
        host.dialog()
            .setTitle(R.string.settings_refresh_cache_confirm)
            .setMessage(R.string.settings_refresh_cache_message)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                launch {
                    manager.refreshCachesFromDatabase()
                    host.toast(R.string.settings_refresh_cache_success)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // --- Loading dialog ---

    private suspend fun <T> withLoading(titleRes: Int, block: suspend () -> T): T {
        showLoading(titleRes)
        return try {
            block()
        } finally {
            loadingDialog?.dismiss()
            loadingDialog = null
        }
    }

    private fun showLoading(titleRes: Int) {
        if (!fragment.isAdded) return
        loadingDialog?.takeIf { it.isShowing }?.let {
            it.setTitle(titleRes)
            return
        }
        val view = LayoutInflater.from(fragment.requireContext()).inflate(
            if (isTv) R.layout.layout_is_loading_tv else R.layout.layout_is_loading_mobile,
            null,
        )
        view.findViewById<TextView>(R.id.tv_is_loading_error)?.visibility = View.GONE
        view.findViewById<Group>(R.id.g_is_loading_retry)?.visibility = View.GONE
        loadingDialog = host.dialog()
            .setTitle(titleRes)
            .setView(view)
            .setCancelable(false)
            .create()
            .apply {
                setCanceledOnTouchOutside(false)
                show()
            }
    }

    private companion object {
        const val MIME_JSON = "application/json"
        const val MIME_ZIP = "application/zip"
    }
}
